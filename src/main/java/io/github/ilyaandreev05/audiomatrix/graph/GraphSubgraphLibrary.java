package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Stores collapsible canvas groups separately from the audio graph topology. */
public final class GraphSubgraphLibrary {
    private static final String KEY = "graph_ui_subgraphs";
    private GraphSubgraphLibrary() {}

    public static List<GraphSubgraph> forRoute(Map<String,Object> config, String routeId) {
        Map<String,Object> byRoute = map(config.get(KEY));
        Object raw = byRoute.get(routeId);
        if (!(raw instanceof List<?> list)) return List.of();
        List<GraphSubgraph> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?,?> map) result.add(GraphSubgraph.fromMap(map));
        return List.copyOf(result);
    }

    public static GraphSubgraph create(Map<String,Object> config, AudioGraph graph, String name, List<String> nodeIds) {
        Set<String> known = graph.nodes().stream().map(GraphNode::id).collect(java.util.stream.Collectors.toSet());
        Set<String> members = new LinkedHashSet<>(nodeIds);
        if (!known.containsAll(members)) throw new IllegalArgumentException("Subgraph references a missing node");
        for (GraphSubgraph existing : forRoute(config, graph.id()))
            if (existing.nodeIds().stream().anyMatch(members::contains))
                throw new IllegalArgumentException("A node can belong to only one subgraph");
        GraphSubgraph created = new GraphSubgraph("subgraph-" + UUID.randomUUID(), name, List.copyOf(members), true);
        List<Map<String,Object>> saved = new ArrayList<>(encoded(forRoute(config, graph.id())));
        saved.add(created.toMap());
        byRoute(config).put(graph.id(), saved);
        return created;
    }

    public static void setCollapsed(Map<String,Object> config, String routeId, String subgraphId, boolean collapsed) {
        List<Map<String,Object>> items = mutableItems(config, routeId);
        Map<String,Object> item = items.stream().filter(value -> subgraphId.equals(value.get("id"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Subgraph does not exist: " + subgraphId));
        item.put("collapsed", collapsed);
    }

    public static void delete(Map<String,Object> config, String routeId, String subgraphId) {
        List<Map<String,Object>> items = mutableItems(config, routeId);
        items.removeIf(value -> subgraphId.equals(value.get("id")));
        if (items.isEmpty()) byRoute(config).remove(routeId);
    }

    public static void removeNode(Map<String,Object> config, String routeId, String nodeId) {
        Object raw = byRoute(config).get(routeId);
        if (!(raw instanceof List<?>)) return;
        @SuppressWarnings("unchecked") List<Map<String,Object>> items = (List<Map<String,Object>>) raw;
        for (Map<String,Object> item : items) {
            List<String> ids = strings(item.get("node_ids"));
            ids.remove(nodeId);
            item.put("node_ids", ids);
        }
        items.removeIf(item -> strings(item.get("node_ids")).size() < 2);
        if (items.isEmpty()) byRoute(config).remove(routeId);
    }

    public static void duplicateRoute(Map<String,Object> config, String fromRoute, String toRoute, Map<String,String> nodeIds) {
        List<GraphSubgraph> source = forRoute(config, fromRoute);
        if (source.isEmpty()) return;
        List<Map<String,Object>> copied = new ArrayList<>();
        for (GraphSubgraph group : source) {
            List<String> members = group.nodeIds().stream().map(nodeIds::get).filter(java.util.Objects::nonNull).toList();
            if (members.size() >= 2) copied.add(new GraphSubgraph("subgraph-" + UUID.randomUUID(), group.name(), members, group.collapsed()).toMap());
        }
        if (!copied.isEmpty()) byRoute(config).put(toRoute, copied);
    }

    public static List<Map<String,Object>> encoded(List<GraphSubgraph> groups) {
        return groups.stream().map(GraphSubgraph::toMap).toList();
    }

    public static Map<String,Object> mutableByRoute(Map<String,Object> config) { return byRoute(config); }

    private static List<Map<String,Object>> mutableItems(Map<String,Object> config, String routeId) {
        Object raw = byRoute(config).get(routeId);
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("Route has no subgraphs: " + routeId);
        @SuppressWarnings("unchecked") List<Map<String,Object>> items = (List<Map<String,Object>>) list;
        return items;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> byRoute(Map<String,Object> config) {
        Object value = config.get(KEY);
        if (value instanceof Map<?,?>) return (Map<String,Object>) value;
        Map<String,Object> created = new LinkedHashMap<>(); config.put(KEY, created); return created;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) {
        return value instanceof Map<?,?> map ? (Map<String,Object>) map : Map.of();
    }
    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        return list.stream().map(String::valueOf).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
}
