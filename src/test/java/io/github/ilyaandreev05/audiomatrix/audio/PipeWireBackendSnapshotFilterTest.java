package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireBackendSnapshotFilterTest {
    @Test
    void includesPhysicalAudioNodesAndTheSystemMixerSink() {
        assertTrue(PipeWireBackend.isSnapshotAudioNode("Audio/Sink", "alsa_output.pci-0000_00_1f.3.analog-stereo"));
        assertTrue(PipeWireBackend.isSnapshotAudioNode("Audio/Source", "alsa_input.pci-0000_00_1f.3.analog-stereo"));
        assertTrue(PipeWireBackend.isSnapshotAudioNode("Stream/Output/Audio", "Firefox"));
        assertTrue(PipeWireBackend.isSnapshotAudioNode("Audio/Sink", PipeWireBackend.SYSTEM_MIXER_SINK_NAME));
    }

    @Test
    void excludesInternalEventSinksAndNonAudioNodes() {
        assertFalse(PipeWireBackend.isSnapshotAudioNode("Audio/Sink", "audiomatrix_events"));
        assertFalse(PipeWireBackend.isSnapshotAudioNode("Stream/Output/Audio", "audiomatrix_events_discard"));
        assertFalse(PipeWireBackend.isSnapshotAudioNode("Video/Source", "camera"));
        assertFalse(PipeWireBackend.isSnapshotAudioNode("Audio/Sink", ""));
        assertFalse(PipeWireBackend.isSnapshotAudioNode(null, "speaker"));
    }
}
