package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GnomeCommonGraphStateTest {
    @Test void sharedPinnedControlsReadPersistedStateAcrossRoutesAndHardwareDisconnects() {
        Map<String, Object> config = DefaultConfig.create();
        String routeId = GraphCommonVibro.COMMON_ROUTE_ID;
        AudioGraph defaults = GraphCommonVibro.defaults();
        List<GraphNode> nodes = new ArrayList<>(defaults.nodes());
        GraphNode leveled = nodes.stream().filter(node -> GraphCommonVibro.VIBRO_GAIN_ID.equals(node.id()))
                .findFirst().orElseThrow();
        nodes.set(nodes.indexOf(leveled), new GainNode(GraphCommonVibro.VIBRO_GAIN_ID, leveled.name(),
                GraphCommonVibro.CHANNELS, .38, true));
        GraphCommonVibro.save(config, new AudioGraph(defaults.id(), defaults.name(), nodes, defaults.connections()));
        QuickSettingsConfig.pins(config).clear();
        QuickSettingsConfig.pinNode(config, routeId, GraphCommonVibro.MAIN_SOURCE_ID, "sources", 0, "EXPANDED");
        QuickSettingsConfig.pinNode(config, routeId, GraphCommonVibro.VIBRO_GAIN_ID, "routing", 1, "EXPANDED");
        QuickSettingsConfig.pinNode(config, routeId, GraphCommonVibro.VIBRO_OUTPUT_ID, "outputs", 2, "EXPANDED");
        config.put("quick_controls", List.of(DefaultConfig.map("id", "shared-volume", "route_id", routeId,
                "node_id", GraphCommonVibro.VIBRO_GAIN_ID, "parameter_id", "gain")));
        PipeWireBackend.Snapshot snapshot = PipeWireBackend.Snapshot.offline("disconnected");

        for (String active : List.of("octine-monitor", "octine-headphones", "octine-free", "")) {
            config.put("active_route", active);
            List<Map<String, Object>> controls = GnomeControlBridge.quickControls(config);
            assertEquals(1, controls.size());
            assertEquals(.38, controls.getFirst().get("value"));
            assertEquals(true, controls.getFirst().get("muted"));
            assertEquals(true, controls.getFirst().get("pinned"));
            assertEquals(routeId, controls.getFirst().get("routeId"));
            List<Map<String, Object>> pins = GnomeQuickSettingsPins.build(config, snapshot, controls, List.of(), List.of());
            assertEquals(3, pins.size());
            assertEquals(true, pins.getFirst().get("pinned"));
            assertEquals(false, pins.getFirst().get("tabAdapterAvailable"));
            Map<?, ?> gain = (Map<?, ?>) pins.get(1).get("gain");
            assertEquals(routeId, gain.get("routeId"));
            assertEquals(GraphCommonVibro.VIBRO_GAIN_ID, gain.get("nodeId"));
            assertEquals(.38, gain.get("volume"));
            assertEquals(true, gain.get("muted"));
            Map<?, ?> output = (Map<?, ?>) pins.getLast().get("output");
            assertEquals("UNBOUND", output.get("state"));
            assertEquals(false, output.get("available"));
        }
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
