package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationStreamManualLifecycleTest extends ApplicationStreamRuleFixture {
    @Test
    void registeringManualIntentPreventsAnOlderUnmuteFromWritingItsStaleVolume() {
        AudioNode muted = stream(17, "player", 0.0, true);
        CompletableFuture<Void> oldRule = apply(snapshot(muted), 0.8, false);
        assertMute(0, false, 0.8);
        CompletableFuture<Void> manual = applier.setManualVolume(muted, 0.35, "drag");
        assertEquals(1, writes.size(), "manual writes share the old reconcile's runtime tail");
        assertFalse(oldRule.isDone());
        assertFalse(manual.isDone());

        complete(0);
        assertSucceeded(oldRule);
        assertEquals(2, writes.size(), "the old unmute must not continue with a stale 0.8 volume write");
        assertMute(1, false, 0.35);
        assertFalse(manual.isDone());
        complete(1);
        assertVolume(2, 0.35);
        assertFalse(manual.isDone());
        complete(2);
        assertSucceeded(manual);
        assertEquals(3, writes.size());
    }

    @Test
    void replacingTheNodeNameDoesNotTransferAnAppliedManualOverride() {
        AudioNode original = stream(17, "old-player", 0.8, false);
        CompletableFuture<Void> manual = applier.setManualVolume(original, 0.35, "drag");
        complete(0);
        complete(1);
        assertSucceeded(manual);

        AudioNode replacement = stream(17, "new-player", 0.8, false);
        assertSucceeded(apply(snapshot(replacement), 0.8, false));
        assertEquals(2, writes.size(), "the replacement already matches its saved rule, not the old override");
        CompletableFuture<Void> changed = apply(snapshot(replacement), 0.6, false);
        assertVolume(2, 0.6);
        complete(2);
        assertSucceeded(changed);
    }

    @Test
    void disappearanceDropsTheOverrideEvenWhenTheIdAndNameAreRediscovered() {
        AudioNode original = stream(17, "player", 0.8, false);
        CompletableFuture<Void> manual = applier.setManualVolume(original, 0.35, "drag");
        complete(0);
        complete(1);
        assertSucceeded(manual);

        assertSucceeded(apply(snapshot(), 0.8, false));
        assertSucceeded(apply(snapshot(original), 0.8, false));
        assertEquals(2, writes.size(), "a rediscovered stream must not inherit an old manual override");
    }

    @Test
    void disconnectDropsTheOverrideBeforeTheSameIdAndNameReconnect() {
        AudioNode original = stream(17, "player", 0.8, false);
        CompletableFuture<Void> manual = applier.setManualVolume(original, 0.35, "drag");
        complete(0);
        complete(1);
        assertSucceeded(manual);

        assertSucceeded(applier.apply(PipeWireBackend.Snapshot.offline("disconnected"), rules(0.8, false), null));
        assertSucceeded(apply(snapshot(original), 0.8, false));
        assertEquals(2, writes.size(), "reconnected ids cannot inherit a disconnected generation's override");
    }

    @Test
    void disappearanceInvalidatesActiveAndQueuedManualIntentsBeforeIdReuse() {
        AudioNode original = stream(17, "player", 0.8, false);
        CompletableFuture<Void> active = applier.setManualVolume(original, 0.35, "drag-a");
        CompletableFuture<Void> queued = applier.setManualVolume(original, 0.45, "drag-b");
        assertMute(0, false, 0.35);
        assertSucceeded(apply(snapshot(), 0.8, false));
        CompletableFuture<Void> replacement = apply(snapshot(original), 0.8, false);
        assertEquals(1, writes.size(), "id reuse must wait for the previous generation's active backend write");
        assertFalse(replacement.isDone());

        complete(0);
        assertTrue(active.isDone());
        assertTrue(queued.isDone());
        assertThrows(CompletionException.class, active::join);
        assertThrows(CompletionException.class, queued::join);
        assertMute(1, false, 0.8);
        assertEquals(2, writes.size(), "neither old intent may write its volume to the reused id");
        complete(1);
        assertVolume(2, 0.8);
        assertFalse(replacement.isDone());
        complete(2);
        assertSucceeded(replacement);
        assertEquals(3, writes.size());
    }
}
