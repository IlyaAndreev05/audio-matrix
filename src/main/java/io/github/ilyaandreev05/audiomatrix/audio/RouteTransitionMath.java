package io.github.ilyaandreev05.audiomatrix.audio;

/** Small linear gain ramp used only for Audio Matrix-owned PipeWire route nodes. */
public final class RouteTransitionMath {
    private static final int MAX_STEPS = 40;
    private static final int MILLIS_PER_VOLUME_COMMAND = 75;

    private RouteTransitionMath() {}

    public static int stepsFor(int transitionMillis) {
        return stepsFor(transitionMillis, 1);
    }

    /** Selects enough space between updates for the sequential PipeWire volume commands. */
    public static int stepsFor(int transitionMillis, int affectedNodes) {
        if (transitionMillis <= 0 || affectedNodes <= 0) return 0;
        long millisPerUpdate = (long) MILLIS_PER_VOLUME_COMMAND * affectedNodes;
        long intervals = Math.max(1, (transitionMillis + millisPerUpdate - 1) / millisPerUpdate);
        return (int) Math.min(MAX_STEPS, intervals + 1);
    }

    /** Absolute time for one interpolation value, with the first at start and last at the deadline. */
    public static long deadlineNanos(long startNanos, int transitionMillis, int step, int steps) {
        if (transitionMillis <= 0 || steps <= 1) return startNanos;
        int boundedStep = Math.max(1, Math.min(step, steps));
        long durationNanos = (long) transitionMillis * 1_000_000L;
        return startNanos + durationNanos * (boundedStep - 1L) / (steps - 1L);
    }

    public static double interpolate(double from, double to, int step, int steps) {
        if (steps <= 0) return to;
        double progress = Math.max(0, Math.min(1, (double) step / steps));
        return from + (to - from) * progress;
    }
}
