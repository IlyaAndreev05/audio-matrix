package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Detects feedback cycles in a graph or in a generator template. */
final class GraphFeedbackCycle {
    private GraphFeedbackCycle() {}

    /** Reports a cycle over the enabled connections of the given graph. */
    static boolean contains(AudioGraph graph) {
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (GraphConnection edge : graph.connections()) {
            if (!edge.enabled()) continue;
            adjacency.computeIfAbsent(edge.fromNode(), ignored -> new HashSet<>()).add(edge.toNode());
        }
        List<String> nodeIds = graph.nodes().stream().map(GraphNode::id).toList();
        return contains(nodeIds, adjacency);
    }

    /** Reports a cycle over a prepared adjacency map. */
    static boolean contains(Collection<String> nodeIds, Map<String, Set<String>> adjacency) {
        Set<String> visiting = new HashSet<>(), visited = new HashSet<>();
        for (String node : nodeIds) if (cycle(node, adjacency, visiting, visited)) return true;
        return false;
    }

    private static boolean cycle(String node, Map<String, Set<String>> adjacency,
                                 Set<String> visiting, Set<String> visited) {
        if (visited.contains(node)) return false;
        if (!visiting.add(node)) return true;
        for (String next : adjacency.getOrDefault(node, Set.of()))
            if (cycle(next, adjacency, visiting, visited)) return true;
        visiting.remove(node);
        visited.add(node);
        return false;
    }
}
