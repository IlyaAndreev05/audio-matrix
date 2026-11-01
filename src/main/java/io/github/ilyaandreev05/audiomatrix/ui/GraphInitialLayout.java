package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.GraphLayoutNodes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Applies a rendered snapshot's defaults only to identities still present at commit time. */
final class GraphInitialLayout {
    private GraphInitialLayout() {}

    static void apply(Map<String,Object> config, String routeId, Map<String,GraphCanvasLayout.Position> missing) {
        Set<String> allowed = GraphLayoutNodes.ids(config, routeId);
        if (allowed.isEmpty()) return;
        Map<String,Object> all = copy(config.get("graph_ui_layout"));
        Map<String,Object> route = copy(all.get(routeId));
        missing.forEach((id, position) -> {
            if (!allowed.contains(id) || position == null) return;
            Map<String,Object> current = copy(route.get(id));
            double x = coordinate(current.get("x"), position.x());
            double y = coordinate(current.get("y"), position.y());
            route.put(id, Map.of("x", x, "y", y));
        });
        all.put(routeId, route);
        config.put("graph_ui_layout", all);
    }

    private static double coordinate(Object value, double fallback) {
        return value instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() >= 0 ? number.doubleValue() : fallback;
    }

    private static Map<String,Object> copy(Object value) {
        Map<String,Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?,?> map) map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
