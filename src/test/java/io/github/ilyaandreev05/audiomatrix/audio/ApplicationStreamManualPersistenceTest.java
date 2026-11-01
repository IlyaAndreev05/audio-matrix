package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationStreamManualPersistenceTest extends ApplicationStreamRuleFixture {
    @Test void failedPersistenceClearsOnlyTheManualIntentAndWaitsForSavedRuleRestoration() {
        AudioNode original = stream(17, "player", 0.8, false);
        CompletableFuture<Void> persistence = new CompletableFuture<>();
        AtomicReference<CompletableFuture<Void>> restoration = new AtomicReference<>();
        IllegalStateException failure = new IllegalStateException("Configuration write failed");

        CompletableFuture<Void> operation = ApplicationStreamManualSave.applyAndPersist(applier, original,
                0.35, "drag", () -> persistence, reported -> {
                    assertSame(failure, reported);
                    CompletableFuture<Void> restored = apply(snapshot(original), 0.8, false);
                    restoration.set(restored);
                    return restored;
                });
        assertMute(0, false, 0.35);
        complete(0);
        assertVolume(1, 0.35);
        complete(1);
        assertFalse(operation.isDone(), "persistence must be awaited after the manual runtime write");

        persistence.completeExceptionally(failure);
        assertNotNull(restoration.get(), "a failed save must request the prior configured level");
        assertFalse(operation.isDone(), "the failure is reported only after saved controls are restored");
        assertMute(2, false, 0.8);
        complete(2);
        assertVolume(3, 0.8);
        complete(3);
        assertSame(failure, assertThrows(CompletionException.class, operation::join).getCause());
        assertSucceeded(apply(snapshot(original), 0.8, false));
        assertEquals(4, writes.size(), "restored saved controls are cached only after success");
    }
}
