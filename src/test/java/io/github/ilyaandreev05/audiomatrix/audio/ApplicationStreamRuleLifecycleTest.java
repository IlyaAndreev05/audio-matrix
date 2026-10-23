package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationStreamRuleLifecycleTest extends ApplicationStreamRuleFixture {
    @Test
    void replacementNameInvalidatesQueuedOldIntentsAndKeepsTheIdSerialized() {
        PipeWireBackend.Snapshot original = snapshot(stream(17, "old-player", 1.0, false));
        CompletableFuture<Void> activeOld = apply(original, 0.4, true);
        CompletableFuture<Void> queuedOld = apply(original, 0.6, false);
        CompletableFuture<Void> replacement = apply(snapshot(stream(17, "new-player", 0.4, true)), 0.4, true);
        assertEquals(1, writes.size(), "a replacement must wait for the old id's active operation");
        complete(0);
        assertTrue(activeOld.isDone());
        assertTrue(queuedOld.isDone());
        assertThrows(CompletionException.class, activeOld::join);
        assertThrows(CompletionException.class, queuedOld::join);
        assertEquals(2, writes.size(), "queued old-name operations must never address the replacement");
        assertVolume(1, 0.4);
        complete(1);
        assertMute(2, true, 0.4);
        assertFalse(replacement.isDone());
        complete(2);
        assertSucceeded(replacement);
    }

    @Test
    void replacementAndRediscoveredNodesCannotReuseAnOldAppliedCache() {
        CompletableFuture<Void> first = apply(snapshot(stream(17, "old-player", 1.0, false)), 0.4, false);
        complete(0);
        assertSucceeded(first);
        CompletableFuture<Void> replacement = apply(snapshot(stream(17, "new-player", 1.0, false)), 0.4, false);
        assertVolume(1, 0.4);
        complete(1);
        assertSucceeded(replacement);

        assertSucceeded(applier.apply(snapshot(), rules(0.4, false), null));
        CompletableFuture<Void> rediscovered = apply(snapshot(stream(17, "new-player", 1.0, false)), 0.4, false);
        assertVolume(2, 0.4);
        complete(2);
        assertSucceeded(rediscovered);
    }

    @Test
    void stablePollsAvoidWritesButNewObservationsStillReconcileExternalChanges() {
        PipeWireBackend.Snapshot original = snapshot(stream(17, "player", 1.0, false));
        CompletableFuture<Void> first = apply(original, 0.4, false);
        complete(0);
        assertSucceeded(first);
        for (int poll = 0; poll < 3; poll++) assertSucceeded(apply(original, 0.4, false));
        assertSucceeded(apply(snapshotAt(Long.MAX_VALUE, stream(17, "player", 0.4, false)), 0.4, false));
        assertEquals(1, writes.size());

        CompletableFuture<Void> corrected = apply(
                snapshotAt(Long.MAX_VALUE, stream(17, "player", 0.8, false)), 0.4, false);
        assertVolume(1, 0.4);
        assertFalse(corrected.isDone());
        complete(1);
        assertSucceeded(corrected);
    }
}
