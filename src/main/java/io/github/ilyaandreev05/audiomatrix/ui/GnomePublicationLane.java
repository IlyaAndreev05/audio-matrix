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
    private final List<CompletableFuture<Void>> barrierQueue = new ArrayList<>();
    private boolean scheduled;

    GnomePublicationLane(Runnable publication, BooleanSupplier closing) {
        this.publication = publication;
        this.closing = closing;
    }

    CompletableFuture<Void> request(CompletableFuture<Void> barrier) {
        CompletableFuture<Void> pending = barrier == null ? new CompletableFuture<>() : barrier;
        if (closing.getAsBoolean()) {
            pending.complete(null);
            return pending;
        }
        synchronized (lock) {
            barrierQueue.add(pending);
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
        List<CompletableFuture<Void>> barriers;
        synchronized (lock) {
            barriers = new ArrayList<>(barrierQueue);
            barrierQueue.clear();
            scheduled = false;
        }
        RuntimeException failure = null;
        try {
            if (!closing.getAsBoolean()) publication.run();
        } catch (RuntimeException error) {
            failure = error;
        }
        for (CompletableFuture<Void> barrier : barriers) {
            if (failure == null) barrier.complete(null);
            else barrier.completeExceptionally(failure);
        }
    }

    void completePending() {
        List<CompletableFuture<Void>> pending;
        synchronized (lock) {
            pending = new ArrayList<>(barrierQueue);
            barrierQueue.clear();
            scheduled = false;
        }
        for (CompletableFuture<Void> barrier : pending) barrier.complete(null);
    }

    @Override
    public void close() {
        completePending();
        lane.shutdownNow();
    }
}
