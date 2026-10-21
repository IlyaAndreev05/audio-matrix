package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GnomeCommonGraphStateTest {
    @Test void apiV5PublishesUnifiedGraphHierarchyCapabilitiesAndSavedLevelsOffline() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph graph = GraphCodec.fromMap(((List<Map<String, Object>>) config.get("route_library")).getFirst());
        Map<String, Object> runtime = new LinkedHashMap<>(GraphCodec.toMap(graph));
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) runtime.get("nodes");
        Map<String, Object> gain = nodes.stream().filter(node -> "octine-vibro-gain".equals(node.get("id")))
                .findFirst().orElseThrow();
        gain.put("parameters", DefaultConfig.map("gain", .38, "muted", true));
        ((List<Map<String, Object>>) config.get("route_library")).set(0, runtime);
        PipeWireBackend.Snapshot snapshot = PipeWireBackend.Snapshot.offline("disconnected");

        Map<String, Object> state = GnomeGraphState.build(config, snapshot, null, Map.of());
        assertEquals("main", state.get("routeId"));
        assertEquals(List.of(), state.get("composites"));
        List<Map<String, Object>> published = (List<Map<String, Object>>) state.get("nodes");
        Map<String, Object> overall = published.stream().filter(node -> "octine-vibro-gain".equals(node.get("id")))
                .findFirst().orElseThrow();
        assertEquals(.38, overall.get("savedVolume"));
        assertEquals(true, overall.get("savedMuted"));
        assertEquals(false, overall.get("available"));
        assertEquals(true, ((Map<?, ?>) overall.get("capabilities")).get("volume"));
        assertEquals("offline", overall.get("runtimeState"));
        assertTrue(published.stream().anyMatch(node -> "dynamic_generator".equals(node.get("type"))));
        assertNull(overall.get("appliedVolume"));
    }

    @Test void deviceSlotsReportBindingTruthInsteadOfAssumingTheDefaultSink() {
        AudioNode sink =
                new AudioNode(12, "alsa_output.vibro", "Vibro",
                        "Audio/Sink", "running", "", "", "", 2, List.of(48000), .5, false, null);
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(sink), List.of(),
                List.of(), List.of(), Map.of(), 12, 0);
        Map<String, Object> selector = DefaultConfig.map("kind", "name_equals", "value", sink.name());
        Map<String, Object> config = DefaultConfig.map("devices", List.of(
                DefaultConfig.map("id", "vibro-slot", "selector", selector),
                DefaultConfig.map("id", "other-slot", "selector", selector)));

        assertEquals("CONFLICT", GnomeDeviceSlotState.describe(
                new DeviceSlotNode("out", "Vibro output",
                        "vibro-slot", GraphCommonVibro.CHANNELS), snapshot, config).get("state"));
        assertEquals("OFFLINE", GnomeDeviceSlotState.describe(
                new DeviceSlotNode("out", "Vibro output",
                        "missing-slot", GraphCommonVibro.CHANNELS), snapshot, config).get("state"));
        assertEquals("UNBOUND", GnomeDeviceSlotState.describe(
                new DeviceSlotNode("out", "Vibro output",
                        "", GraphCommonVibro.CHANNELS), snapshot, config).get("state"));
    }
}
