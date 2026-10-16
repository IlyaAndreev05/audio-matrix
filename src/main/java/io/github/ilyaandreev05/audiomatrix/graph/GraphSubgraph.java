package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A saved canvas group that displays a set of ordinary graph nodes as one collapsed block. */
public record GraphSubgraph(String id, String name, List<String> nodeIds, boolean collapsed) {
    public GraphSubgraph {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Subgraph id must not be blank");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Subgraph name must not be blank");
        nodeIds = List.copyOf(nodeIds);
        if (nodeIds.size() < 2 || nodeIds.stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("A subgraph must contain at least two named nodes");
        if (nodeIds.stream().distinct().count() != nodeIds.size())
            throw new IllegalArgumentException("A subgraph cannot contain the same node twice");
    }

    public Set<String> nodeSet() { return Set.copyOf(nodeIds); }

    public Map<String,Object> toMap() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",id);result.put("name",name);result.put("node_ids",new ArrayList<>(nodeIds));result.put("collapsed",collapsed);
        return result;
    }

    public static GraphSubgraph fromMap(Map<?,?> value) {
        if (!(value.get("id") instanceof String id) || !(value.get("name") instanceof String name))
            throw new IllegalArgumentException("Subgraph id and name must be strings");
        Object rawIds = value.get("node_ids");
        if (!(rawIds instanceof List<?> list)) throw new IllegalArgumentException("Subgraph node_ids must be an array");
        List<String> ids = list.stream().map(String::valueOf).toList();
        Object rawCollapsed = value.get("collapsed");
        if (!(rawCollapsed instanceof Boolean collapsed)) throw new IllegalArgumentException("Subgraph collapsed must be boolean");
        return new GraphSubgraph(id, name, ids, collapsed);
    }

    public static List<BoundaryPort> boundaryPorts(AudioGraph graph, Collection<String> members) {
        Set<String> memberSet = new LinkedHashSet<>(members);
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        graph.nodes().forEach(node -> nodes.put(node.id(), node));
        Map<String, BoundaryPort> ports = new LinkedHashMap<>();
        for (GraphConnection edge : graph.connections()) {
            boolean fromInside = memberSet.contains(edge.fromNode());
            boolean toInside = memberSet.contains(edge.toNode());
            if (fromInside == toInside) continue;
            GraphNode endpoint = nodes.get(fromInside ? edge.fromNode() : edge.toNode());
            String portId = fromInside ? edge.fromPort() : edge.toPort();
            Direction direction = fromInside ? Direction.OUTPUT : Direction.INPUT;
            String key = direction + ":" + endpoint.id() + ":" + portId;
            ports.putIfAbsent(key, new BoundaryPort(direction, endpoint.id(), portId,
                    endpoint.name() + " · " + portId));
        }
        return List.copyOf(ports.values());
    }

    public enum Direction { INPUT, OUTPUT }
    public record BoundaryPort(Direction direction, String nodeId, String portId, String label) {}
}
