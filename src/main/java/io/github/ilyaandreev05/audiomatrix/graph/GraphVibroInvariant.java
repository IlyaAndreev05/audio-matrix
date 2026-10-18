package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The shared signal topology is fixed; users edit gain, input mutes and device binding. */
final class GraphVibroInvariant {
    private GraphVibroInvariant() {}

    static void require(AudioGraph graph) {
        AudioGraph template = GraphCommonVibro.defaults();
        Map<String, GraphNode> actual = graph.nodes().stream().collect(Collectors.toMap(GraphNode::id, Function.identity()));
        if (!actual.keySet().equals(template.nodes().stream().map(GraphNode::id).collect(Collectors.toSet())))
            throw new IllegalArgumentException("The common Vibro nodes cannot be added, removed or renamed by ID");
        for (GraphNode expected : template.nodes()) {
            GraphNode node = actual.get(expected.id());
            if (!expected.getClass().equals(node.getClass()) || !ports(expected).equals(ports(node)))
                throw new IllegalArgumentException("The common Vibro node types and ports are fixed");
            if (expected instanceof SourceNode source && !source.selector().equals(((SourceNode) node).selector()))
                throw new IllegalArgumentException("The common Main and EVENT source selectors are fixed");
            if (expected instanceof FilterNode filter) {
                FilterNode changed = (FilterNode) node;
                if (!filter.processor().equals(changed.processor()) || !changed.enabled())
                    throw new IllegalArgumentException("The bass Low-pass and output Limiter must remain enabled");
            }
        }
        Set<String> required = template.connections().stream().map(GraphVibroInvariant::signal).collect(Collectors.toSet());
        Set<String> current = graph.connections().stream().map(GraphVibroInvariant::signal).collect(Collectors.toSet());
        if (!required.equals(current) || graph.connections().size() != template.connections().size())
            throw new IllegalArgumentException("The shared Vibro topology is fixed: Main copy → Low-pass, EVENT bypass → mixer → gain → limiter → output");
    }

    private static Set<String> ports(GraphNode node) {
        return node.ports().stream().map(port -> port.id() + ":" + port.direction() + ":"
                + String.join(",", port.channels()) + ":" + port.acceptsMultiple()).collect(Collectors.toSet());
    }

    private static String signal(GraphConnection edge) {
        return edge.fromNode() + ":" + edge.fromPort() + "→" + edge.toNode() + ":" + edge.toPort()
                + ":" + edge.enabled() + ":" + edge.gain();
    }
}
