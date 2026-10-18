package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import java.util.List;
import java.util.Map;

/** Resolves the shared root graph without manufacturing a route-library copy. */
final class GraphUiRoutes {
    private GraphUiRoutes() {}

    static boolean common(String routeId) {
        return GraphCommonVibro.COMMON_ROUTE_ID.equals(routeId);
    }

    static boolean available(Map<String, Object> config, String routeId) {
        return common(routeId) || routeId.equals(config.get("active_route"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> route(Map<String, Object> config, String routeId) {
        if (common(routeId)) {
            if (config.get("common_vibro_graph") instanceof Map<?, ?> graph)
                return (Map<String, Object>) graph;
            throw new IllegalArgumentException("Common Vibro graph is unavailable");
        }
        if (config.get("route_library") instanceof List<?> routes)
            for (Object entry : routes)
                if (entry instanceof Map<?, ?> route && routeId.equals(route.get("id")))
                    return (Map<String, Object>) route;
        throw new IllegalArgumentException("Route is unavailable: " + routeId);
    }
}
