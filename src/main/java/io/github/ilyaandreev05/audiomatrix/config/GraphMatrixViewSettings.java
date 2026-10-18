package io.github.ilyaandreev05.audiomatrix.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Persists Matrix presentation choices separately from route DSP. */
public final class GraphMatrixViewSettings {
    private static final Set<String> VIEWS = Set.of("MATRIX", "ARROWS");
    private static final String KEY = "graph_ui_matrix_views";

    private GraphMatrixViewSettings() {}

    public static String get(Map<String, Object> config, String routeId, String nodeId) {
        Map<String, Object> route = object(object(config.get(KEY)).get(routeId));
        Map<String, Object> views = route;
        String saved = String.valueOf(views.getOrDefault(nodeId, "MATRIX"));
        return VIEWS.contains(saved) ? saved : "MATRIX";
    }

    public static void set(Map<String, Object> config, String routeId, String nodeId, String view) {
        if (routeId == null || routeId.isBlank() || nodeId == null || nodeId.isBlank())
            throw new IllegalArgumentException("Matrix route and node IDs are required");
        if (!VIEWS.contains(view)) throw new IllegalArgumentException("Unsupported Matrix view: " + view);
        Map<String, Object> routes = object(config.get(KEY));
        config.put(KEY, routes);
        Map<String, Object> route = object(routes.get(routeId));
        routes.put(routeId, route);
        route.put(nodeId, view);
    }

    public static void duplicateRoute(Map<String, Object> config, String sourceRoute,
                                      String targetRoute, Map<String, String> nodeIds) {
        Map<String, Object> routes = object(config.get(KEY));
        Map<String, Object> source = object(routes.get(sourceRoute));
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((nodeId, view) -> {
            String replacement = nodeIds.get(nodeId);
            if (replacement != null && VIEWS.contains(view)) copy.put(replacement, view);
        });
        if (!copy.isEmpty()) routes.put(targetRoute, copy);
        config.put(KEY, routes);
    }

    public static void removeRoute(Map<String, Object> config, String routeId) {
        Map<String, Object> routes = object(config.get(KEY));
        routes.remove(routeId);
        config.put(KEY, routes);
    }

    public static Map<String, Object> copyForRoute(Map<String, Object> config, String routeId) {
        return new LinkedHashMap<>(object(object(config.get(KEY)).get(routeId)));
    }

    public static void restoreForRoute(Map<String, Object> config, String routeId, Object saved) {
        Map<String, Object> routes = object(config.get(KEY));
        Map<String, Object> views = object(saved);
        if (!views.isEmpty()) routes.put(routeId, views);
        config.put(KEY, routes);
    }

    private static Map<String, Object> object(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map)
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
