package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireMeterLiveSmokeTest {
    @Test
    void readsSamplesFromMutedZeroVolumeSinkMonitorWithoutChangingDeviceSettings() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_METER_LIVE_SMOKE")),
                "Set AUDIO_MATRIX_METER_LIVE_SMOKE=1 to run the read-only meter check");
        try (PipeWireBackend backend = new PipeWireBackend(); PipeWireLevelMonitor meter = new PipeWireLevelMonitor()) {
            PipeWireBackend.Snapshot before = backend.refresh().get(10, TimeUnit.SECONDS);
            Assumptions.assumeTrue(before.connected(), "PipeWire is required for the meter smoke check");
            AudioNode silentSink = before.sinks().stream()
                    .filter(node -> node.muted() && node.volume() == 0.0)
                    .findFirst().orElse(null);
            Assumptions.assumeTrue(silentSink != null, "A muted zero-volume sink is required; no live output signal is played");

            meter.setTarget(silentSink);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!meter.reading().available() && System.nanoTime() < deadline) Thread.sleep(50);
            assertTrue(meter.reading().available(), meter.reading().message());
            assertEquals(silentSink.name(), meter.reading().nodeName());
            meter.close();
            PipeWireBackend.Snapshot after = backend.refresh().get(10, TimeUnit.SECONDS);
            AudioNode unchanged = after.sinks().stream().filter(node -> node.name().equals(silentSink.name())).findFirst().orElseThrow();
            assertEquals(silentSink.volume(), unchanged.volume(), 1e-9);
            assertEquals(silentSink.muted(), unchanged.muted());
        }
    }
}
