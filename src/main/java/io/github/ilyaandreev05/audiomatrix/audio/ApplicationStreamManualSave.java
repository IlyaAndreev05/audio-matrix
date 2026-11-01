package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;

/** Persists a released stream drag only after its runtime value, restoring saved state on failure. */
public final class ApplicationStreamManualSave {
    private ApplicationStreamManualSave() {}

    public static CompletableFuture<Void> applyAndPersist(ApplicationStreamRuleApplier applier, AudioNode stream,
            double volume, String intentId, Supplier<CompletableFuture<Void>> persist,
            Function<Throwable, CompletableFuture<Void>> restoreSavedRules) {
        return applier.setManualVolume(stream, volume, intentId)
                .thenCompose(ignored -> persist.get())
                .<CompletableFuture<Void>>handle((ignored, error) -> {
                    if (error == null) return CompletableFuture.<Void>completedFuture(null);
                    applier.clearManualVolume(stream, intentId);
                    Throwable failure = unwrap(error);
                    CompletableFuture<Void> restored;
                    try {
                        restored = restoreSavedRules.apply(failure);
                    } catch (RuntimeException restoreFailure) {
                        restored = CompletableFuture.completedFuture(null);
                    }
                    if (restored == null) restored = CompletableFuture.completedFuture(null);
                    return restored.<Void>handle((nothing, restoreFailure) -> {
                        throw new CompletionException(failure);
                    });
                }).thenCompose(restoration -> restoration);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null)
            cause = cause.getCause();
        return cause;
    }
}
