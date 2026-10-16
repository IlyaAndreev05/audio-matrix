package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PipeWireLevelMonitorTest {
    @Test
    void outputCaptureTargetsTheSinkMonitorInsteadOfTheDefaultInput() {
        assertEquals(List.of("pw-cat", "--record", "--target", "alsa_output.pci-0000_00_1f.3.analog-stereo",
                        "--properties", "{\"stream.capture.sink\":true}",
                        "--raw", "--format", "f32", "--rate", "48000",
                        "--channels", "2", "--channel-map", "FL,FR", "-"),
                PipeWireLevelMonitor.captureCommand("alsa_output.pci-0000_00_1f.3.analog-stereo", 2, true));
        assertFalse(PipeWireLevelMonitor.captureCommand("alsa_input.pci-0000_00_1f.3.analog-stereo", 2, false)
                .contains("{\"stream.capture.sink\":true}"));
    }
}
