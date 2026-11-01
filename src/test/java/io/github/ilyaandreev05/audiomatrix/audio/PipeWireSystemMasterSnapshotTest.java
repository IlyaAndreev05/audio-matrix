package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireSystemMasterSnapshotTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void mutedSystemSinkUsesStoredLinearChannelVolumesInsteadOfEffectiveWpctlZero() throws Exception {
        AudioNode sink = sink(PipeWireBackend.SYSTEM_MIXER_SINK_NAME, 32,
                "\"volume\":1.0,\"mute\":true,\"channelVolumes\":[1.0,1.0]",
                status(32, PipeWireBackend.SYSTEM_MIXER_SINK_NAME, "0.00", true));

        assertEquals(1.0, sink.volume(), 1e-9, "channelVolumes is the stored linear target while muted");
        assertTrue(sink.muted());
    }

    @Test
    void mutedSystemSinkTracksHalfAndZeroStoredTargetsWithoutCubicConversion() throws Exception {
        AudioNode half = sink(PipeWireBackend.SYSTEM_MIXER_SINK_NAME, 32,
                "\"volume\":1.0,\"mute\":true,\"channelVolumes\":[0.5,0.5]",
                status(32, PipeWireBackend.SYSTEM_MIXER_SINK_NAME, "0.00", true));
        AudioNode zero = sink(PipeWireBackend.SYSTEM_MIXER_SINK_NAME, 32,
                "\"volume\":1.0,\"mute\":true,\"channelVolumes\":[0.0,0.0]",
                status(32, PipeWireBackend.SYSTEM_MIXER_SINK_NAME, "0.00", true));

        assertEquals(0.5, half.volume(), 1e-9, "The stored channel volume is already linear");
        assertTrue(half.muted());
        assertEquals(0.0, zero.volume(), 1e-9, "A stored zero is a valid target, not an unavailable readback");
        assertTrue(zero.muted());
    }

    @Test
    void unmutedSystemSinkUsesStoredChannelVolumesNotTheScalarVolume() throws Exception {
        AudioNode sink = sink(PipeWireBackend.SYSTEM_MIXER_SINK_NAME, 32,
                "\"volume\":1.0,\"mute\":false,\"channelVolumes\":[0.5,0.5]",
                status(32, PipeWireBackend.SYSTEM_MIXER_SINK_NAME, "0.79370", false));

        assertEquals(0.5, sink.volume(), 1e-9, "The Props.volume scalar must not mask an OSD level change");
        assertTrue(!sink.muted());
    }

    @Test
    void missingOrInvalidMutedStoredChannelVolumesDoNotGuessEffectiveZeroAsTarget() throws Exception {
        AudioNode missing = sink(PipeWireBackend.SYSTEM_MIXER_SINK_NAME, 32,
                "\"volume\":1.0,\"mute\":true",
                status(32, PipeWireBackend.SYSTEM_MIXER_SINK_NAME, "0.00", true));
        AudioNode invalid = sink(PipeWireBackend.SYSTEM_MIXER_SINK_NAME, 32,
                "\"volume\":1.0,\"mute\":true,\"channelVolumes\":[1.01,1.01]",
                status(32, PipeWireBackend.SYSTEM_MIXER_SINK_NAME, "0.00", true));

        assertTrue(Double.isNaN(missing.volume()));
        assertTrue(missing.muted());
        assertTrue(Double.isNaN(invalid.volume()));
        assertTrue(invalid.muted());
    }

    @Test
    void anyInvalidMutedChannelMakesTheStoredTargetUnavailable() throws Exception {
        AudioNode sink = sink(PipeWireBackend.SYSTEM_MIXER_SINK_NAME, 32,
                "\"volume\":1.0,\"mute\":true,\"channelVolumes\":[0.5,1.01]",
                status(32, PipeWireBackend.SYSTEM_MIXER_SINK_NAME, "0.00", true));

        assertTrue(Double.isNaN(sink.volume()));
        assertTrue(sink.muted());
    }

    @Test
    void otherSinksKeepTheirExistingEffectiveWpctlVolumeSemantics() throws Exception {
        AudioNode sink = sink("private_physical_test_output", 40,
                "\"volume\":1.0,\"mute\":true,\"channelVolumes\":[0.0,0.0]",
                status(40, "private_physical_test_output", "0.00", true));

        assertEquals(0.0, sink.volume(), 1e-9);
        assertTrue(sink.muted());
    }

    @Test
    void unmutedSystemSinkFallsBackToExistingWpctlLinearConversionWithoutChannelVolumes() throws Exception {
        AudioNode sink = sink(PipeWireBackend.SYSTEM_MIXER_SINK_NAME, 32,
                "\"volume\":1.0,\"mute\":false",
                status(32, PipeWireBackend.SYSTEM_MIXER_SINK_NAME, "0.79370", false));

        assertEquals(0.5, sink.volume(), 1e-4);
        assertTrue(!sink.muted());
    }

    private static AudioNode sink(String name, int id, String props, String status) throws IOException {
        JsonNode root = JSON.readTree("""
                [{"id":%d,"info":{"state":"running","props":{"node.name":"%s",
                  "node.description":"Test sink","media.class":"Audio/Sink","audio.channels":2},
                  "params":{"Props":[{%s}]}}}]
                """.formatted(id, name, props));
        List<AudioNode> sinks = new ArrayList<>();
        PipeWireSnapshotReader.readNodes(root, Map.of(), PipeWireSnapshotReader.levelStates(status),
                sinks, new ArrayList<>(), new ArrayList<>());
        return sinks.stream().filter(candidate -> candidate.id() == id).findFirst()
                .orElseThrow(() -> new IOException("Test sink was not parsed: " + name));
    }

    private static String status(int id, String name, String volume, boolean muted) {
        return "Audio\n ├─ Sinks:\n │ * " + id + ". " + name + " [vol: " + volume
                + (muted ? " MUTED" : "") + "]\n";
    }
}
