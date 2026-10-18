package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConfigGraphMatrixViewRules {
    private static final Set<String> VIEWS = Set.of("MATRIX", "ARROWS");

    private ConfigGraphMatrixViewRules() {}

    static void validate(Map<String, Object> config, Map<String, AudioGraph> graphs,
                         Set<String> routeIds, List<String> errors) {
        Object raw = config.get("graph_ui_matrix_views");
        if (raw == null) return;
        if (!(raw instanceof Map<?, ?> routes)) {
            errors.add("graph_ui_matrix_views must be an object");
            return;
        }
        for (Map.Entry<?, ?> routeEntry : routes.entrySet()) {
            String routeId = String.valueOf(routeEntry.getKey());
            AudioGraph graph = graphs.get(routeId);
            if (!routeIds.contains(routeId) || graph == null) {
                errors.add("graph_ui_matrix_views references a missing route: " + routeId);
                continue;
            }
            validateNodes(routeEntry.getValue(), graph, routeId, errors);
        }
    }

    private static void validateNodes(Object raw, AudioGraph graph, String routeId, List<String> errors) {
        if (!(raw instanceof Map<?, ?> nodes)) {
            errors.add("graph_ui_matrix_views[" + routeId + "] must be an object");
            return;
        }
        for (Map.Entry<?, ?> entry : nodes.entrySet()) {
            String nodeId = String.valueOf(entry.getKey());
            GraphNode node = graph.nodes().stream().filter(item -> item.id().equals(nodeId)).findFirst().orElse(null);
            if (node == null || !"matrix".equals(node.type())) {
                errors.add("graph_ui_matrix_views[" + routeId + "] references a missing Matrix node: " + nodeId);
            } else if (!(entry.getValue() instanceof String view) || !VIEWS.contains(view)) {
                errors.add("graph_ui_matrix_views[" + routeId + "]." + nodeId + " must be MATRIX or ARROWS");
            }
        }
    }
}
