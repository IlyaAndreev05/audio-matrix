package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps the generator-owned canvas containers in sync with the persisted instance records. */
final class GraphGeneratorComposites {
    private GraphGeneratorComposites() {}

    /**
     * Returns the automatic containers missing for the generator instances of the graph.
     * An instance whose children are already materialized in the graph keeps its authored container,
     * so a missing one is reported by the configuration validation instead of being created here.
     */
    static List<GraphSubgraph> missing(AudioGraph graph, List<GraphSubgraph> groups) {
        Set<String> known = new LinkedHashSet<>();
        graph.nodes().forEach(node -> known.add(node.id()));
        Set<String> existing = new LinkedHashSet<>();
        groups.forEach(group -> existing.add(group.id()));
        List<GraphSubgraph> created = new ArrayList<>();
        for (GraphNode node : graph.nodes()) {
            if (!(node instanceof DynamicGeneratorNode generator)) continue;
            for (Map.Entry<String, Object> entry : generator.instances().entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> instance)) continue;
                if (!(instance.get("composite_id") instanceof String compositeId) || compositeId.isBlank()) continue;
                if (existing.contains(compositeId)) continue;
                if (materialized(instance, known)) continue;
                created.add(new GraphSubgraph(compositeId, name(instance, entry.getKey()), List.of(), true, null,
                        generator.id()));
                existing.add(compositeId);
            }
        }
        return created;
    }

    /** Creates the missing automatic containers of the route's persisted generator instances. */
    static void ensureRoute(Map<String, Object> config, AudioGraph graph) {
        List<GraphSubgraph> groups = GraphSubgraphLibrary.forRoute(config, graph.id());
        List<GraphSubgraph> created = missing(graph, groups);
        if (created.isEmpty()) return;
        List<Map<String, Object>> saved = new ArrayList<>(GraphSubgraphLibrary.encoded(groups));
        created.forEach(group -> saved.add(group.toMap()));
        GraphSubgraphLibrary.mutableByRoute(config).put(graph.id(), saved);
    }

    /** Creates the missing automatic containers of a saved copy's persisted generator instances. */
    static void ensureCopy(Map<String, Object> copy, AudioGraph graph) {
        List<GraphSubgraph> groups = new ArrayList<>();
        Object raw = copy.get("ui_subgraphs");
        if (raw instanceof List<?> list)
            for (Object item : list) if (item instanceof Map<?, ?> map) groups.add(GraphSubgraph.fromMap(map));
        List<GraphSubgraph> created = missing(graph, groups);
        if (created.isEmpty()) return;
        List<Map<String, Object>> saved = new ArrayList<>(GraphSubgraphLibrary.encoded(groups));
        created.forEach(group -> saved.add(group.toMap()));
        copy.put("ui_subgraphs", saved);
    }

    /** Creates the missing automatic containers of the given persisted routes. */
    static void ensureConfigRoutes(Map<String, Object> config, List<Object> routes) {
        for (Object raw : routes) {
            if (!(raw instanceof Map<?, ?> route)) continue;
            Map<String, Object> decoded = new LinkedHashMap<>();
            route.forEach((key, value) -> decoded.put(String.valueOf(key), value));
            AudioGraph graph;
            try { graph = GraphCodec.fromMap(decoded); }
            catch (RuntimeException invalid) { continue; }
            ensureRoute(config, graph);
        }
    }

    /** Returns a copy of the container map so that the source configuration stays unchanged. */
    static Map<String, Object> copiedContainers(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(String.valueOf(key),
                value instanceof List<?> list ? new ArrayList<>(list) : value));
        return result;
    }

    private static boolean materialized(Map<?, ?> instance, Set<String> known) {
        if (!(instance.get("node_ids") instanceof Map<?, ?> nodeIds)) return false;
        for (Object rawId : nodeIds.values())
            if (rawId instanceof String id && known.contains(id)) return true;
        return false;
    }

    private static String name(Map<?, ?> instance, String instanceKey) {
        Object raw = instance.get("display_name");
        if (raw instanceof String display && !display.isBlank()) return display;
        return instanceKey == null ? "" : instanceKey;
    }
}
