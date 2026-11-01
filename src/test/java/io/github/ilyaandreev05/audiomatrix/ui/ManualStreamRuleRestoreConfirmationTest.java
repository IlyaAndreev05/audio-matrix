package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.gnome.GnomeControlBridge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ManualStreamRuleRestoreConfirmationTest {
    @Test
    void failedManualPersistenceAtStreamOnlyReleaseBoundaryPublishesRestoredLevelBeforeAck() {
        ManualStreamRuleRestoreGate restoreGate = new ManualStreamRuleRestoreGate();
        AtomicBoolean commandInFlight = new AtomicBoolean(true);
        AtomicInteger manualRestores = new AtomicInteger();
        AtomicInteger immediateRestores = new AtomicInteger();
        AtomicInteger ordinarySavedRuleApplies = new AtomicInteger();
        AtomicReference<Double> runtimeVolume = new AtomicReference<>(0.35);
        List<Double> publishedVolumes = new ArrayList<>();
        List<String> events = new ArrayList<>();
        CompletableFuture<Void> persistence = new CompletableFuture<>();
        CompletableFuture<Void> failureRestore = persistence
                .handle((ignored, error) -> {
                    assertNotNull(error);
                    events.add("manual-save-failure");
                    return restoreGate.restoreOrDefer(commandInFlight.get(), () -> {
                        immediateRestores.incrementAndGet();
                        runtimeVolume.set(0.8);
                        return CompletableFuture.completedFuture(null);
                    });
                })
                .thenCompose(restoration -> restoration);

        CompletableFuture<Void> observation = new CompletableFuture<>();
        CompletableFuture<Void> publication = new CompletableFuture<>();
        CompletableFuture<Void> postRestoreObservation = new CompletableFuture<>();
        CompletableFuture<Void> postRestorePublication = new CompletableFuture<>();
        CompletableFuture<Void> confirmation = GnomeCommandConfirmation.confirm(
                new GnomeControlBridge.Command("set_stream_volume", "10\t0.35"),
                () -> {
                    ordinarySavedRuleApplies.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                },
                () -> {
                    events.add("observe");
                    return observation;
                },
                () -> {
                    events.add("publish-before-restore");
                    publishedVolumes.add(runtimeVolume.get());
                    return publication;
                },
                () -> restoreGate.releaseAndDrain(() -> {
                    events.add("release");
                    commandInFlight.set(false);
                    persistence.completeExceptionally(
                            new IllegalStateException("Manual volume persistence failed at release"));
                    failureRestore.join();
                    assertEquals(0, immediateRestores.get(),
                            "a failure in the release/drain boundary must join the confirmation barrier");
                }, () -> {
                    events.add("restore-saved-level");
                    manualRestores.incrementAndGet();
                    runtimeVolume.set(0.8);
                    return CompletableFuture.completedFuture(null);
                }),
                () -> restoreGate.drainOrSeal(() -> {
                    events.add("restore-late-saved-level");
                    manualRestores.incrementAndGet();
                    runtimeVolume.set(0.8);
                    return CompletableFuture.completedFuture(null);
                }),
                () -> {
                    events.add("observe-after-restore");
                    return postRestoreObservation;
                },
                () -> {
                    events.add("publish-after-restore");
                    publishedVolumes.add(runtimeVolume.get());
                    return postRestorePublication;
                });

        assertEquals(List.of("observe"), events);
        observation.complete(null);
        assertEquals(List.of("observe", "publish-before-restore"), events);
        assertTrue(commandInFlight.get());
        publication.complete(null);

        assertEquals(List.of("observe", "publish-before-restore", "release", "manual-save-failure",
                "restore-saved-level", "observe-after-restore"), events);
        assertEquals(0, immediateRestores.get());
        assertEquals(1, manualRestores.get());
        assertFalse(confirmation.isDone(), "ACK confirmation must wait for the post-restore state publication");

        postRestoreObservation.complete(null);
        assertEquals(0.8, publishedVolumes.getLast(), 0.0);
        assertEquals("publish-after-restore", events.getLast());
        assertFalse(confirmation.isDone());
        postRestorePublication.complete(null);
        confirmation.join();

        assertFalse(commandInFlight.get());
        assertEquals(0, ordinarySavedRuleApplies.get(), "runtime-only confirmation must skip ordinary saved rules");
        assertEquals(List.of(0.35, 0.8), publishedVolumes,
                "the final State/ACK projection must report the actual restored stream level");
        assertEquals(runtimeVolume.get(), publishedVolumes.getLast(), 0.0);
        assertEquals(1, manualRestores.get());
    }
    @Test
    void confirmationWaitsForAnAlreadyStartedRestoreTailAndPublishesItsFinalState() {
        ManualStreamRuleRestoreGate restoreGate = new ManualStreamRuleRestoreGate();
        AtomicBoolean commandInFlight = new AtomicBoolean(false);
        AtomicInteger manualRestores = new AtomicInteger();
        AtomicInteger ordinarySavedRuleApplies = new AtomicInteger();
        AtomicReference<Double> runtimeVolume = new AtomicReference<>(0.35);
        List<Double> publishedVolumes = new ArrayList<>();
        List<String> events = new ArrayList<>();
        CompletableFuture<Void> restoreWrite = new CompletableFuture<>();
        restoreGate.restoreOrDefer(false, () -> {
            manualRestores.incrementAndGet();
            return restoreWrite.thenRun(() -> runtimeVolume.set(0.8));
        });
        commandInFlight.set(true);

        CompletableFuture<Void> observation = new CompletableFuture<>();
        CompletableFuture<Void> publication = new CompletableFuture<>();
        CompletableFuture<Void> postRestoreObservation = new CompletableFuture<>();
        CompletableFuture<Void> postRestorePublication = new CompletableFuture<>();
        CompletableFuture<Void> confirmation = GnomeCommandConfirmation.confirm(
                new GnomeControlBridge.Command("set_stream_volume", "10\t0.35"),
                () -> {
                    ordinarySavedRuleApplies.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                },
                () -> {
                    events.add("observe");
                    return observation;
                },
                () -> {
                    events.add("publish-before-restore");
                    publishedVolumes.add(runtimeVolume.get());
                    return publication;
                },
                () -> restoreGate.releaseAndDrain(() -> {
                    events.add("release");
                    commandInFlight.set(false);
                }, () -> {
                    fail("an already-started restore must be awaited, not duplicated");
                    return CompletableFuture.completedFuture(null);
                }),
                () -> restoreGate.drainOrSeal(() -> {
                    fail("no deferred restore should be pending");
                    return CompletableFuture.completedFuture(null);
                }),
                () -> {
                    events.add("observe-after-restore");
                    return postRestoreObservation;
                },
                () -> {
                    events.add("publish-after-restore");
                    publishedVolumes.add(runtimeVolume.get());
                    return postRestorePublication;
                });

        assertEquals(List.of("observe"), events);
        observation.complete(null);
        assertEquals(List.of("observe", "publish-before-restore"), events);
        publication.complete(null);
        assertEquals(List.of("observe", "publish-before-restore", "release"), events);
        assertFalse(confirmation.isDone(), "confirmation must wait for the tracked restore future");
        assertEquals(List.of(0.35), publishedVolumes);

        restoreWrite.complete(null);
        assertEquals("observe-after-restore", events.getLast());
        postRestoreObservation.complete(null);
        assertEquals("publish-after-restore", events.getLast());
        assertEquals(0.8, publishedVolumes.getLast(), 0.0);
        postRestorePublication.complete(null);
        confirmation.join();

        assertFalse(commandInFlight.get());
        assertEquals(0, ordinarySavedRuleApplies.get(),
                "runtime-only confirmation must skip ordinary saved rules");
        assertEquals(List.of(0.35, 0.8), publishedVolumes,
                "the final State/ACK projection must report the completed restore");
        assertEquals(runtimeVolume.get(), publishedVolumes.getLast(), 0.0);
        assertEquals(1, manualRestores.get());
    }
}
