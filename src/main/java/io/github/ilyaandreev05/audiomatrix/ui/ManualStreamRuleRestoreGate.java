package io.github.ilyaandreev05.audiomatrix.ui;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Defers only manual-save rollback while GNOME temporarily owns a stream control. */
final class ManualStreamRuleRestoreGate {
    record DrainResult(boolean attempted, CompletableFuture<Void> completion) {}

    private CompletableFuture<Void> restoreTail = CompletableFuture.completedFuture(null);
    private long restoreGeneration;
    private long observedRestoreGeneration;

    private boolean pending;
    private boolean confirmationBarrierOpen;

    synchronized CompletableFuture<Void> restoreOrDefer(boolean commandInFlight,
            Supplier<CompletableFuture<Void>> restoreSavedRules) {
        if (commandInFlight || confirmationBarrierOpen) {
            pending = true;
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> restoration = apply(restoreSavedRules);
        track(restoration);
        return restoration;
    }

    synchronized DrainResult releaseAndDrain(Runnable releaseCommand,
            Supplier<CompletableFuture<Void>> restoreSavedRules) {
        confirmationBarrierOpen = true;
        try {
            releaseCommand.run();
        } catch (RuntimeException failure) {
            confirmationBarrierOpen = false;
            return new DrainResult(false, CompletableFuture.failedFuture(failure));
        }
        return pending ? drainPending(restoreSavedRules) : drainTrackedRestores();
    }

    synchronized DrainResult drainOrSeal(Supplier<CompletableFuture<Void>> restoreSavedRules) {
        if (!confirmationBarrierOpen) return drainTrackedRestores();
        if (pending) return drainPending(restoreSavedRules);
        DrainResult active = drainTrackedRestores();
        if (active.attempted()) return active;
        confirmationBarrierOpen = false;
        return idle();
    }

    /** Releases a failed command, drains deferred restores, then seals the barrier for the next command. */
    synchronized CompletableFuture<Void> releaseAfterFailure(Runnable releaseCommand,
            Supplier<CompletableFuture<Void>> restoreSavedRules) {
        DrainResult released = releaseAndDrain(releaseCommand, restoreSavedRules);
        return released.completion().handle((ignored, failure) -> failure)
                .thenCompose(failure -> sealAfterFailure(failure, restoreSavedRules));
    }

    private CompletableFuture<Void> sealAfterFailure(Throwable initialFailure,
            Supplier<CompletableFuture<Void>> restoreSavedRules) {
        DrainResult next = drainOrSeal(restoreSavedRules);
        CompletableFuture<Void> completion = next.completion();
        if (next.attempted())
            return completion.handle((ignored, failure) -> firstFailure(initialFailure, failure))
                    .thenCompose(failure -> sealAfterFailure(failure, restoreSavedRules));
        return completion.handle((ignored, failure) -> {
            Throwable first = firstFailure(initialFailure, failure);
            if (first != null) throw new java.util.concurrent.CompletionException(first);
            return null;
        });
    }

    private static Throwable firstFailure(Throwable first, Throwable second) {
        return first == null ? second : first;
    }

    private DrainResult drainPending(Supplier<CompletableFuture<Void>> restoreSavedRules) {
        if (!pending) return drainTrackedRestores();
        pending = false;
        track(apply(restoreSavedRules));
        return drainTrackedRestores();
    }

    private void track(CompletableFuture<Void> restoration) {
        restoreTail = restoreTail.isDone()
                ? restoration
                : CompletableFuture.allOf(restoreTail, restoration);
        restoreGeneration++;
    }

    private DrainResult drainTrackedRestores() {
        if (restoreGeneration == observedRestoreGeneration && restoreTail.isDone()) return idle();
        observedRestoreGeneration = restoreGeneration;
        return new DrainResult(true, restoreTail);
    }

    private static CompletableFuture<Void> apply(Supplier<CompletableFuture<Void>> restoreSavedRules) {
        try {
            CompletableFuture<Void> restoration = restoreSavedRules.get();
            return restoration == null ? CompletableFuture.failedFuture(
                    new IllegalStateException("Saved stream-rule restore returned no completion result")) : restoration;
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static DrainResult idle() {
        return new DrainResult(false, CompletableFuture.completedFuture(null));
    }
}
