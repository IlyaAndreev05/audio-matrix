package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphPipeWireCompiler;
import io.github.ilyaandreev05.audiomatrix.config.UnifiedGraphConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigGraphDeviceAssignmentTest {

    @Test void newGraphStartsWithMusicOnlyOnMonitorAndVibroUnassigned() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph graph = route(config, UnifiedGraphConfig.ROUTE_ID);
        var musicSwitch = graph.nodes().stream().filter(io.github.ilyaandreev05.audiomatrix.graph.RouterNode.class::isInstance)
                .map(io.github.ilyaandreev05.audiomatrix.graph.RouterNode.class::cast).findFirst().orElseThrow();
        var vibro = graph.nodes().stream().filter(node -> GraphCommonVibro.VIBRO_OUTPUT_ID.equals(node.id()))
                .map(DeviceSlotNode.class::cast).findFirst().orElseThrow();
        assertEquals(java.util.List.of("in->monitor"), musicSwitch.activeLinks());
        assertEquals("", vibro.deviceId());
        assertEquals(java.util.List.of(), ConfigValidator.validate(config));
    }

    @Test void oneGraphCannotAssignMonitorAndVibroToTheSamePhysicalOutput() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph graph = route(config, UnifiedGraphConfig.ROUTE_ID);
        graph = replaceSlot(graph, UnifiedGraphConfig.MONITOR_OUTPUT_ID, "shared-output");
        graph = replaceSlot(graph, GraphCommonVibro.VIBRO_OUTPUT_ID, "shared-output");
        AudioGraph duplicated = graph;
        assertThrows(IllegalArgumentException.class, () -> GraphPipeWireCompiler.compile(duplicated, config));
    }

    @Test void oneGraphCannotAssignTheSamePhysicalOutputToTwoSlots() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph graph = route(config, UnifiedGraphConfig.ROUTE_ID);
        graph = replaceSlot(graph, UnifiedGraphConfig.MONITOR_OUTPUT_ID, "monitor-output");
        graph = replaceSlot(graph, GraphCommonVibro.VIBRO_OUTPUT_ID, "vibro-output");
        assertEquals("main", GraphPipeWireCompiler.compile(graph, config).get("active_route"));
    }

    @SuppressWarnings("unchecked")
    private static AudioGraph route(Map<String, Object> config, String routeId) {
        List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("route_library");
        return GraphCodec.fromMap(routes.stream()
                .filter(item -> routeId.equals(item.get("id"))).findFirst().orElseThrow());
    }

    private static AudioGraph replaceSlot(AudioGraph graph, String slotId, String deviceId) {
        List<GraphNode> nodes = graph.nodes().stream().map(node ->
                node instanceof DeviceSlotNode slot && slotId.equals(slot.id())
                        ? new DeviceSlotNode(slot.id(), slot.name(), deviceId, slot.inputs().getFirst().channels())
                        : node).toList();
        return new AudioGraph(graph.id(), graph.name(), nodes, graph.connections());
    }
}
