package io.github.ilyaandreev05.audiomatrix.audio;

/** Coalesces route updates and prevents an unchanged failed graph from retrying on every PipeWire event. */
public final class RouteApplyGate {
    private boolean inFlight;
    private boolean pendingForce;
    private String applyingSignature = "";
    private String lastSignature = "";
    private long applyingGeneration = -1;
    private long lastGeneration = -1;
    private String failedSignature = "";
    private long failedGeneration = -1;

    public synchronized boolean request(String signature, long connectionGeneration, boolean force) {
        String requested = signature == null ? "" : signature;
        if (inFlight) {
            if (!force && requested.equals(applyingSignature) && connectionGeneration == applyingGeneration)
                return false;
            pendingForce |= force;
            return false;
        }
        if (!force && requested.equals(lastSignature) && connectionGeneration == lastGeneration) return false;
        if (!force && requested.equals(failedSignature) && connectionGeneration == failedGeneration) return false;

        inFlight = true;
        applyingSignature = requested;
        applyingGeneration = connectionGeneration;
        return true;
    }

    /** Completes the in-flight request and returns whether a coalesced request should be reconsidered. */
    public synchronized Completion complete(boolean succeeded, String observedSignature,
                                            long connectionGeneration) {
        if (!inFlight) return new Completion(false, false);
        String observed = observedSignature == null ? "" : observedSignature;
        if (succeeded) {
            lastSignature = applyingSignature;
            lastGeneration = applyingGeneration;
            failedSignature = "";
            failedGeneration = -1;
        } else {
            // Applying a graph may have torn down the previous one before failing.
            lastSignature = "";
            lastGeneration = -1;
            failedSignature = applyingSignature;
            failedGeneration = applyingGeneration;
        }
        boolean inputsChangedDuringApply = !observed.equals(applyingSignature)
                || connectionGeneration != applyingGeneration;
        inFlight = false;
        applyingSignature = "";
        applyingGeneration = -1;
        Completion completion = new Completion(pendingForce || inputsChangedDuringApply, pendingForce);
        pendingForce = false;
        return completion;
    }

    public synchronized void reset() {
        inFlight = false;
        pendingForce = false;
        applyingSignature = "";
        lastSignature = "";
        applyingGeneration = -1;
        lastGeneration = -1;
        failedSignature = "";
        failedGeneration = -1;
    }

    public record Completion(boolean retryPending, boolean forcePending) {}
}
