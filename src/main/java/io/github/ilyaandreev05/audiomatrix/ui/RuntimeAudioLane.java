package io.github.ilyaandreev05.audiomatrix.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Helpers that keep PipeWire-derived work on the dedicated audio lane instead of a JavaFX pulse. */
final class RuntimeAudioLane {
    private RuntimeAudioLane() {
    }

    /** Runs audio work on the lane; a rejected lane simply drops the callback. */
    static void run(Executor lane, BooleanSupplier closing, Runnable work) {
        if (closing.getAsBoolean()) return;
        try {
            lane.execute(() -> {
                if (closing.getAsBoolean()) return;
                try {
                    work.run();
                } catch (RuntimeException failure) {
                    System.err.println("Audio Matrix runtime audio task failed: " + AppShellBase.rootMessage(failure));
                }
            });
        } catch (RejectedExecutionException closedLane) { /* shell is closing */ }
    }

    /** Runs one asynchronous audio operation on the lane and returns its own completion. */
    static <T> CompletableFuture<T> submit(Executor lane, BooleanSupplier closing,
                                           Supplier<CompletableFuture<T>> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closing.getAsBoolean()) {
            result.completeExceptionally(new IllegalStateException("Audio Matrix is closing"));
            return result;
        }
        try {
            lane.execute(() -> {
                if (closing.getAsBoolean()) {
                    result.completeExceptionally(new IllegalStateException("Audio Matrix is closing"));
                    return;
                }
                CompletableFuture<T> operation;
                try {
                    operation = work.get();
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                    return;
                }
                if (operation == null) {
                    result.complete(null);
                    return;
                }
                operation.whenComplete((value, error) -> {
                    if (error != null) result.completeExceptionally(error);
                    else result.complete(value);
                });
            });
        } catch (RejectedExecutionException closedLane) {
            result.completeExceptionally(closedLane);
        }
        return result;
    }
}
