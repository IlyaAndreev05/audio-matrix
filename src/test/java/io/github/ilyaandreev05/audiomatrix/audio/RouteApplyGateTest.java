package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteApplyGateTest {
    @Test
    void coalescesPipeWireChangesWhileAnApplyIsInFlight() {
        RouteApplyGate gate = new RouteApplyGate();

        assertTrue(gate.request("initial", 1, false));
        assertFalse(gate.request("initial", 1, false));
        assertFalse(gate.request("transient-a", 1, false));
        assertFalse(gate.request("transient-b", 1, false));

        RouteApplyGate.Completion completion = gate.complete(true, "settled", 1);
        assertTrue(completion.retryPending());
        assertFalse(completion.forcePending());
        assertTrue(gate.request("settled", 1, false));
    }

    @Test
    void suppressesAnUnchangedFailureUntilInputsOrConnectionChange() {
        RouteApplyGate gate = new RouteApplyGate();

        assertTrue(gate.request("bad-graph", 4, false));
        RouteApplyGate.Completion completion = gate.complete(false, "bad-graph", 4);
        assertFalse(completion.retryPending());
        assertFalse(gate.request("bad-graph", 4, false));
        assertTrue(gate.request("changed-input", 4, false));
    }

    @Test
    void doesNotRetryARepeatedFailureButRetriesIfInputsChangedDuringApply() {
        RouteApplyGate gate = new RouteApplyGate();

        assertTrue(gate.request("broken-input", 8, false));
        RouteApplyGate.Completion changed = gate.complete(false, "new-input", 8);
        assertTrue(changed.retryPending());
        assertTrue(gate.request("new-input", 8, false));
        gate.complete(false, "new-input", 8);
        assertFalse(gate.request("new-input", 8, false));
    }

    @Test
    void retriesLastSuccessfulInputsIfAChangedGraphFailedAndWasTornDown() {
        RouteApplyGate gate = new RouteApplyGate();

        assertTrue(gate.request("working-input", 3, false));
        gate.complete(true, "working-input", 3);
        assertTrue(gate.request("changed-input", 3, false));
        gate.complete(false, "changed-input", 3);

        assertFalse(gate.request("changed-input", 3, false));
        assertTrue(gate.request("working-input", 3, false));
    }

    @Test
    void retriesAfterConnectionGenerationChangesDuringApply() {
        RouteApplyGate gate = new RouteApplyGate();

        assertTrue(gate.request("same-input", 2, false));
        RouteApplyGate.Completion completion = gate.complete(true, "same-input", 3);
        assertTrue(completion.retryPending());
        assertTrue(gate.request("same-input", 3, false));
    }

    @Test
    void allowsManualRetryAndCarriesItAcrossAnInFlightApply() {
        RouteApplyGate gate = new RouteApplyGate();

        assertTrue(gate.request("first", 1, false));
        assertFalse(gate.request("first", 1, true));
        RouteApplyGate.Completion completion = gate.complete(true, "first", 1);
        assertTrue(completion.retryPending());
        assertTrue(completion.forcePending());
        assertTrue(gate.request("first", 1, true));
    }
}
