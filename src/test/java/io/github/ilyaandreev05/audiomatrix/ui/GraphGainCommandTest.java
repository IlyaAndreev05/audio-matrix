package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GraphGainCommandTest {
    @Test void absoluteGraphGainCommandsKeepTheConfiguredLevelThroughMute() {
        Map<String, Object> config = io.github.ilyaandreev05.audiomatrix.config.DefaultConfig.create();
        String route = "main";
        String node = GraphCommonVibro.VIBRO_GAIN_ID;
        GraphNodeCommand.setVolume(config, route, node, 0.35);
        GainNode level = gain(config, route, node);
        assertEquals(0.35, level.gain(), 0.0001);
        assertFalse(level.muted());
        GraphNodeCommand.setMuted(config, route, node, true);
        GainNode muted = gain(config, route, node);
        assertEquals(0.35, muted.gain(), 0.0001);
        assertTrue(muted.muted());
        GraphNodeCommand.setVolume(config, route, node, 0.42);
        assertEquals(0.42, gain(config, route, node).gain(), 0.0001);
        assertTrue(gain(config, route, node).muted(), "volume setter must not alter the mute property");
        GraphNodeCommand.setMuted(config, route, node, false);
        GainNode restored = gain(config, route, node);
        assertEquals(0.42, restored.gain(), 0.0001);
        assertFalse(restored.muted());
        assertEquals(List.of(), io.github.ilyaandreev05.audiomatrix.config.ConfigValidator.validate(config));
    }

    @Test void graphGainVolumeDoesNotChangeTheSeparateMuteProperty() {
        Map<String, Object> config = io.github.ilyaandreev05.audiomatrix.config.DefaultConfig.create();
        String target = "main\t" + GraphCommonVibro.VIBRO_GAIN_ID;
        GraphGainCommand.set(config, target + "\t0.35", false);
        GraphGainCommand.set(config, target, true);
        GraphGainCommand.set(config, target + "\t0.42", false);

        GainNode saved = gain(config, "main", GraphCommonVibro.VIBRO_GAIN_ID);
        assertEquals(0.42, saved.gain(), 0.0001);
        assertTrue(saved.muted(), "setting volume must not implicitly unmute the graph Gain");
    }

    @Test void rejectsInvalidLevelsAndUnknownNodesWithoutChangingTheGraph() {
        Map<String, Object> config = io.github.ilyaandreev05.audiomatrix.config.DefaultConfig.create();
        assertThrows(IllegalArgumentException.class,
                () -> GraphNodeCommand.setVolume(config, "main", GraphCommonVibro.VIBRO_GAIN_ID, 1.1));
        assertThrows(IllegalArgumentException.class,
                () -> GraphNodeCommand.setVolume(config, "main", "missing", 0.5));
        assertEquals(0.6, gain(config, "main", GraphCommonVibro.VIBRO_GAIN_ID).gain(), 0.0001);
    }


    @Test void deviceCommandUpdatesOnlyAnExistingDeviceBinding() {
        Map<String, Object> config = io.github.ilyaandreev05.audiomatrix.config.DefaultConfig.create();
        Map<String, Object> route = routes(config).stream().filter(item -> "main".equals(item.get("id")))
                .findFirst().orElseThrow();
        Map<String, Object> slot = ((List<Map<String, Object>>) route.get("nodes")).stream()
                .filter(item -> GraphCommonVibro.VIBRO_OUTPUT_ID.equals(item.get("id"))).findFirst().orElseThrow();
        Map<String, Object> parameters = (Map<String, Object>) slot.get("parameters");
        parameters.put("device_id", "device-a");
        parameters.put("volume", 0.25);
        List<Map<String, Object>> devices = new ArrayList<>();
        devices.add(io.github.ilyaandreev05.audiomatrix.config.DefaultConfig.map(
                "id", "device-a", "kind", "device_slot", "role", "output", "volume", 0.8, "mute", false));
        config.put("devices", devices);

        GraphNodeCommand.setDeviceBindingLevel(config, "main", GraphCommonVibro.VIBRO_OUTPUT_ID, 0.4, null);

        assertEquals(0.4, ((Number) devices.getFirst().get("volume")).doubleValue(), 0.0001);
        assertEquals(0.25, ((Number) parameters.get("volume")).doubleValue(), 0.0001);
        assertFalse((Boolean) devices.getFirst().get("mute"));
        parameters.put("device_id", "missing-binding");
        assertThrows(IllegalArgumentException.class, () ->
                GraphNodeCommand.setDeviceBindingLevel(config, "main", GraphCommonVibro.VIBRO_OUTPUT_ID, 0.5, true));
    }

    @Test void routeSwitchCommandsRequireAnExactConnectedInputOutputLink() {
        Map<String, Object> config = io.github.ilyaandreev05.audiomatrix.config.DefaultConfig.create();
        String switchId = io.github.ilyaandreev05.audiomatrix.config.UnifiedGraphConfig.SWITCH_ID;

        GraphNodeCommand.setRouteEnabled(config, "main", switchId, "in->haptic", true);
        assertEquals(List.of("in->haptic"), switchNode(config).activeLinks());

        assertThrows(IllegalArgumentException.class, () -> GraphNodeCommand.setRouteEnabled(config, "main",
                switchId, "haptic->in", true));
        assertThrows(IllegalArgumentException.class, () -> GraphNodeCommand.setRouteEnabled(config, "main",
                switchId, "in->missing", true));
        assertThrows(IllegalArgumentException.class, () -> GraphNodeCommand.setRouteEnabled(config, "main",
                switchId, "in->monitor", true));
        assertEquals(List.of("in->haptic"), switchNode(config).activeLinks());
    }

    private static RouterNode switchNode(Map<String, Object> config) {
        return (RouterNode) GraphCodec.fromMap(routes(config).stream()
                .filter(item -> "main".equals(item.get("id"))).findFirst().orElseThrow()).nodes().stream()
                .filter(node -> node.id().equals(
                        io.github.ilyaandreev05.audiomatrix.config.UnifiedGraphConfig.SWITCH_ID))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> routes(Map<String, Object> config) {
        return (List<Map<String, Object>>) config.get("route_library");
    }

    private static GainNode gain(Map<String, Object> config, String routeId, String nodeId) {
        @SuppressWarnings("unchecked") Map<String, Object> route = ((List<Map<String, Object>>) config.get("route_library"))
                .stream().filter(item -> routeId.equals(item.get("id"))).findFirst().orElseThrow();
        return GraphCodec.fromMap(route).nodes().stream()
                .filter(node -> nodeId.equals(node.id()) && node instanceof GainNode)
                .map(GainNode.class::cast).findFirst().orElseThrow();
    }

}
