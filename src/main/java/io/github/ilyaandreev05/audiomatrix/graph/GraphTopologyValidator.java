package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GraphTopologyValidator {
    private GraphTopologyValidator() {}

    static List<String> validate(AudioGraph graph) {
        return validate(graph, Map.of());
    }

    static List<String> validate(AudioGraph graph,
                                 Map<DynamicInstanceRegistry.InstanceAddress,
                                 DynamicInstanceRegistry.RuntimeInstance> runtimeInstances) {
        List<String> errors = new ArrayList<>();
        Map<String, GraphNode> nodes = new HashMap<>();
        for (GraphNode node : graph.nodes()) {
            if (nodes.putIfAbsent(node.id(), node) != null) errors.add("Duplicate node id: " + node.id());
        }
        DynamicGeneratorValidator.validateGenerators(graph, nodes, runtimeInstances, errors);
        OutputSelectorValidator.validatePartitions(graph.nodes(), errors);

        Set<String> connectionIds = new HashSet<>();
        Set<String> enabledEndpointPairs = new HashSet<>();
        Set<String> occupiedInputs = new HashSet<>();
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (GraphConnection edge : graph.connections()) {
            if (!connectionIds.add(edge.id())) errors.add("Duplicate connection id: " + edge.id());
            GraphNode from = nodes.get(edge.fromNode()), to = nodes.get(edge.toNode());
            if (from instanceof DynamicGeneratorNode) continue;
            if (to instanceof DynamicGeneratorNode) {
                errors.add("Connection " + edge.id() + " targets a dynamic generator with no PCM input ports");
                continue;
            }
            if (edge.gain() != 1.0)
                errors.add("Connection " + edge.id() + " has a level; insert a Gain node instead");
            if (edge.enabled()) {
                String endpoints = edge.fromNode() + "." + edge.fromPort() + "->" + edge.toNode() + "." + edge.toPort();
                if (!enabledEndpointPairs.add(endpoints)) errors.add("Duplicate enabled connection: " + endpoints);
            }
            if (from == null || to == null) {
                errors.add("Connection " + edge.id() + " references a missing node");
                continue;
            }
            AudioPort source = GraphValidationSupport.port(from, edge.fromPort());
            AudioPort target = GraphValidationSupport.port(to, edge.toPort());
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
                errors.add("Channel layout mismatch on connection " + edge.id() + ": "
                        + source.channels() + " → " + target.channels());
            if (edge.enabled()) adjacency.computeIfAbsent(edge.fromNode(), ignored -> new HashSet<>()).add(edge.toNode());
        }
        for (GraphNode node : graph.nodes()) if (node instanceof DeviceSlotNode) {
            long outputs = graph.connections().stream().filter(GraphConnection::enabled)
                    .filter(edge -> edge.fromNode().equals(node.id())).count();
            if (outputs > 1) errors.add("Device Slot " + node.id() + " accepts one logical output");
        }
        if (GraphFeedbackCycle.contains(nodes.keySet(), adjacency)) errors.add("Graph contains a feedback cycle");
        return List.copyOf(errors);
    }
}
