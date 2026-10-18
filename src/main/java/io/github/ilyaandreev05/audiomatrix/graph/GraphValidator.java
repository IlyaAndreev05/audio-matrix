package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure graph checks; validation never creates PipeWire nodes or plays audio. */
public final class GraphValidator {
    private GraphValidator() {}

    public static List<String> validate(AudioGraph graph) {
        List<String> errors = new ArrayList<>();
        Map<String, GraphNode> nodes = new HashMap<>();
        for (GraphNode node : graph.nodes()) {
            if (nodes.putIfAbsent(node.id(), node) != null) errors.add("Duplicate node id: " + node.id());
        }
        Set<String> connectionIds = new HashSet<>();
        Set<String> enabledEndpointPairs = new HashSet<>();
        Set<String> occupiedInputs = new HashSet<>();
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (GraphConnection edge : graph.connections()) {
            if (!connectionIds.add(edge.id())) errors.add("Duplicate connection id: " + edge.id());
            if (edge.gain() != 1.0)
                errors.add("Connection " + edge.id() + " has a level; insert a Gain node instead");
            if (edge.enabled()) {
                String endpoints=edge.fromNode()+"."+edge.fromPort()+"->"+edge.toNode()+"."+edge.toPort();
                if(!enabledEndpointPairs.add(endpoints))errors.add("Duplicate enabled connection: "+endpoints);
            }
            GraphNode from = nodes.get(edge.fromNode()), to = nodes.get(edge.toNode());
            if (from == null || to == null) { errors.add("Connection " + edge.id() + " references a missing node"); continue; }
            AudioPort source = from.ports().stream().filter(p -> p.id().equals(edge.fromPort())).findFirst().orElse(null);
            AudioPort target = to.ports().stream().filter(p -> p.id().equals(edge.toPort())).findFirst().orElse(null);
            if (source == null || source.direction() != AudioPort.Direction.OUTPUT) {
                errors.add("Connection " + edge.id() + " has no output port " + edge.fromNode() + "." + edge.fromPort());
                continue;
            }
            if (target == null || target.direction() != AudioPort.Direction.INPUT) {
                errors.add("Connection " + edge.id() + " has no input port " + edge.toNode() + "." + edge.toPort());
                continue;
            }
            String targetAddress = edge.toNode() + "." + edge.toPort();
            if (edge.enabled() && !occupiedInputs.add(targetAddress) && !target.acceptsMultiple())
                errors.add("Input " + targetAddress + " accepts one connection; insert an explicit Mixer");
            if (!source.channels().equals(target.channels()))
                errors.add("Channel layout mismatch on connection " + edge.id() + ": " + source.channels() + " → " + target.channels());
            if (edge.enabled()) adjacency.computeIfAbsent(edge.fromNode(), ignored -> new HashSet<>()).add(edge.toNode());
        }
        if (hasCycle(nodes.keySet(), adjacency)) errors.add("Graph contains a feedback cycle");
        return List.copyOf(errors);
    }

    private static boolean hasCycle(Set<String> nodes, Map<String, Set<String>> adjacency) {
        Set<String> visiting = new HashSet<>(), visited = new HashSet<>();
        for (String node : nodes) if (cycle(node, adjacency, visiting, visited)) return true;
        return false;
    }

    private static boolean cycle(String node, Map<String, Set<String>> adjacency, Set<String> visiting, Set<String> visited) {
        if (visited.contains(node)) return false;
        if (!visiting.add(node)) return true;
        for (String next : adjacency.getOrDefault(node, Set.of())) if (cycle(next, adjacency, visiting, visited)) return true;
        visiting.remove(node);
        visited.add(node);
        return false;
    }
}
