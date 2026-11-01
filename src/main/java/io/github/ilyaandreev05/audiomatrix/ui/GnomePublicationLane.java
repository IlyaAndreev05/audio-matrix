package io.github.ilyaandreev05.audiomatrix.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

/**
 * Coalesced ordered GNOME State publications on their own daemon lane.
 *
 * <p>Snapshot-only requests collapse into the single scheduled drain, which reads the latest
 * observation and configuration at execution time. Every confirmation barrier completes only
 * after the actual publication, never at enqueue time, and pending barriers always complete on
 * shutdown so a command can never hang on a closing shell.</p>
 */
final class GnomePublicationLane implements AutoCloseable {
    private final ExecutorService lane = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "audio-matrix-gnome-state");
        thread.setDaemon(true);
        return thread;
    });
    private final Runnable publication;
    private final BooleanSupplier closing;
    private final Object lock = new Object();
    private record Barrier(CompletableFuture<Void> future, Runnable validate) {}
    private final List<Barrier> barrierQueue = new ArrayList<>();
    private boolean scheduled;

    GnomePublicationLane(Runnable publication, BooleanSupplier closing) {
        this.publication = publication;
        this.closing = closing;
    }

    CompletableFuture<Void> request(CompletableFuture<Void> barrier) {
        return request(barrier, null);
    }

    CompletableFuture<Void> requestValidated(Runnable validate) {
        return request(new CompletableFuture<>(), java.util.Objects.requireNonNull(validate));
    }

    private CompletableFuture<Void> request(CompletableFuture<Void> barrier, Runnable validate) {
        CompletableFuture<Void> pending = barrier == null ? new CompletableFuture<>() : barrier;
        if (closing.getAsBoolean()) {
            if (validate == null) pending.complete(null);
            else pending.completeExceptionally(new IllegalStateException("GNOME publication is closing"));
            return pending;
        }
        synchronized (lock) {
            barrierQueue.add(new Barrier(pending, validate));
            if (scheduled) return pending;
            scheduled = true;
        }
        try {
            lane.execute(this::drain);
        } catch (RejectedExecutionException closedLane) {
            completePending();
        }
        return pending;
    }

    private void drain() {
        List<Barrier> barriers;
        synchronized (lock) {
            barriers = new ArrayList<>(barrierQueue);
            barrierQueue.clear();
            scheduled = false;
        }
        RuntimeException failure = null;
        try {
            if (!closing.getAsBoolean()) publication.run();
            else if (barriers.stream().anyMatch(barrier -> barrier.validate() != null))
                failure = new IllegalStateException("GNOME publication is closing");
        } catch (RuntimeException error) {
            failure = error;
        }
        for (Barrier barrier : barriers) {
            if (failure != null) {
                barrier.future().completeExceptionally(failure);
            } else {
                try {
                    if (barrier.validate() != null) barrier.validate().run();
                    barrier.future().complete(null);
                } catch (RuntimeException error) {
                    barrier.future().completeExceptionally(error);
                }
            }
        }
    }

    void completePending() {
        List<Barrier> pending;
        synchronized (lock) {
            pending = new ArrayList<>(barrierQueue);
            barrierQueue.clear();
            scheduled = false;
        }
        for (Barrier barrier : pending) {
            if (barrier.validate() == null) barrier.future().complete(null);
            else barrier.future().completeExceptionally(new IllegalStateException("GNOME publication is closing"));
        }
    }

    @Override
    public void close() {
        completePending();
        lane.shutdownNow();
    }
}
