package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Runs at most one snapshot refresh at a time, immediate for level edits and debounced for topology churn. */
final class PipeWireRefreshCoalescer implements AutoCloseable {
    static final long LEVEL_DELAY_MS = 5;
    static final long TOPOLOGY_DELAY_MS = 250;

    private final ScheduledExecutorService scheduler;
    private final Supplier<CompletableFuture<PipeWireBackend.Snapshot>> refresh;
    private final Consumer<PipeWireBackend.Snapshot> listener;
    private long pendingDelay = Long.MAX_VALUE;
    private long scheduledDelay = Long.MAX_VALUE;
    private ScheduledFuture<?> scheduled;
    private boolean inFlight;
    private boolean closed;

    PipeWireRefreshCoalescer(ScheduledExecutorService scheduler,
                             Supplier<CompletableFuture<PipeWireBackend.Snapshot>> refresh,
                             Consumer<PipeWireBackend.Snapshot> listener) {
        this.scheduler = scheduler;
        this.refresh = refresh;
        this.listener = listener;
    }

    void request(boolean levelOnly) {
        request(levelOnly ? LEVEL_DELAY_MS : TOPOLOGY_DELAY_MS);
    }

    private synchronized void request(long delayMs) {
        if (closed) return;
        pendingDelay = Math.min(pendingDelay, delayMs);
        if (inFlight) return;
        if (scheduled != null) {
            if (pendingDelay >= scheduledDelay) return;
            if (!scheduled.cancel(false)) return;
        }
        schedule(pendingDelay);
    }

    private void schedule(long delayMs) {
        scheduledDelay = delayMs;
        scheduled = scheduler.schedule(this::run, delayMs, TimeUnit.MILLISECONDS);
    }

    private void run() {
        synchronized (this) {
            scheduled = null;
            scheduledDelay = Long.MAX_VALUE;
            if (closed) return;
            inFlight = true;
            pendingDelay = Long.MAX_VALUE;
        }
        CompletableFuture<PipeWireBackend.Snapshot> future;
        try {
            future = refresh.get();
        } catch (RuntimeException failure) {
            complete(null);
            return;
        }
        if (future == null) {
            complete(null);
            return;
        }
        future.whenComplete((snapshot, error) -> complete(snapshot));
    }

    private void complete(PipeWireBackend.Snapshot snapshot) {
        try {
            if (snapshot != null) listener.accept(snapshot);
        } catch (RuntimeException ignored) {
            // A failing listener must not stop future refresh scheduling.
        }
        synchronized (this) {
            inFlight = false;
            if (closed || pendingDelay == Long.MAX_VALUE || scheduled != null) return;
            schedule(pendingDelay);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
        scheduledDelay = Long.MAX_VALUE;
    }
}
