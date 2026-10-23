package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationStreamRuleApplierTest extends ApplicationStreamRuleFixture {

    @Test
    void restoresVolumeEvenWhenTheOriginalSnapshotAlreadyShowsTheRestoredValue() {
        PipeWireBackend.Snapshot original = snapshot(stream(17, "player", 1.0, false));
        CompletableFuture<Void> lowered = apply(original, 0.4, false);
        assertVolume(0, 0.4);
        assertFalse(lowered.isDone());
        complete(0);
        assertSucceeded(lowered);

        CompletableFuture<Void> restored = apply(original, 1.0, false);
        assertEquals(2, writes.size());
        assertVolume(1, 1.0);
        assertFalse(restored.isDone());
        complete(1);
        assertSucceeded(restored);
    }

    @Test
    void aChangedIntentDoesNotUseTheSnapshotToleranceToSkipItsExactLevel() {
        PipeWireBackend.Snapshot original = snapshot(stream(17, "player", 0.4, false));
        assertSucceeded(apply(original, 0.4, false));
        assertTrue(writes.isEmpty());
        CompletableFuture<Void> changed = apply(original, 0.405, false);
        assertVolume(0, 0.405);
        assertFalse(changed.isDone());
        complete(0);
        assertSucceeded(changed);
    }

    @Test
    void muteAndUnmuteKeepTheSavedVolumeDespiteMutedObservationsAtZero() {
        CompletableFuture<Void> muted = apply(snapshot(stream(17, "player", 0.7, false)), 0.4, true);
        assertVolume(0, 0.4);
        complete(0);
        assertMute(1, true, 0.4);
        assertFalse(muted.isDone());
        complete(1);
        assertSucceeded(muted);

        PipeWireBackend.Snapshot observedMuted = snapshotAt(Long.MAX_VALUE, stream(17, "player", 0.0, true));
        assertSucceeded(apply(observedMuted, 0.4, true));
        assertEquals(2, writes.size(), "a stable muted poll must not rewrite its effective zero volume");
        CompletableFuture<Void> unmuted = apply(observedMuted, 0.4, false);
        assertEquals(3, writes.size());
        assertMute(2, false, 0.4);
        assertFalse(unmuted.isDone());
        complete(2);
        assertSucceeded(unmuted);
    }

    @Test
    void forcedRollbackWritesBothControlsEvenWhenTheSnapshotMatchesRollback() {
        PipeWireBackend.Snapshot original = snapshot(stream(17, "player", 1.0, false));
        CompletableFuture<Void> changed = apply(original, 0.4, true);
        complete(0);
        complete(1);
        assertSucceeded(changed);

        CompletableFuture<Void> rollback = applier.apply(original, rules(1.0, false), Map.of(), true, null);
        assertVolume(2, 1.0);
        assertFalse(rollback.isDone());
        complete(2);
        assertMute(3, false, 1.0);
        assertFalse(rollback.isDone());
        complete(3);
        assertSucceeded(rollback);
        assertEquals(4, writes.size());

        CompletableFuture<Void> repeated = applier.apply(original, rules(1.0, false), Map.of(), true, null);
        assertVolume(4, 1.0);
        complete(4);
        assertMute(5, false, 1.0);
        assertFalse(repeated.isDone());
        complete(5);
        assertSucceeded(repeated);
        assertEquals(6, writes.size(), "force must also bypass the known applied state");
    }

    @Test
    void queuedIntentsAreAppliedInOrderAndEachFutureWaitsForItsOwnControls() {
        PipeWireBackend.Snapshot original = snapshot(stream(17, "player", 1.0, false));
        CompletableFuture<Void> first = apply(original, 0.2, false);
        CompletableFuture<Void> second = apply(original, 0.4, true);
        CompletableFuture<Void> third = apply(original, 0.8, false);
        assertEquals(1, writes.size());
        assertVolume(0, 0.2);
        assertFalse(first.isDone());
        assertFalse(second.isDone());
        assertFalse(third.isDone());

        complete(0);
        assertSucceeded(first);
        assertVolume(1, 0.4);
        assertFalse(second.isDone());
        assertFalse(third.isDone());
        complete(1);
        assertMute(2, true, 0.4);
        assertFalse(second.isDone());
        assertFalse(third.isDone());
        complete(2);
        assertSucceeded(second);
        assertVolume(3, 0.8);
        assertFalse(third.isDone());
        complete(3);
        assertMute(4, false, 0.8);
        assertFalse(third.isDone());
        complete(4);
        assertSucceeded(third);
        assertEquals(5, writes.size());
    }

    @Test
    void failedVolumeIsReportedAndCannotBeCachedAsApplied() {
        PipeWireBackend.Snapshot original = snapshot(stream(17, "player", 1.0, false));
        List<Throwable> failures = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("volume write failed");
        CompletableFuture<Void> failed = applier.apply(original, rules(0.4, false), (stream, error) -> {
            assertEquals(17, stream.id());
            failures.add(error);
        });
        writes.getFirst().completion().completeExceptionally(failure);
        assertTrue(failed.isDone());
        assertSame(failure, assertThrows(CompletionException.class, failed::join).getCause());
        assertEquals(List.of(failure), failures);

        CompletableFuture<Void> retry = apply(original, 1.0, false);
        assertVolume(1, 1.0);
        complete(1);
        assertMute(2, false, 1.0);
        assertFalse(retry.isDone());
        complete(2);
        assertSucceeded(retry);
        assertSucceeded(apply(original, 1.0, false));
        assertEquals(3, writes.size());
    }

    @Test
    void failedMuteDoesNotCacheThePartiallyAppliedVolume() {
        PipeWireBackend.Snapshot original = snapshot(stream(17, "player", 1.0, false));
        CompletableFuture<Void> failed = apply(original, 0.4, true);
        complete(0);
        assertMute(1, true, 0.4);
        writes.get(1).completion().completeExceptionally(new IllegalStateException("mute write failed"));
        assertTrue(failed.isDone());
        assertThrows(CompletionException.class, failed::join);

        CompletableFuture<Void> retry = apply(original, 0.4, true);
        assertVolume(2, 0.4);
        complete(2);
        assertMute(3, true, 0.4);
        assertFalse(retry.isDone());
        complete(3);
        assertSucceeded(retry);
    }


}
