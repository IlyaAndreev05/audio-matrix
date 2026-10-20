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
        GraphNodeCommand.setMuted(config, route, node, false);
        GainNode restored = gain(config, route, node);
        assertEquals(0.35, restored.gain(), 0.0001);
        assertFalse(restored.muted());
        assertEquals(List.of(), io.github.ilyaandreev05.audiomatrix.config.ConfigValidator.validate(config));
    }

    @Test void rejectsInvalidLevelsAndUnknownNodesWithoutChangingTheGraph() {
        Map<String, Object> config = io.github.ilyaandreev05.audiomatrix.config.DefaultConfig.create();
        assertThrows(IllegalArgumentException.class,
                () -> GraphNodeCommand.setVolume(config, "main", GraphCommonVibro.VIBRO_GAIN_ID, 1.1));
        assertThrows(IllegalArgumentException.class,
                () -> GraphNodeCommand.setVolume(config, "main", "missing", 0.5));
        assertEquals(0.6, gain(config, "main", GraphCommonVibro.VIBRO_GAIN_ID).gain(), 0.0001);
    }

    @Test void deviceSlotLevelAndMuteAreSavedIndependently() {
        Map<String, Object> config = io.github.ilyaandreev05.audiomatrix.config.DefaultConfig.create();
        String output = GraphCommonVibro.VIBRO_OUTPUT_ID;
        GraphNodeCommand.setVolume(config, "main", output, 0.42);
        GraphNodeCommand.setMuted(config, "main", output, true);
        DeviceSlotNode slot = deviceSlot(config, "main", output);
        assertEquals(.42, slot.volume(), .0001);
        assertTrue(slot.muted());
        GraphNodeCommand.setMuted(config, "main", output, false);
        assertEquals(.42, deviceSlot(config, "main", output).volume(), .0001);
    }

    private static GainNode gain(Map<String, Object> config, String routeId, String nodeId) {
        @SuppressWarnings("unchecked") Map<String, Object> route = ((List<Map<String, Object>>) config.get("route_library"))
                .stream().filter(item -> routeId.equals(item.get("id"))).findFirst().orElseThrow();
        return GraphCodec.fromMap(route).nodes().stream()
                .filter(node -> nodeId.equals(node.id()) && node instanceof GainNode)
                .map(GainNode.class::cast).findFirst().orElseThrow();
    }

    private static DeviceSlotNode deviceSlot(Map<String, Object> config, String routeId, String nodeId) {
        @SuppressWarnings("unchecked") Map<String, Object> route = ((List<Map<String, Object>>) config.get("route_library"))
                .stream().filter(item -> routeId.equals(item.get("id"))).findFirst().orElseThrow();
        return GraphCodec.fromMap(route).nodes().stream()
                .filter(node -> nodeId.equals(node.id()) && node instanceof DeviceSlotNode)
                .map(DeviceSlotNode.class::cast).findFirst().orElseThrow();
    }
}
