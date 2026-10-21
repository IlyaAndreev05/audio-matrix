package io.github.ilyaandreev05.audiomatrix.ui;

import java.util.concurrent.CompletableFuture;
import java.util.function.LongFunction;

/** Serializes persistent commands while allowing the queue to continue after a failed command. */
final class GnomePersistentCommandQueue {
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    private long sequence;

    synchronized CompletableFuture<Void> submit(LongFunction<CompletableFuture<Void>> command) {
        long current = ++sequence;
        CompletableFuture<Void> completion = tail.handle((ignored, previousError) -> null)
                .thenCompose(ignored -> command.apply(current));
        tail = completion.handle((ignored, error) -> null);
        return completion;
    }
}
