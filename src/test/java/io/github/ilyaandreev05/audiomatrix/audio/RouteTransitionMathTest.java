package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteTransitionMathTest {
    @Test void routeGainRampHasExactEndpointsAndMonotonicSteps() {
        int steps = RouteTransitionMath.stepsFor(120);
        double previous = RouteTransitionMath.interpolate(0, 0.8, 0, steps);
        assertEquals(0.0, previous);
        for (int step = 1; step <= steps; step++) {
            double current = RouteTransitionMath.interpolate(0, 0.8, step, steps);
            assertTrue(current >= previous);
            previous = current;
        }
        assertEquals(0.8, previous, 1e-12);
    }

    @Test void zeroDurationSkipsStepsAndReturnsTarget() {
        assertEquals(0, RouteTransitionMath.stepsFor(0));
        assertEquals(0, RouteTransitionMath.stepsFor(-1));
        assertEquals(1.0, RouteTransitionMath.interpolate(0.2, 1.0, 0, 0));
    }

    @Test void transitionStepCountIsBounded() {
        assertEquals(15, RouteTransitionMath.stepsFor(1000));
        assertEquals(40, RouteTransitionMath.stepsFor(10_000));
    }

    @Test void stepCountAccountsForSequentialNodeUpdates() {
        assertEquals(9, RouteTransitionMath.stepsFor(600, 1));
        assertEquals(5, RouteTransitionMath.stepsFor(600, 2));
        assertEquals(3, RouteTransitionMath.stepsFor(600, 4));
        assertEquals(2, RouteTransitionMath.stepsFor(600, 8));
        assertEquals(0, RouteTransitionMath.stepsFor(600, 0));
    }

    @Test void deadlinesCoverConfiguredDurationWithoutAccumulatingCommandTime() {
        long start = 1_000_000_000L;
        int steps = RouteTransitionMath.stepsFor(600, 1);
        long previous = RouteTransitionMath.deadlineNanos(start, 600, 1, steps);
        assertEquals(start, previous);
        for (int step = 2; step <= steps; step++) {
            long current = RouteTransitionMath.deadlineNanos(start, 600, step, steps);
            assertTrue(current > previous);
            previous = current;
        }
        assertEquals(start + 600_000_000L, previous);
    }
}
