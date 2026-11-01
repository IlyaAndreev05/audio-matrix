package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationStreamManualRecoveryTest extends ApplicationStreamRuleFixture {
    @Test
    void failedManualVolumeIsNotCachedAndClearingItRestoresTheSavedControl() {
        AudioNode original = stream(17, "player", 0.8, false);
        CompletableFuture<Void> manual = applier.setManualVolume(original, 0.35, "drag");
        assertMute(0, false, 0.35);
        complete(0);
        assertVolume(1, 0.35);
        IllegalStateException failure = new IllegalStateException("manual volume failed");
        writes.get(1).completion().completeExceptionally(failure);
        assertSame(failure, assertThrows(CompletionException.class, manual::join).getCause());

        CompletableFuture<Void> retry = apply(snapshot(stream(17, "player", 0.35, false)), 0.8, false);
        assertMute(2, false, 0.35);
        complete(2);
        assertVolume(3, 0.35);
        assertFalse(retry.isDone(), "a failed write cannot be treated as success even when a snapshot matches");
        complete(3);
        assertSucceeded(retry);

        applier.clearManualVolume(original, "drag");
        CompletableFuture<Void> restored = apply(snapshot(original), 0.8, false);
        assertMute(4, false, 0.8);
        complete(4);
        assertVolume(5, 0.8);
        assertFalse(restored.isDone());
        complete(5);
        assertSucceeded(restored);
        assertSucceeded(apply(snapshot(original), 0.8, false));
        assertEquals(6, writes.size(), "only successfully restored controls may be cached");
    }

    @Test
    void failedManualUnmuteNeverWritesVolumeAndItsIntentCanBeCleared() {
        AudioNode muted = stream(17, "player", 0.0, true);
        CompletableFuture<Void> manual = applier.setManualVolume(muted, 0.35, "drag");
        assertMute(0, false, 0.35);
        IllegalStateException failure = new IllegalStateException("manual unmute failed");
        writes.getFirst().completion().completeExceptionally(failure);
        assertSame(failure, assertThrows(CompletionException.class, manual::join).getCause());
        assertEquals(1, writes.size(), "volume must not be written after a failed unmute");

        applier.clearManualVolume(muted, "drag");
        CompletableFuture<Void> restored = apply(snapshot(muted), 0.8, true);
        assertVolume(1, 0.8);
        complete(1);
        assertMute(2, true, 0.8);
        assertFalse(restored.isDone());
        complete(2);
        assertSucceeded(restored);
        assertEquals(3, writes.size());
    }
}
