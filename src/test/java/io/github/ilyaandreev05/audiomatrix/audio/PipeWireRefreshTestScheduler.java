package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** A single-threaded virtual clock; refresh tests never depend on sleeps or host load. */
final class PipeWireRefreshTestScheduler extends AbstractExecutorService implements ScheduledExecutorService {
    private final PriorityQueue<Task<?>> tasks = new PriorityQueue<>();
    private long nowMillis;
    private long sequence;
    private boolean shutdown;

    void advanceBy(long millis) {
        if (millis < 0) throw new IllegalArgumentException("Cannot move time backwards");
        long target = nowMillis + millis;
        while (!tasks.isEmpty() && tasks.peek().deadline <= target) {
            Task<?> task = tasks.remove();
            nowMillis = task.deadline;
            if (!task.isCancelled()) task.run();
        }
        nowMillis = target;
    }

    long pendingCount() {
        return tasks.stream().filter(task -> !task.isCancelled()).count();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return schedule(() -> { command.run(); return null; }, delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        if (shutdown) throw new RejectedExecutionException("Scheduler is shut down");
        Task<V> task = new Task<>(callable, nowMillis + Math.max(0, unit.toMillis(delay)), sequence++);
        tasks.add(task);
        return task;
    }

    @Override
    public void execute(Runnable command) { schedule(command, 0, TimeUnit.MILLISECONDS); }
    @Override
    public void shutdown() { shutdown = true; }
    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> pending = new ArrayList<>(tasks);
        tasks.forEach(task -> task.cancel(false));
        tasks.clear();
        return pending;
    }
    @Override
    public boolean isShutdown() { return shutdown; }
    @Override
    public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        throw new UnsupportedOperationException("Periodic scheduling is not used by refreshes");
    }
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException("Periodic scheduling is not used by refreshes");
    }

    private final class Task<V> extends FutureTask<V> implements ScheduledFuture<V> {
        private final long deadline;
        private final long order;

        private Task(Callable<V> callable, long deadline, long order) {
            super(callable);
            this.deadline = deadline;
            this.order = order;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(deadline - nowMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other == this) return 0;
            if (other instanceof PipeWireRefreshTestScheduler.Task<?> task) {
                int byDeadline = Long.compare(deadline, task.deadline);
                return byDeadline != 0 ? byDeadline : Long.compare(order, task.order);
            }
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }
    }
}
