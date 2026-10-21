package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.LinkedHashMap;
import java.util.Map;

/** Carries canvas layout, zoom and matrix views to a duplicated route. */
final class GraphRouteCopyState {
    private GraphRouteCopyState() {}

    static void copy(Map<String, Object> config, String routeId, String newRouteId, Map<String, String> nodeIds) {
        Map<String, Object> layouts = GraphRouteLibrary.mutableMap(config, "graph_ui_layout");
        Map<String, Object> sourceLayout = GraphRouteLibrary.map(layouts.get(routeId));
        Map<String, Object> copiedLayout = new LinkedHashMap<>();
        sourceLayout.forEach((oldNode, value) -> {
            String newNode = nodeIds.get(oldNode);
            if (newNode != null) copiedLayout.put(newNode, GraphRouteLibrary.deepValue(value));
        });
        if (!copiedLayout.isEmpty()) layouts.put(newRouteId, copiedLayout);
        io.github.ilyaandreev05.audiomatrix.config.GraphMatrixViewSettings.duplicateRoute(
                config, routeId, newRouteId, nodeIds);
        Map<String, Object> scales = GraphRouteLibrary.mutableMap(config, "graph_ui_scale");
        Object scale = scales.get(routeId);
        if (scale instanceof Number) scales.put(newRouteId, scale);
    }
}
