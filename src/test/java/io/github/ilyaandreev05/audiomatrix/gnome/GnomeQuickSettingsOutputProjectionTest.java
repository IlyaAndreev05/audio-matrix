package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.config.UnifiedGraphConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GnomeQuickSettingsOutputProjectionTest {
    @Test void projectionResolvesTheBoundSinkNotAnotherDeviceWhoseIdEqualsItsName() {
        Map<String, Object> config = DefaultConfig.create();
        AudioNode live = sink(72, "alsa_output.shared", "Bound output");
        AudioNode other = sink(73, "alsa_output.other", "Other output");
        config.put("devices", List.of(
                DefaultConfig.map("id", "saved-bound", "identity", live.identity().toMap(),
                        "volume", 0.7, "mute", false),
                DefaultConfig.map("id", live.name(), "identity", other.identity().toMap(),
                        "volume", 0.9, "mute", false)));
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(live, other),
                List.of(), List.of(), List.of(), Map.of(), live.id(), 0);
        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(route(config),
                Map.of(UnifiedGraphConfig.MONITOR_OUTPUT_ID, List.of(DynamicInstanceRegistry.output(live))));
        QuickSettingsConfig.pinNode(config, "main", UnifiedGraphConfig.MONITOR_OUTPUT_ID,
                "device:" + live.identity().fingerprint(), "output", "outputs", 5, "EXPANDED");

        List<Map<String, Object>> pins = GnomeQuickSettingsPins.build(config, snapshot,
                GnomeControlBridge.quickControls(config), List.of(), List.of(), reconciled.effectiveGraph(),
                reconciled.instances());

        Map<?, ?> output = (Map<?, ?>) pins.stream()
                .filter(item -> UnifiedGraphConfig.MONITOR_OUTPUT_ID.equals(item.get("nodeId")))
                .findFirst().orElseThrow().get("output");
        assertEquals(true, output.get("available"));
        assertEquals("device:saved-bound", output.get("id"));
        assertEquals("Bound output", output.get("description"));
        assertEquals(0.7, output.get("volume"));
        Map<?, ?> childOutput = (Map<?, ?>) pins.stream()
                .filter(item -> "output".equals(item.get("childId")))
                .findFirst().orElseThrow().get("output");
        assertEquals("device:saved-bound", childOutput.get("id"));
        assertEquals("Bound output", childOutput.get("description"));
        assertEquals(0.9, ((List<Map<String, Object>>) config.get("devices")).get(1).get("volume"));
    }

    private static AudioNode sink(int id, String name, String description) {
        DeviceIdentity identity = DeviceIdentity.from(Map.of("device.bus-path", "pci-0000:01:00.0",
                "device.serial", "serial-" + id, "device.profile.name", "hdmi-stereo",
                "device.port.name", "hdmi-output-" + id, "audio.position", "FL,FR"), 2);
        return new AudioNode(id, name, description, "Audio/Sink", "running", "", "", "", 2,
                List.of(48000), 0.7, false, null, identity);
    }

    @SuppressWarnings("unchecked")
    private static AudioGraph route(Map<String, Object> config) {
        Map<String, Object> route = ((List<Map<String, Object>>) config.get("route_library")).stream()
                .filter(item -> "main".equals(item.get("id"))).findFirst().orElseThrow();
        return GraphCodec.fromMap(route);
    }
}
