package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationStreamManualVolumeTest extends ApplicationStreamRuleFixture {
    @Test
    void manualVolumeSurvivesNewerOldRuleObservationsUntilTheSavedControlMatches() {
        AudioNode original = stream(17, "player", 0.8, false);
        assertSucceeded(apply(snapshot(original), 0.8, false));
        CompletableFuture<Void> manual = applier.setManualVolume(original, 0.35, "drag-a");
        assertMute(0, false, 0.35);
        complete(0);
        assertVolume(1, 0.35);
        complete(1);
        assertSucceeded(manual);

        CompletableFuture<Void> oldRule = apply(
                snapshotAt(Long.MAX_VALUE, stream(17, "player", 0.8, false)), 0.8, false);
        assertVolume(2, 0.35);
        complete(2);
        assertSucceeded(oldRule);
        assertEquals(3, writes.size(), "a newer observation cannot restore the old saved volume");

        CompletableFuture<Void> persisted = apply(snapshotAt(Long.MAX_VALUE, original), 0.35, false);
        assertVolume(3, 0.35);
        complete(3);
        assertSucceeded(persisted);
        assertEquals(4, writes.size(), "matching saved controls retire the override even while PipeWire still lags");
        CompletableFuture<Void> laterSaved = apply(snapshot(original), 0.8, false);
        assertVolume(4, 0.8);
        complete(4);
        assertSucceeded(laterSaved);
    }

    @Test
    void draggingAMutedStreamAwaitsUnmuteBeforeWritingTheExactRequestedVolume() {
        AudioNode muted = stream(17, "player", 0.35, true);
        CompletableFuture<Void> manual = applier.setManualVolume(muted, 0.35, "drag");
        assertMute(0, false, 0.35);
        assertEquals(1, writes.size(), "the volume must wait for unmute completion");
        assertFalse(manual.isDone());
        complete(0);
        assertVolume(1, 0.35);
        assertFalse(manual.isDone(), "the command cannot succeed before its volume write completes");
        complete(1);
        assertSucceeded(manual);
        assertEquals(2, writes.size(), "manual volume is exact even when the observation already matches");
    }

    @Test
    void invalidManualInputsFailWithoutAnyRuntimeWrite() {
        AudioNode original = stream(17, "player", 0.8, false);
        assertInvalid(applier.setManualVolume(null, 0.35, "drag"));
        for (String id : new String[] {null, "", " \t\n"})
            assertInvalid(applier.setManualVolume(original, 0.35, id));
        for (double volume : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                -Double.MIN_VALUE, Math.nextUp(1.0)})
            assertInvalid(applier.setManualVolume(original, volume, "drag"));
        assertTrue(writes.isEmpty());
        assertSucceeded(apply(snapshot(original), 0.8, false));
        assertTrue(writes.isEmpty(), "invalid input must not leave a manual override behind");
    }

    @Test
    void aStreamWithoutAnApplicationRuleKeepsItsManualVolume() {
        AudioNode original = stream(17, "player", 0.8, false);
        CompletableFuture<Void> manual = applier.setManualVolume(original, 0.35, "drag");
        complete(0);
        complete(1);
        assertSucceeded(manual);
        assertSucceeded(applier.apply(null, List.of(), null));
        assertEquals(2, writes.size(), "a missing observation must not discard manual state");

        CompletableFuture<Void> withoutRule = applier.apply(snapshotAt(Long.MAX_VALUE, original), List.of(), null);
        assertVolume(2, 0.35);
        complete(2);
        assertSucceeded(withoutRule);
        assertSucceeded(applier.apply(
                snapshotAt(Long.MAX_VALUE, stream(17, "player", 0.35, false)), List.of(), null));
        assertEquals(3, writes.size());
    }

    @Test
    void matchingSavedVolumeWithDifferentMuteDoesNotRetireTheManualOverride() {
        AudioNode original = stream(17, "player", 0.8, false);
        CompletableFuture<Void> manual = applier.setManualVolume(original, 0.35, "drag");
        complete(0);
        complete(1);
        assertSucceeded(manual);
        assertSucceeded(apply(snapshotAt(Long.MAX_VALUE, stream(17, "player", 0.35, false)), 0.35, true));
        assertEquals(2, writes.size(), "a saved mute cannot override a manual unmuted drag");

        CompletableFuture<Void> oldRule = apply(snapshotAt(Long.MAX_VALUE, original), 0.8, false);
        assertVolume(2, 0.35);
        complete(2);
        assertSucceeded(oldRule);
    }

    @Test
    void aLateClearForAnEqualVolumeCannotRemoveTheNewerIntent() {
        AudioNode original = stream(17, "player", 0.8, false);
        CompletableFuture<Void> first = applier.setManualVolume(original, 0.35, "drag-a");
        complete(0);
        assertVolume(1, 0.35);
        CompletableFuture<Void> second = applier.setManualVolume(original, 0.35, "drag-b");
        assertEquals(2, writes.size(), "the newer equal-valued intent waits for the active write");
        writes.get(1).completion().completeExceptionally(new IllegalStateException("older drag failed"));
        assertThrows(CompletionException.class, first::join);
        assertMute(2, false, 0.35);
        applier.clearManualVolume(original, "drag-a");
        complete(2);
        assertVolume(3, 0.35);
        complete(3);
        assertSucceeded(second);

        CompletableFuture<Void> retained = apply(snapshotAt(Long.MAX_VALUE, original), 0.8, false);
        assertVolume(4, 0.35);
        complete(4);
        assertSucceeded(retained);
        applier.clearManualVolume(original, "drag-b");
        CompletableFuture<Void> restored = apply(snapshot(original), 0.8, false);
        assertMute(5, false, 0.8);
        complete(5);
        assertVolume(6, 0.8);
        complete(6);
        assertSucceeded(restored);
    }

    private static void assertInvalid(CompletableFuture<Void> result) {
        assertTrue(result.isDone(), "validation must fail before any queued write");
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(CompletionException.class, result::join).getCause());
    }
}
