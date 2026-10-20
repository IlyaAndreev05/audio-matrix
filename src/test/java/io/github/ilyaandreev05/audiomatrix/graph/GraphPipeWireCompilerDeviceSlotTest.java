package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.UnifiedGraphConfig;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GraphPipeWireCompilerDeviceSlotTest {
    @Test void defaultGraphUsesOneApplicationBusAndOnlyRoutesMusicToTheMonitor() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph graph = route(config);

        Map<String, Object> compiled = GraphPipeWireCompiler.compile(graph, config);
        List<Map<String, Object>> buses = maps(compiled.get("buses"));
        List<Map<String, Object>> routes = maps(compiled.get("routes"));

        assertEquals(1, buses.stream().filter(bus -> GraphCommonVibro.MAIN_SOURCE_ID.equals(bus.get("id"))).count());
        assertEquals(1, routes.size());
        assertEquals("system_default", routes.getFirst().get("to"));
        assertEquals(GraphCommonVibro.MAIN_SOURCE_ID, routes.getFirst().get("from"));
        assertEquals(List.of(), GraphValidator.validate(graph));
    }

    @Test void unboundVibroStaysSilentWithoutASecondDefaultSinkRoute() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph graph = route(config);
        List<Map<String, Object>> routes = maps(GraphPipeWireCompiler.compile(graph, config).get("routes"));

        assertEquals(1, routes.size());
        assertTrue(routes.stream().noneMatch(route -> "vibro".equals(route.get("role"))));
        assertEquals("system_default", routes.getFirst().get("to"));
    }

    @Test void enablingMusicOnVibroKeepsTheIndependentEventLevelSafe() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph graph = withVibroDeviceAndRoute(route(config), "slot-vibro", true);
        List<Map<String, Object>> routes = maps(GraphPipeWireCompiler.compile(graph, config).get("routes"));
        Map<String, Object> music = routes.stream().filter(item ->
                GraphCommonVibro.MAIN_SOURCE_ID.equals(item.get("from")) && "slot-vibro".equals(item.get("to")))
                .findFirst().orElseThrow();
        Map<String, Object> events = routes.stream().filter(item ->
                UnifiedGraphConfig.EVENT_SOURCE_ID.equals(item.get("from"))).findFirst().orElseThrow();

        assertEquals(0.36, music.get("volume"));
        assertEquals(0.39, events.get("volume"));
        assertEquals("slot-vibro", events.get("to"));
        assertFalse(events.containsKey("filters_ref"));
    }

    @Test void rejectsPhysicalOutputSharedByMonitorAndVibroSlots() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph graph = replaceSlots(route(config), Map.of(
                UnifiedGraphConfig.MONITOR_OUTPUT_ID, "shared-output",
                GraphCommonVibro.VIBRO_OUTPUT_ID, "shared-output"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GraphPipeWireCompiler.compile(graph, config));
        assertTrue(error.getMessage().contains("Vibro slot"), error.getMessage());
    }

    private static AudioGraph withVibroDeviceAndRoute(AudioGraph graph, String device, boolean enableMusic) {
        AudioGraph assigned = replaceSlots(graph, Map.of(GraphCommonVibro.VIBRO_OUTPUT_ID, device));
        List<GraphNode> nodes = new ArrayList<>(assigned.nodes());
        SwitchNode router = nodes.stream().filter(SwitchNode.class::isInstance).map(SwitchNode.class::cast)
                .findFirst().orElseThrow();
        List<String> active = enableMusic ? List.of("in->monitor", "in->vibro") : List.of("in->monitor");
        nodes.set(nodes.indexOf(router), new SwitchNode(router.id(), router.name(), router.inputs().getFirst().channels(),
                router.outputs().stream().map(AudioPort::id).toList(), active));
        return new AudioGraph(assigned.id(), assigned.name(), nodes, assigned.connections());
    }

    private static AudioGraph route(Map<String, Object> config) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes =
                (List<Map<String, Object>>) config.get("route_library");
        return GraphCodec.fromMap(routes.stream().filter(item -> "main".equals(item.get("id")))
                .findFirst().orElseThrow());
    }

    private static AudioGraph replaceSlots(AudioGraph graph, Map<String, String> assignments) {
        List<GraphNode> nodes = graph.nodes().stream().map(node -> node instanceof DeviceSlotNode slot
                && assignments.containsKey(slot.id())
                ? new DeviceSlotNode(slot.id(), slot.name(), assignments.get(slot.id()), slot.inputs().getFirst().channels())
                : node).toList();
        return new AudioGraph(graph.id(), graph.name(), nodes, graph.connections());
    }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
