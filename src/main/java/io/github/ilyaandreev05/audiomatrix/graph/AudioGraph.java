package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;

public record AudioGraph(String id, String name, List<GraphNode> nodes, List<GraphConnection> connections) {
    public AudioGraph {
        if (id == null || id.isBlank() || name == null || name.isBlank()) throw new IllegalArgumentException("Graph id and name are required");
        nodes = List.copyOf(nodes);
        connections = List.copyOf(connections);
    }
}
