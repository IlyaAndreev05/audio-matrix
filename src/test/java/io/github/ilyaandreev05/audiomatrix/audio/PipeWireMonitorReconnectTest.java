package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireMonitorReconnectTest {
    @Test void initialStartupSnapshotDoesNotQueueAnExtraRefresh() {
        PipeWireMonitorReconnect reconnect = new PipeWireMonitorReconnect();

        assertFalse(reconnect.initialSnapshotReceived());
    }

    @Test void firstSnapshotAfterMonitorReconnectRequestsOneRefresh() {
        PipeWireMonitorReconnect reconnect = new PipeWireMonitorReconnect();
        reconnect.monitorUnavailable();

        assertTrue(reconnect.initialSnapshotReceived());
        assertFalse(reconnect.initialSnapshotReceived());
    }

    @Test void recoveryFromPipeWireUnavailableAlsoRequestsARefresh() {
        PipeWireMonitorReconnect reconnect = new PipeWireMonitorReconnect();
        reconnect.monitorUnavailable();
        reconnect.monitorUnavailable();

        assertTrue(reconnect.initialSnapshotReceived());
        assertFalse(reconnect.initialSnapshotReceived());
    }
}
