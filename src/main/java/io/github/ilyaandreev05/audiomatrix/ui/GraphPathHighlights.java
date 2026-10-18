package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

/** Finds enabled graph edges that lead into or out of the selected node. */
final class GraphPathHighlights {
    private GraphPathHighlights() {}

    static Set<String> throughNode(AudioGraph graph, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) return Set.of();
        Set<String> upstream = reachable(graph, nodeId, false);
        Set<String> downstream = reachable(graph, nodeId, true);
        Set<String> result = new LinkedHashSet<>();
        for (GraphConnection edge : graph.connections()) {
            if (!edge.enabled()) continue;
            boolean leadsIntoSelection = upstream.contains(edge.fromNode()) && upstream.contains(edge.toNode());
            boolean leadsFromSelection = downstream.contains(edge.fromNode()) && downstream.contains(edge.toNode());
            if (leadsIntoSelection || leadsFromSelection) result.add(edge.id());
        }
        return Set.copyOf(result);
    }

    private static Set<String> reachable(AudioGraph graph, String root, boolean forward) {
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current)) continue;
            for (GraphConnection edge : graph.connections()) {
                if (!edge.enabled()) continue;
                String from = forward ? edge.fromNode() : edge.toNode();
                String to = forward ? edge.toNode() : edge.fromNode();
                if (from.equals(current) && !visited.contains(to)) pending.addLast(to);
            }
        }
        return visited;
    }
}
