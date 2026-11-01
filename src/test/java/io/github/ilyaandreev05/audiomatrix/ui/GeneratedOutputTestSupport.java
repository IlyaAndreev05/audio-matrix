package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.config.UnifiedGraphConfig;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GeneratedOutputTestSupport {
    private GeneratedOutputTestSupport() {}
    static DynamicGeneratorNode generator() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("registry", "outputs");
        parameters.put("template", "default-output");
        parameters.put("selector", "device:*");
        parameters.put("instances", new LinkedHashMap<>());
        parameters.put("connect_to", Map.of("node_id", UnifiedGraphConfig.SWITCH_ID, "port_id", "in"));
        parameters.put("excluded_selectors", List.of("device:role:haptic_output"));
        return new DynamicGeneratorNode(UnifiedGraphConfig.MONITOR_OUTPUT_ID, "Generated output", "device:*",
                List.of("FL", "FR"), parameters);
    }
    static AudioGraph replaceGenerator(Map<String, Object> config, DynamicGeneratorNode generator) {
        Map<String, Object> savedRoute = routes(config).stream()
                .filter(route -> "main".equals(route.get("id"))).findFirst().orElseThrow();
        AudioGraph original = GraphCodec.fromMap(savedRoute);
        List<GraphNode> nodes = new ArrayList<>(original.nodes());
        int index = nodes.stream().map(GraphNode::id).toList().indexOf(generator.id());
        if (index >= 0) nodes.set(index, generator); else nodes.add(generator);
        AudioGraph changed = new AudioGraph(original.id(), original.name(), nodes, original.connections());
        GraphRouteLibrary.saveRoute(config, changed, String.valueOf(savedRoute.getOrDefault("group_id", "")));
        return GraphCodec.fromMap(routes(config).stream()
                .filter(route -> "main".equals(route.get("id"))).findFirst().orElseThrow());
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> routes(Map<String, Object> config) {
        return (List<Map<String, Object>>) config.get("route_library");
    }
}
