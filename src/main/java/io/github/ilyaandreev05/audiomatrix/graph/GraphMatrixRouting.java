package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Maps simple source-to-output matrix cells to direct links between endpoint Gain nodes. */
public final class GraphMatrixRouting {
    private GraphMatrixRouting() {}

    public static boolean connected(AudioGraph graph, SourceNode source, OutputNode output) {
        return hasPath(graph, source.id(), output.id(), "");
    }

    public static boolean canToggle(AudioGraph graph, SourceNode source, OutputNode output) {
        GraphConnection direct = directLink(graph, source, output);
        return direct == null ? !hasPath(graph, source.id(), output.id(), "")
                : !hasPath(graph, source.id(), output.id(), direct.id());
    }

    public static AudioGraph setDirect(AudioGraph graph, SourceNode source, OutputNode output, boolean enabled) {
        if (!canToggle(graph, source, output))
            throw new IllegalArgumentException("This cell represents a processed graph path; edit its connections in the graph editor");
        GraphConnection direct = directLink(graph, source, output);
        List<GraphConnection> links = new ArrayList<>(graph.connections());
        if (direct != null) {
            links.set(links.indexOf(direct), new GraphConnection(direct.id(), direct.fromNode(), direct.fromPort(),
                    direct.toNode(), direct.toPort(), enabled, direct.gain()));
        } else if (enabled) {
            GraphNode from = GraphEndpointGainFactory.gainFor(graph, source);
            GraphNode to = GraphEndpointGainFactory.gainFor(graph, output);
            links.add(new GraphConnection("matrix-link-" + UUID.randomUUID(),
                    from == null ? source.id() : from.id(), "out", to == null ? output.id() : to.id(), "in", true, 1));
        }
        return new AudioGraph(graph.id(), graph.name(), graph.nodes(), links);
    }

    private static GraphConnection directLink(AudioGraph graph, SourceNode source, OutputNode output) {
        GraphNode from = GraphEndpointGainFactory.gainFor(graph, source);
        GraphNode to = GraphEndpointGainFactory.gainFor(graph, output);
        String fromId = from == null ? source.id() : from.id();
        String toId = to == null ? output.id() : to.id();
        return graph.connections().stream().filter(edge -> fromId.equals(edge.fromNode())
                && toId.equals(edge.toNode()) && "out".equals(edge.fromPort()) && "in".equals(edge.toPort()))
                .findFirst().orElse(null);
    }

    private static boolean hasPath(AudioGraph graph, String start, String target, String ignoredEdge) {
        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current)) continue;
            for (GraphConnection edge : graph.connections()) {
                if (!edge.enabled() || ignoredEdge.equals(edge.id()) || !current.equals(edge.fromNode())) continue;
                if (target.equals(edge.toNode())) return true;
                pending.addLast(edge.toNode());
            }
        }
        return false;
    }
}
