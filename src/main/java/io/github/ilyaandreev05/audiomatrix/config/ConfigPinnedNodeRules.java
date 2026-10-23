package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConfigPinnedNodeRules {
    private ConfigPinnedNodeRules() {}

    static void validate(Map<String, Object> config, Map<String, AudioGraph> graphs,
                         List<Map<String, Object>> controls, List<String> errors) {
        List<Map<String, Object>> pins = QuickSettingsConfig.pins(config);
        Set<String> controlIds = new HashSet<>();
        controls.forEach(control -> controlIds.add(String.valueOf(control.get("id"))));
        for (int index = 0; index < pins.size(); index++) {
            Map<String, Object> pin = pins.get(index);
            String routeId = String.valueOf(pin.getOrDefault("route_id", ""));
            String nodeId = String.valueOf(pin.getOrDefault("node_id", ""));
            AudioGraph graph = graphs.get(routeId);
            if (graph == null) {
                errors.add("quick_settings.pins[" + index + "] references a missing route");
                continue;
            }
            GraphNode node = graph.nodes().stream().filter(item -> item.id().equals(nodeId)).findFirst().orElse(null);
            boolean composite = hasCompositeId(config, routeId, nodeId);
            if (node == null && !composite)
                errors.add("quick_settings.pins[" + index + "] references a missing graph node or composite");
            String instanceKey = String.valueOf(pin.getOrDefault("instance_key", ""));
            String childId = String.valueOf(pin.getOrDefault("child_id", ""));
            if (!instanceKey.isBlank() && !(node instanceof DynamicGeneratorNode))
                errors.add("quick_settings.pins[" + index + "] instance_key requires a generator");
            if (!childId.isBlank() && (!(node instanceof DynamicGeneratorNode generator)
                    || generator.templateNodes().stream().noneMatch(child -> childId.equals(child.get("id")))))
                errors.add("quick_settings.pins[" + index + "] references a missing generator template child");
            Object ids = pin.get("control_ids");
            if (!(ids instanceof List<?> list)) continue;
            for (Object rawId : list) {
                String id = String.valueOf(rawId);
                Map<String, Object> control = controls.stream()
                        .filter(item -> id.equals(item.get("id"))).findFirst().orElse(null);
                if (!controlIds.contains(id) || control == null) {
                    errors.add("quick_settings.pins[" + index + "] references a missing quick control");
                    continue;
                }
                if (!routeId.equals(String.valueOf(control.getOrDefault("route_id", "")))
                        || !nodeId.equals(String.valueOf(control.getOrDefault("node_id", ""))))
                    errors.add("quick_settings.pins[" + index + "] control must reference the same route and node");
            }
        }
    }

    private static boolean hasCompositeId(Map<String, Object> config, String routeId, String nodeId) {
        if (!(config.get("graph_ui_subgraphs") instanceof Map<?, ?> routes)
                || !(routes.get(routeId) instanceof List<?> groups)) return false;
        for (Object item : groups)
            if (item instanceof Map<?, ?> group && nodeId.equals(group.get("id"))) return true;
        return false;
    }
}
