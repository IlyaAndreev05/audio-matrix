package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.gnome.GnomeControlBridge;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.VersionedConfig;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** Runtime stream edits are temporary: confirm them without immediately reasserting an app rule. */
final class GnomeCommandConfirmation {
    private GnomeCommandConfirmation() {}

    static CompletableFuture<Void> confirm(AppShellState shell, GnomeControlBridge.Command command) {
        if ("open".equals(command.name())) return shell.requestGnomeStatePublication(new CompletableFuture<>());
        return shell.onRuntimeAudio(() -> {
            RuntimeStreamReadback expected = shell.gnomeStreamReadback;
            java.util.concurrent.atomic.AtomicReference<PipeWireBackend.Snapshot> fresh = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicLong refreshGeneration = new java.util.concurrent.atomic.AtomicLong(-2);
            Supplier<CompletableFuture<Void>> refreshAndObserve = () ->
                    shell.backend.refresh().thenAccept(observation -> {
                        if (!observation.connected()) throw new IllegalStateException(observation.error());
                        if (observation.capturedAtMillis() >= shell.snapshot.capturedAtMillis())
                            shell.snapshot = observation;
                        shell.requestUiRefresh();
                    });
            Supplier<CompletableFuture<Void>> publishState =
                    () -> shell.requestGnomeStatePublication(new CompletableFuture<>());
            Supplier<CompletableFuture<Void>> refreshRuntime = () -> {
                long before = shell.backend.connectionGeneration();
                return shell.backend.refresh().thenAccept(observation -> {
                    long after = shell.backend.connectionGeneration();
                    refreshGeneration.set(before == after ? after : -2);
                    fresh.set(observation);
                    if (observation.capturedAtMillis() >= shell.snapshot.capturedAtMillis())
                        shell.snapshot = observation;
                    shell.requestUiRefresh();
                });
            };
            Supplier<CompletableFuture<Void>> publishAndVerify = () ->
                    shell.requestValidatedGnomeStatePublication(() -> verifyRuntimeReadback(expected, fresh.get(),
                            shell.gnomePublishedObservation, refreshGeneration.get(), shell.backend.connectionGeneration()));
            return confirm(command, () -> {
                VersionedConfig config = shell.store.snapshot();
                PipeWireBackend.Snapshot observation = shell.snapshot;
                return shell.applyConfiguredDeviceRules(observation, config)
                        .thenCompose(ignored -> shell.applyConfiguredStreamRules(observation, config));
            }, runtimeStreamControl(command) ? refreshRuntime : refreshAndObserve,
                    runtimeStreamControl(command) ? publishAndVerify : publishState,
                    () -> shell.releaseGnomeAudioCommand(command),
                    shell::drainDeferredManualStreamRuleRestore,
                    refreshAndObserve, runtimeStreamControl(command) ? publishAndVerify : publishState);
        }).whenComplete((ignored, failure) -> shell.lastMixerCommandWriteMillis =
                Math.max(shell.lastMixerCommandWriteMillis, System.currentTimeMillis()));
    }

    static CompletableFuture<Void> publishRuntimeReadback(Supplier<CompletableFuture<Void>> publish,
            RuntimeStreamReadback expected, Supplier<PipeWireBackend.Snapshot> fresh) {
        return publish.get().thenRun(() -> {
            if (expected == null) throw new IllegalStateException("Stream readback target was not captured");
            expected.verify(fresh.get());
        });
    }
    static void verifyRuntimeReadback(RuntimeStreamReadback expected, PipeWireBackend.Snapshot fresh,
            PipeWireBackend.Snapshot published, long refreshGeneration, long publicationGeneration) {
        if (expected == null) throw new IllegalStateException("Stream readback target was not captured");
        expected.verifyGeneration(refreshGeneration);
        expected.verifyGeneration(publicationGeneration);
        if (fresh == null || published == null)
            throw new IllegalStateException("Stream readback or GNOME publication unavailable");
        expected.verify(fresh);
        expected.verify(published);
    }


    static boolean runtimeStreamControl(GnomeControlBridge.Command command) {
        return switch (command.name()) {
            case "set_stream_volume", "toggle_stream_mute" -> true;
            case "set_app_volume", "toggle_app_mute" -> command.value().split("\\t", 2)[0].startsWith("stream:");
            default -> false;
        };
    }

    static CompletableFuture<Void> confirm(GnomeControlBridge.Command command,
            Supplier<CompletableFuture<Void>> applySavedRules,
            Supplier<CompletableFuture<Void>> refreshAndObserve,
            Supplier<CompletableFuture<Void>> publishState, Runnable releaseCommand) {
        return confirm(command, applySavedRules, refreshAndObserve, publishState,
                () -> {
                    releaseCommand.run();
                    return idleDrain();
                }, GnomeCommandConfirmation::idleDrain,
                () -> CompletableFuture.completedFuture(null),
                () -> CompletableFuture.completedFuture(null));
    }

