package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeControlBridge;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeStreamReadbackTest {
    private static AudioNode node(int id, String name, double volume, boolean muted) {
        return new AudioNode(id, name, name, "Stream/Output/Audio", "running", "app", "process", "app.id", 2,
                List.of(), volume, muted, null);
    }
    private static PipeWireBackend.Snapshot snapshot(AudioNode... nodes) {
        return new PipeWireBackend.Snapshot(true, "", List.of(), List.of(), List.of(nodes), List.of(), Map.of(), -1, 1);
    }
    private static RuntimeStreamReadback target() {
        return RuntimeStreamReadback.capture(List.of(node(96, "ess", 0.830584, false)), "96\t0.125", false);
    }
    private static CompletableFuture<Void> done() { return CompletableFuture.completedFuture(null); }

    @Test void writerSuccessWithOldReadbackPublishesThenFailsWithoutSavedRules() {
        List<String> events = new ArrayList<>();
        var expected = target();
        var old = snapshot(node(96, "ess", 0.830584, false));
        CompletableFuture<Void> result = done().thenCompose(ignored -> GnomeCommandConfirmation.confirm(
                new GnomeControlBridge.Command("set_stream_volume", "96\t0.125"),
                () -> { events.add("rules"); return done(); },
                () -> { events.add("refresh"); return done(); },
                () -> GnomeCommandConfirmation.publishRuntimeReadback(() -> { events.add("publish"); return done(); }, expected, () -> old),
                () -> events.add("release")));
        CompletionException failure = assertThrows(CompletionException.class, result::join);
        assertTrue(failure.getCause().getMessage().contains("0.830584"));
        assertEquals(List.of("refresh", "publish", "release"), events);
    }

    @Test void exactAndRoundedCubicReadbackSucceed() {
        assertDoesNotThrow(() -> target().verify(snapshot(node(96, "ess", 0.125, false))));
        double rounded = Math.pow(0.50, 3);
        var expected = RuntimeStreamReadback.capture(List.of(node(96, "ess", 1, false)), "96\t0.128", false);
        assertDoesNotThrow(() -> expected.verify(snapshot(node(96, "ess", rounded, false))));
        assertThrows(IllegalStateException.class, () -> expected.verify(snapshot(node(96, "ess", Math.pow(0.51, 3), false))));
    }
    @Test void namedStreamWriterSuccessRequiresMatchingPublishedReadback() {
        List<String> events = new ArrayList<>();
        var expected = RuntimeStreamReadback.capture(List.of(node(96, "ess", .830584, true)), "stream:ess\t0.125", false);
        var actual = snapshot(node(96, "ess", .125, false));
        CompletableFuture<Void> result = done().thenCompose(ignored -> GnomeCommandConfirmation.confirm(
                new GnomeControlBridge.Command("set_app_volume", "stream:ess\t0.125"),
                () -> { events.add("rules"); return done(); },
                () -> { events.add("refresh"); return done(); },
                () -> GnomeCommandConfirmation.publishRuntimeReadback(() -> { events.add("publish"); return done(); }, expected, () -> actual),
                () -> events.add("release")));
        assertDoesNotThrow(result::join);
        assertEquals(List.of("refresh", "publish", "release"), events);
    }


    @Test void newerPublishedMismatchFailsEvenWhenFreshReadbackMatched() {
        var expected = RuntimeStreamReadback.capture(List.of(node(96, "ess", .830584, false)), "96\t0.125", false, 7);
        var fresh = snapshot(node(96, "ess", .125, false));
        var newer = snapshot(node(96, "ess", .830584, false));
        AtomicReference<PipeWireBackend.Snapshot> published = new AtomicReference<>();
        try (var lane = new GnomePublicationLane(() -> published.set(newer), () -> false)) {
            CompletableFuture<Void> result = lane.requestValidated(() ->
                    GnomeCommandConfirmation.verifyRuntimeReadback(expected, fresh, published.get(), 7, 7));
            assertTrue(assertThrows(CompletionException.class, result::join).getCause().getMessage().contains("readback mismatch"));
            assertSame(newer, published.get());
        }
    }

    @Test void skippedPublicationCannotReusePreviouslyPublishedMatchingSnapshot() {
        var expected = RuntimeStreamReadback.capture(List.of(node(96, "ess", .830584, false)), "96\t0.125", false, 7);
        var matching = snapshot(node(96, "ess", .125, false));
        AtomicReference<PipeWireBackend.Snapshot> published = new AtomicReference<>(matching);
        // The publication lane resets the marker before invoking a publisher that may skip.
        try (var lane = new GnomePublicationLane(() -> published.set(null), () -> false)) {
            var result = lane.requestValidated(() ->
                    GnomeCommandConfirmation.verifyRuntimeReadback(expected, matching, published.get(), 7, 7));
            assertTrue(assertThrows(CompletionException.class, result::join).getCause().getMessage().contains("publication unavailable"));
        }
    }

    @Test void reconnectWithSameStreamIdAndNameCannotConfirm() {
        var expected = RuntimeStreamReadback.capture(List.of(node(96, "ess", .830584, false)), "96\t0.125", false, 7);
        var actual = snapshot(node(96, "ess", .125, false));
        assertDoesNotThrow(() -> GnomeCommandConfirmation.verifyRuntimeReadback(expected, actual, actual, 7, 7));
        assertThrows(IllegalStateException.class, () ->
                GnomeCommandConfirmation.verifyRuntimeReadback(expected, actual, actual, 8, 8));
        assertThrows(IllegalStateException.class, () ->
                GnomeCommandConfirmation.verifyRuntimeReadback(expected, actual, actual, 7, 8));
        assertThrows(IllegalStateException.class, () ->
                GnomeCommandConfirmation.verifyRuntimeReadback(expected, actual, null, 7, 7));
    }

    @Test void missingDisconnectedNonFiniteAndMutedReadbacksFail() {
        assertThrows(IllegalStateException.class, () -> target().verify(snapshot()));
        assertThrows(IllegalStateException.class, () -> target().verify(snapshot(node(96, "replacement", 0.125, false))));
        assertThrows(IllegalStateException.class, () -> target().verify(PipeWireBackend.Snapshot.offline("offline")));
        assertThrows(IllegalStateException.class, () -> target().verify(snapshot(node(96, "ess", Double.NaN, false))));
        assertThrows(IllegalStateException.class, () -> target().verify(snapshot(node(96, "ess", 0.125, true))));
    }

    @Test void namedTargetsAndMuteAreCheckedForEveryOriginallySelectedStream() {
        var a = node(1, "same", .3, true);
        var b = node(2, "same", .3, false);
        var mute = RuntimeStreamReadback.capture(List.of(a, b), "stream:same", true);
        assertDoesNotThrow(() -> mute.verify(snapshot(node(1, "same", .3, true), node(2, "same", .3, true))));
        assertThrows(IllegalStateException.class, () -> mute.verify(snapshot(node(1, "same", .3, true))));
        assertThrows(IllegalStateException.class, () -> mute.verify(snapshot(node(1, "same", .3, true), node(2, "same", .3, false))));
        var unmute = RuntimeStreamReadback.capture(List.of(a), "stream:same", true);
        assertDoesNotThrow(() -> unmute.verify(snapshot(node(1, "same", .3, false))));
    }

    @Test void failedReadbackWaitsForDeferredRestoreAndPublishesRestoredStateBeforeError() {
        var gate = new ManualStreamRuleRestoreGate();
        AtomicReference<Double> actual = new AtomicReference<>(0.830584);
        List<Double> published = new ArrayList<>();
        CompletableFuture<Void> restore = new CompletableFuture<>();
        CompletableFuture<Void> afterPublish = new CompletableFuture<>();
        gate.restoreOrDefer(true, RuntimeStreamReadbackTest::done);
        CompletableFuture<Void> result = GnomeCommandConfirmation.confirm(
                new GnomeControlBridge.Command("set_stream_volume", "96\t0.125"),
                () -> { fail("ordinary saved rules must not apply"); return done(); },
                RuntimeStreamReadbackTest::done,
                () -> GnomeCommandConfirmation.publishRuntimeReadback(() -> { published.add(actual.get()); return done(); },
                        target(), () -> snapshot(node(96, "ess", actual.get(), false))),
                () -> gate.releaseAndDrain(() -> {}, () -> restore.thenRun(() -> actual.set(.8))),
                () -> gate.drainOrSeal(RuntimeStreamReadbackTest::done),
                RuntimeStreamReadbackTest::done,
                () -> { published.add(actual.get()); return afterPublish; });
        assertFalse(result.isDone());
        restore.complete(null);
        assertEquals(List.of(0.830584, 0.8), published);
        assertFalse(result.isDone());
        afterPublish.complete(null);
        assertTrue(assertThrows(CompletionException.class, result::join).getCause().getMessage().contains("readback mismatch"));
    }
}
