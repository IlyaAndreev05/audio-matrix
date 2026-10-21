package io.github.ilyaandreev05.audiomatrix.graph;

final class GraphValidationSupport {
    private GraphValidationSupport() {}

    static AudioPort port(GraphNode node, String id) {
        return node.ports().stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
    }
}
