package io.github.ilyaandreev05.audiomatrix.audio;

/** Requests a fresh backend snapshot after the monitor reconnects to PipeWire. */
final class PipeWireMonitorReconnect {
    private boolean refreshAfterNextSnapshot;

    void monitorUnavailable() {
        refreshAfterNextSnapshot = true;
    }

    boolean initialSnapshotReceived() {
        if (!refreshAfterNextSnapshot) return false;
        refreshAfterNextSnapshot = false;
        return true;
    }
}
