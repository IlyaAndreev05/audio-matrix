package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PipeWireSweepCommandsTest {
    @Test
    void recorderAndPlayerAreTargetedAtTheExplicitLiveNodes() throws Exception {
        AudioNode source = new AudioNode(42, "usb_sensor", "USB sensor", "Audio/Source", "running", "", "", "", 2,
                List.of(48_000), Double.NaN, false, null);
        AudioNode sink = new AudioNode(58, "usb_amp", "USB amplifier", "Audio/Sink", "running", "", "", "", 2,
                List.of(48_000), Double.NaN, false, null);

        assertEquals(List.of("pw-record", "--target", "42", "--media-category", "Capture", "--rate", "48000",
                        "--channels", "2", "--format", "s16", "--container", "wav", "/tmp/sensor.wav"),
                PipeWireSweepCommands.record(source, Path.of("/tmp/sensor.wav")));
        assertEquals(List.of("pw-play", "--target", "58", "--volume", "0.10", "/tmp/sweep.wav"),
                PipeWireSweepCommands.play(sink, Path.of("/tmp/sweep.wav")));
        assertEquals(List.of("pw-play", "--target", "58", "--volume", "0.03", "/tmp/impulse.wav"),
                PipeWireSweepCommands.playManualImpulse(sink, Path.of("/tmp/impulse.wav")));
    }

    @Test
    void refusesWrongDirectionAndUnsupportedMultichannelSources() {
        AudioNode sink = new AudioNode(10, "sink", "sink", "Audio/Sink", "running", "", "", "", 2,
                List.of(), Double.NaN, false, null);
        AudioNode multichannelSource = new AudioNode(11, "source", "source", "Audio/Source", "running", "", "", "", 4,
                List.of(), Double.NaN, false, null);
        assertThrows(java.io.IOException.class, () -> PipeWireSweepCommands.record(sink, Path.of("/tmp/input.wav")));
        assertThrows(java.io.IOException.class, () -> PipeWireSweepCommands.record(multichannelSource, Path.of("/tmp/input.wav")));
        assertThrows(java.io.IOException.class, () -> PipeWireSweepCommands.play(multichannelSource, Path.of("/tmp/sweep.wav")));
        assertThrows(java.io.IOException.class, () -> PipeWireSweepCommands.playManualImpulse(multichannelSource, Path.of("/tmp/impulse.wav")));
        assertThrows(java.io.IOException.class, () -> PipeWireSweepCommands.playManualImpulse(null, Path.of("/tmp/impulse.wav")));
        assertThrows(java.io.IOException.class, () -> PipeWireSweepCommands.playManualImpulse(
                new AudioNode(12, "sink", "sink", "Audio/Sink", "running", "", "", "", 2, List.of(), Double.NaN, false, null), null));
    }
}