    static CompletableFuture<Void> confirm(GnomeControlBridge.Command command,
            Supplier<CompletableFuture<Void>> applySavedRules,
            Supplier<CompletableFuture<Void>> refreshAndObserve,
            Supplier<CompletableFuture<Void>> publishState,
            Supplier<ManualStreamRuleRestoreGate.DrainResult> releaseAndDrain,
            Supplier<ManualStreamRuleRestoreGate.DrainResult> drainOrSeal,
            Supplier<CompletableFuture<Void>> refreshAfterRestore,
            Supplier<CompletableFuture<Void>> publishAfterRestore) {
        if (runtimeStreamControl(command)) {
            return sequence(refreshAndObserve, publishState)
                    .handle((ignored, failure) -> failure)
                    .thenCompose(failure -> finishAfterConfirmation(failure, safeDrain(releaseAndDrain),
                            drainOrSeal, refreshAfterRestore, publishAfterRestore));
        }

        ManualStreamRuleRestoreGate.DrainResult initialDrain = safeDrain(releaseAndDrain);
        return complete(initialDrain).handle((ignored, failure) -> failure)
                .thenCompose(drainFailure -> sequence(applySavedRules, publishState)
                        .handle((ignored, confirmationFailure) -> firstFailure(drainFailure, confirmationFailure)))
                .thenCompose(failure -> finishAfterConfirmation(failure,
                        new ManualStreamRuleRestoreGate.DrainResult(initialDrain.attempted(),
                                CompletableFuture.completedFuture(null)),
                        drainOrSeal, refreshAfterRestore, publishAfterRestore));
    }

    private static CompletableFuture<Void> finishAfterConfirmation(Throwable initialFailure,
            ManualStreamRuleRestoreGate.DrainResult initialDrain,
            Supplier<ManualStreamRuleRestoreGate.DrainResult> drainOrSeal,
            Supplier<CompletableFuture<Void>> refreshAfterRestore,
            Supplier<CompletableFuture<Void>> publishAfterRestore) {
        return complete(initialDrain).handle((ignored, restoreFailure) -> firstFailure(initialFailure, restoreFailure))
                .thenCompose(failure -> {
                    CompletableFuture<Void> refreshed = initialDrain.attempted()
                            ? sequence(refreshAfterRestore, publishAfterRestore)
                            : CompletableFuture.completedFuture(null);
                    return refreshed.handle((ignored, publishFailure) -> firstFailure(failure, publishFailure))
                            .thenCompose(pendingFailure -> {
                                ManualStreamRuleRestoreGate.DrainResult next = safeDrain(drainOrSeal);
                                if (next.attempted())
                                    return finishAfterConfirmation(pendingFailure, next, drainOrSeal,
                                            refreshAfterRestore, publishAfterRestore);
                                return complete(next).<Void>handle((nothing, finalFailure) -> {
                                    Throwable failureAfterDrain = firstFailure(pendingFailure, finalFailure);
                                    if (failureAfterDrain != null)
                                        throw completionFailure(failureAfterDrain);
                                    return null;
                                });
                            });
                });
    }

    private static CompletableFuture<Void> sequence(Supplier<CompletableFuture<Void>> first,
                                                     Supplier<CompletableFuture<Void>> second) {
        CompletableFuture<Void> initial;
        try {
            initial = first.get();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (initial == null)
            return CompletableFuture.failedFuture(new IllegalStateException("Confirmation step returned no completion result"));
        return initial.thenCompose(ignored -> {
            try {
                CompletableFuture<Void> next = second.get();
                return next == null ? CompletableFuture.failedFuture(
                        new IllegalStateException("Confirmation step returned no completion result")) : next;
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        });
    }

    private static CompletableFuture<Void> complete(ManualStreamRuleRestoreGate.DrainResult drain) {
        return drain == null || drain.completion() == null
                ? CompletableFuture.failedFuture(new IllegalStateException("Deferred restore returned no completion result"))
                : drain.completion();
    }

    private static ManualStreamRuleRestoreGate.DrainResult safeDrain(
            Supplier<ManualStreamRuleRestoreGate.DrainResult> drain) {
        try {
            ManualStreamRuleRestoreGate.DrainResult result = drain.get();
            return result == null ? new ManualStreamRuleRestoreGate.DrainResult(false,
                    CompletableFuture.failedFuture(new IllegalStateException("Deferred restore returned no result")))
                    : result;
        } catch (RuntimeException failure) {
            return new ManualStreamRuleRestoreGate.DrainResult(false, CompletableFuture.failedFuture(failure));
        }
    }

    private static ManualStreamRuleRestoreGate.DrainResult idleDrain() {
        return new ManualStreamRuleRestoreGate.DrainResult(false, CompletableFuture.completedFuture(null));
    }

    private static Throwable firstFailure(Throwable first, Throwable second) {
        return first == null ? second : first;
    }

    private static CompletionException completionFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        return new CompletionException(cause);
    }
}
