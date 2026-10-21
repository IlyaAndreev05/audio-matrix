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
        return create(config, graph, name, nodeIds, null);
    }

    public static GraphSubgraph create(Map<String,Object> config, AudioGraph graph, String name, List<String> nodeIds, String parentId) {
        Set<String> known = graph.nodes().stream().map(GraphNode::id).collect(java.util.stream.Collectors.toSet());
        Set<String> members = new LinkedHashSet<>(nodeIds);
        if (!known.containsAll(members)) throw new IllegalArgumentException("Subgraph references a missing node");
        List<GraphSubgraph> existingGroups = forRoute(config, graph.id());
        if (parentId != null && existingGroups.stream().noneMatch(group -> parentId.equals(group.id())))
            throw new IllegalArgumentException("Subgraph parent does not exist: " + parentId);
        for (GraphSubgraph existing : existingGroups)
            if (existing.nodeIds().stream().anyMatch(members::contains))
                throw new IllegalArgumentException("A node can belong to only one subgraph");
        GraphSubgraph created = new GraphSubgraph("subgraph-" + UUID.randomUUID(), name, List.copyOf(members), true,
                parentId, null);
        List<Map<String,Object>> saved = new ArrayList<>(encoded(existingGroups));
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
        GraphSubgraph deleted = forRoute(config, routeId).stream().filter(group -> subgraphId.equals(group.id())).findFirst().orElse(null);
        if (deleted != null && deleted.generatorId() != null)
            throw new IllegalArgumentException("Generator-owned subgraphs are managed by their generator");
        if (deleted != null)
            for (Map<String,Object> item : items)
                if (subgraphId.equals(item.get("parent_id"))) setParent(item, deleted.parentId());
        items.removeIf(value -> subgraphId.equals(value.get("id")));
        if (items.isEmpty()) byRoute(config).remove(routeId);
    }

    public static void removeNode(Map<String,Object> config, String routeId, String nodeId) {
        Object raw = byRoute(config).get(routeId);
        if (!(raw instanceof List<?>)) return;
        @SuppressWarnings("unchecked") List<Map<String,Object>> items = (List<Map<String,Object>>) raw;
        List<GraphSubgraph> groups = forRoute(config, routeId);
        if (groups.stream().anyMatch(group -> group.generatorId() != null && !group.generatorId().equals(nodeId)
                && group.nodeIds().contains(nodeId)))
            throw new IllegalArgumentException("Nodes in generator-owned subgraphs are managed by their generator");
        Map<String,GraphSubgraph> groupsById = new LinkedHashMap<>();
        groups.forEach(group -> groupsById.putIfAbsent(group.id(), group));
        Map<String,String> retainedIds = new LinkedHashMap<>();
        for (Map<String,Object> item : items) {
            List<String> ids = strings(item.get("node_ids"));
            ids.remove(nodeId);
            item.put("node_ids", ids);
            String id = item.get("id") instanceof String value ? value : null;
            boolean generatorRemoved = nodeId.equals(optionalString(item, "generator_id"));
            boolean generatorOwned = optionalString(item, "generator_id") != null;
            if (!generatorRemoved && id != null && (ids.size() >= 2 || generatorOwned)) retainedIds.put(id, id);
        }
        for (Map<String,Object> item : items) {
            String id = item.get("id") instanceof String value ? value : null;
            GraphSubgraph group = id == null ? null : groupsById.get(id);
            if (group != null && retainedIds.containsKey(id))
                setParent(item, nearestRetainedParent(group.parentId(), groupsById, retainedIds));
        }
        items.removeIf(item -> !(item.get("id") instanceof String id) || !retainedIds.containsKey(id));
        if (items.isEmpty()) byRoute(config).remove(routeId);
    }

    public static void duplicateRoute(Map<String,Object> config, String fromRoute, String toRoute, Map<String,String> nodeIds) {
        List<GraphSubgraph> source = forRoute(config, fromRoute);
        if (source.isEmpty()) return;
        Map<String,Object> sourceRoute = GraphRouteLibrary.find(GraphRouteLibrary.maps(config.get("route_library")), fromRoute);
        AudioGraph sourceGraph = sourceRoute == null ? null : GraphCodec.fromMap(sourceRoute);
        Map<String,String> generatedGroupIds = remappedCompositeIds(sourceGraph, nodeIds);
        Map<String,GraphSubgraph> groupsById = new LinkedHashMap<>();
        source.forEach(group -> groupsById.putIfAbsent(group.id(), group));
        Map<String,String> copiedIds = new LinkedHashMap<>();
        Map<String,String> copiedGeneratorIds = new LinkedHashMap<>();
        Map<String,List<String>> copiedMembers = new LinkedHashMap<>();
        for (GraphSubgraph group : source) {
            List<String> members = group.nodeIds().stream().map(nodeIds::get).filter(java.util.Objects::nonNull).toList();
            String generatorId = group.generatorId() == null ? null : nodeIds.get(group.generatorId());
            String copiedGroupId = group.generatorId() == null ? "subgraph-" + UUID.randomUUID() : generatedGroupIds.get(group.id());
            if (group.generatorId() != null && (generatorId == null || copiedGroupId == null)) continue;
            if (members.size() >= 2 || (members.size() <= 1 && generatorId != null)) {
                copiedIds.put(group.id(), copiedGroupId);
                copiedGeneratorIds.put(group.id(), generatorId);
                copiedMembers.put(group.id(), members);
            }
        }
        List<Map<String,Object>> copied = new ArrayList<>();
        for (GraphSubgraph group : source) {
            String copiedId = copiedIds.get(group.id());
            if (copiedId == null) continue;
            String parentId = nearestRetainedParent(group.parentId(), groupsById, copiedIds);
            copied.add(new GraphSubgraph(copiedId, group.name(), copiedMembers.get(group.id()), group.collapsed(),
                    parentId, copiedGeneratorIds.get(group.id())).toMap());
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
    private static String optionalString(Map<String,Object> item, String key) {
        return item.get(key) instanceof String value && !value.isBlank() ? value : null;
    }
    private static void setParent(Map<String,Object> item, String parentId) {
        if (parentId == null) item.remove("parent_id");
        else item.put("parent_id", parentId);
    }
    private static String nearestRetainedParent(String parentId, Map<String,GraphSubgraph> groupsById,
                                                Map<String,String> retainedIds) {
        Set<String> seen = new LinkedHashSet<>();
        String current = parentId;
        while (current != null && seen.add(current)) {
            String retained = retainedIds.get(current);
            if (retained != null) return retained;
            GraphSubgraph parent = groupsById.get(current);
            current = parent == null ? null : parent.parentId();
        }
        return null;
    }
    private static Map<String,String> remappedCompositeIds(AudioGraph graph, Map<String,String> nodeIds) {
        Map<String,String> result = new LinkedHashMap<>();
        if (graph == null) return result;
        for (GraphNode node : graph.nodes()) {
            if (!(node instanceof DynamicGeneratorNode generator)) continue;
            String copiedGeneratorId = nodeIds.get(generator.id());
            if (copiedGeneratorId == null) continue;
            for (Map.Entry<String,Object> entry : generator.instances().entrySet()) {
                if (!(entry.getValue() instanceof Map<?,?> instance)) continue;
                if (!(instance.get("composite_id") instanceof String compositeId) || compositeId.isBlank()) continue;
                result.put(compositeId, DynamicInstanceRegistry.stableCompositeId(copiedGeneratorId, entry.getKey()));
            }
        }
        return result;
    }
}
