package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigGraphDeviceAssignmentTest {

    @Test void inactiveRouteMayPreBindTheVibroPhysicalOutput() {
        Map<String, Object> config = DefaultConfig.create();
        GraphCommonVibro.save(config, replaceSlot(GraphCommonVibro.graph(config),
                GraphCommonVibro.VIBRO_OUTPUT_ID, "front-aux"));
        GraphRouteLibrary.saveRoute(config,
                replaceSlot(route(config, "octine-headphones"), "octine-headphones-output", "front-aux"),
                "", "headphones");

        assertEquals(List.of(), ConfigValidator.validate(config));
    }

    @Test void ordinaryRouteCannotReuseTheVibroPhysicalOutput() {
        Map<String, Object> config = DefaultConfig.create();
        GraphCommonVibro.save(config, replaceSlot(GraphCommonVibro.graph(config),
                GraphCommonVibro.VIBRO_OUTPUT_ID, "front-aux"));
        GraphRouteLibrary.saveRoute(config,
                replaceSlot(route(config, "octine-monitor"), "octine-monitor-output", "front-aux"), "", "monitor");

        List<String> errors = ConfigValidator.validate(config);

        assertTrue(errors.stream().anyMatch(error -> error.contains("reuses the Vibro physical output")), errors.toString());
    }

    @Test void oneGraphCannotAssignTheSamePhysicalOutputToTwoSlots() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph free = route(config, "octine-free");
        free = replaceSlot(free, "octine-free-monitor", "shared-output");
        free = replaceSlot(free, "octine-free-headphones", "shared-output");
        GraphRouteLibrary.saveRoute(config, free, "", "free");

        List<String> errors = ConfigValidator.validate(config);

        assertTrue(errors.stream().anyMatch(error -> error.contains("duplicates physical output")), errors.toString());
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
