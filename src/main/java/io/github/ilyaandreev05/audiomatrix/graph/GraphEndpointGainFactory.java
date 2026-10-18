package io.github.ilyaandreev05.audiomatrix.graph;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Creates endpoint Gain nodes and migrates legacy connection levels into explicit Gain nodes. */
public final class GraphEndpointGainFactory {
    private GraphEndpointGainFactory() {}

    public static AudioGraph ensure(AudioGraph graph) {
        graph = normalizeConnectionGains(graph);
        List<GraphNode> nodes = new ArrayList<>(graph.nodes());
        List<GraphConnection> links = new ArrayList<>(graph.connections());
        for (GraphNode endpoint : List.copyOf(nodes)) {
            if (!(endpoint instanceof SourceNode) && !(endpoint instanceof OutputNode)) continue;
            if (gainFor(endpoint.id(), nodes) != null) continue;
            GainNode gain = create(graph, endpoint, nodes);
            nodes.add(gain);
            if (endpoint instanceof SourceNode) {
                redirectSource(links, endpoint.id(), gain.id());
                links.add(connection(graph.id(), "in", endpoint.id(), "out", gain.id(), "in"));
            } else {
                redirectOutput(links, endpoint.id(), gain.id());
                links.add(connection(graph.id(), "out", gain.id(), "out", endpoint.id(), "in"));
            }
        }
        return new AudioGraph(graph.id(), graph.name(), nodes, links);
    }

    private static AudioGraph normalizeConnectionGains(AudioGraph graph) {
        List<GraphNode> nodes = new ArrayList<>(graph.nodes());
        List<GraphConnection> connections = new ArrayList<>();
        Set<String> nodeIds = new HashSet<>();
        Set<String> connectionIds = new HashSet<>();
        nodes.forEach(node -> nodeIds.add(node.id()));
        graph.connections().forEach(edge -> connectionIds.add(edge.id()));
        for (GraphConnection edge : graph.connections()) {
            if (edge.gain() == 1.0) {
                connections.add(edge);
                continue;
            }
            GraphNode source = nodes.stream().filter(node -> node.id().equals(edge.fromNode())).findFirst().orElse(null);
            AudioPort port = source == null ? null : source.outputs().stream()
                    .filter(candidate -> candidate.id().equals(edge.fromPort())).findFirst().orElse(null);
            if (port == null) {
                connections.add(edge);
                continue;
            }
            String seed = graph.id() + "\0" + edge.id();
            String gainId = uniqueNodeId("connection-gain", seed, nodeIds);
            nodeIds.add(gainId);
            nodes.add(new GainNode(gainId, "Gain · " + edge.fromNode() + " → " + edge.toNode(),
                    port.channels(), edge.gain(), false));
            connections.add(new GraphConnection(edge.id(), edge.fromNode(), edge.fromPort(), gainId,
                    "in", edge.enabled(), 1.0));
            String outputLink = uniqueConnectionId(graph.id(), edge.id(), connectionIds);
            connectionIds.add(outputLink);
            connections.add(new GraphConnection(outputLink, gainId, "out", edge.toNode(), edge.toPort(), true, 1.0));
        }
        return new AudioGraph(graph.id(), graph.name(), nodes, connections);
    }

    private static String uniqueNodeId(String prefix, String seed, Set<String> ids) {
        String id = stableId(prefix, seed);
        for (int suffix = 1; ids.contains(id); suffix++) id = stableId(prefix, seed + "\0" + suffix);
        return id;
    }

    private static String uniqueConnectionId(String graphId, String edgeId, Set<String> ids) {
        String seed = graphId + "\0" + edgeId + "\0gain-out";
        String id = stableId("connection-link", seed);
        for (int suffix = 1; ids.contains(id); suffix++) id = stableId("connection-link", seed + "\0" + suffix);
        return id;
    }

    public static GainNode gainFor(AudioGraph graph, GraphNode endpoint) {
        return gainFor(endpoint.id(), graph.nodes());
    }

    private static GainNode gainFor(String endpointId, List<GraphNode> nodes) {
        return nodes.stream().filter(GainNode.class::isInstance).map(GainNode.class::cast)
                .filter(gain -> endpointId.equals(gain.endpointNodeId())).findFirst().orElse(null);
    }

    private static GainNode create(AudioGraph graph, GraphNode endpoint, List<GraphNode> nodes) {
        String seed = graph.id() + "\0" + endpoint.type() + "\0" + endpoint.id();
        String id = stableId("endpoint-gain", seed);
        int suffix = 1;
        while (contains(nodes, id)) id = stableId("endpoint-gain", seed + "\0" + suffix++);
        String name = endpoint.name() + " · уровень";
        List<String> channels = endpoint.outputs().isEmpty()
                ? endpoint.inputs().getFirst().channels() : endpoint.outputs().getFirst().channels();
        return new GainNode(id, name, channels, 1.0, false, endpoint.id());
    }

    private static GraphConnection connection(String graphId, String kind, String fromNode,
                                              String fromPort, String toNode, String toPort) {
        String id = stableId("endpoint-link", graphId + "\0" + kind + "\0" + fromNode + "\0" + toNode);
        return new GraphConnection(id, fromNode, fromPort, toNode, toPort, true, 1.0);
    }

    private static boolean contains(List<GraphNode> nodes, String id) {
        return nodes.stream().anyMatch(node -> id.equals(node.id()));
    }

    private static void redirectSource(List<GraphConnection> links, String sourceId, String gainId) {
        for (int i = 0; i < links.size(); i++) {
            GraphConnection edge = links.get(i);
            if (sourceId.equals(edge.fromNode())) links.set(i, new GraphConnection(edge.id(), gainId,
                    edge.fromPort(), edge.toNode(), edge.toPort(), edge.enabled(), edge.gain()));
        }
    }

    private static void redirectOutput(List<GraphConnection> links, String outputId, String gainId) {
        for (int i = 0; i < links.size(); i++) {
            GraphConnection edge = links.get(i);
            if (outputId.equals(edge.toNode())) links.set(i, new GraphConnection(edge.id(), edge.fromNode(),
                    edge.fromPort(), gainId, edge.toPort(), edge.enabled(), edge.gain()));
        }
    }

    private static String stableId(String prefix, String seed) {
        return prefix + "-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
