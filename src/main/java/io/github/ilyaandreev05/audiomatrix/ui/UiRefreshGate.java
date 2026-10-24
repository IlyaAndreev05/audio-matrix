package io.github.ilyaandreev05.audiomatrix.ui;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Coalesces JavaFX renders and suppresses them while the window cannot be drawn.
 *
 * <p>A hidden or iconified window records that work was deferred instead of walking the page
 * builders. Showing the window resumes the meter callbacks and schedules exactly one render of
 * the latest state; repeated requests collapse into the same pending callback. Visibility
 * changes and loopback from the JavaFX queue are ignored once the shell is closed.</p>
 */
final class UiRefreshGate {
    private final Executor fx;
    private final Runnable render;
    private final Runnable pauseMeters;
    private final Runnable resumeMeters;
    private final BooleanSupplier closed;
    private final AtomicBoolean visible = new AtomicBoolean(true);
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean deferred = new AtomicBoolean();

    UiRefreshGate(Executor fx, Runnable render, Runnable pauseMeters, Runnable resumeMeters,
            BooleanSupplier closed) {
        this.fx = fx;
        this.render = render;
        this.pauseMeters = pauseMeters;
        this.resumeMeters = resumeMeters;
        this.closed = closed;
    }

    /** {@code true} only while the window is drawable and the shell is still open. */
    boolean visible() { return !closed.getAsBoolean() && visible.get(); }

    /** {@code true} when work was skipped while hidden and still awaits the next show. */
    boolean deferred() { return deferred.get(); }

    /** Requests one render at most; hidden requests only mark the pending render. */
    void request() {
        if (closed.getAsBoolean()) return;
        if (!visible.get()) {
            deferred.set(true);
            return;
        }
        if (!scheduled.compareAndSet(false, true)) return;
        try {
            fx.execute(this::drain);
        } catch (RuntimeException rejected) {
            scheduled.set(false);
        }
    }

    /**
     * Applies a real stage visibility transition.
     *
     * @return {@code true} when the state changed; repeated notifications do nothing
     */
    boolean setVisible(boolean next) {
        if (closed.getAsBoolean()) return false;
        boolean previous = visible.getAndSet(next);
        if (previous == next) return false;
        if (next) {
            resumeMeters.run();
            request();
        } else {
            pauseMeters.run();
        }
        return true;
    }

    private void drain() {
        scheduled.set(false);
        if (closed.getAsBoolean()) return;
        if (!visible.get()) {
            deferred.set(true);
            return;
        }
        deferred.set(false);
        render.run();
    }
}
