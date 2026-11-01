package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Node identities whose positions can be persisted with a configured graph. */
public final class GraphLayoutNodes {
    private GraphLayoutNodes() {}

    public static Set<String> ids(AudioGraph graph) {
        Set<String> ids = new LinkedHashSet<>();
        for (GraphNode node : graph.nodes()) {
            ids.add(node.id());
            if (node instanceof DynamicGeneratorNode generator)
                for (GraphGeneratedInstances.Instance instance : GraphGeneratedInstances.of(generator, null))
                    ids.addAll(instance.nodeIds().values());
        }
        return ids;
    }

    /** Resolve from the configuration being mutated, not a potentially stale rendered graph. */
    public static Set<String> ids(Map<String, Object> config, String routeId) {
        if (GraphCommonVibro.COMMON_ROUTE_ID.equals(routeId)) {
            AudioGraph common = GraphCommonVibro.graph(config);
            return common == null ? Set.of() : ids(common);
        }
        if (config.get("route_library") instanceof Iterable<?> routes)
            for (Object item : routes)
                if (item instanceof Map<?, ?> route && routeId.equals(route.get("id"))) {
                    @SuppressWarnings("unchecked") Map<String, Object> encoded = (Map<String, Object>) route;
                    return ids(GraphCodec.fromMap(encoded));
                }
        return Set.of();
    }
}
