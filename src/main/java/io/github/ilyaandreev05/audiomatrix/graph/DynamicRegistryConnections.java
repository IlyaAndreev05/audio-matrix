package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DynamicRegistryConnections {
    private DynamicRegistryConnections() {}

    static List<Target> targets(AudioGraph graph, DynamicGeneratorNode generator) {
        Map<String, Object> configured = generator.connectTo();
        String nodeId = DynamicRegistryValues.text(configured.get("node_id"));
        String portId = DynamicRegistryValues.text(configured.get("port_id"));
        if (!nodeId.isBlank() && !portId.isBlank()) return List.of(new Target(nodeId, portId));
        return graph.connections().stream().filter(edge -> edge.fromNode().equals(generator.id()))
                .map(edge -> new Target(edge.toNode(), edge.toPort())).distinct().toList();
    }

    /** The channel layout of the anchor the generated instance feeds. */
    static List<String> anchorChannels(AudioGraph graph, List<Target> targets) {
        for (Target target : targets) {
            for (GraphNode node : graph.nodes()) if (target.nodeId().equals(node.id())) {
                List<String> channels = node.inputs().isEmpty() ? List.of() : node.inputs().getFirst().channels();
                if (!channels.isEmpty()) return channels;
            }
        }
        return List.of();
    }

    static void addBoundaryConnections(DynamicGeneratorNode generator, AudioGraph template, String instanceKey,
            Map<String, String> ids, List<Target> targets, DynamicInstanceRegistry.InstanceSnapshot observation,
            boolean bindingAvailable, List<GraphNode> graphNodes, List<GraphConnection> result) {
        if (targets.isEmpty()) return;
        boolean outputRegistry = "outputs".equals(generator.registry());
        Set<String> connectedInputs = new LinkedHashSet<>();
        Set<String> connectedOutputs = new LinkedHashSet<>();
        template.connections().stream().filter(GraphConnection::enabled).forEach(edge -> {
            connectedInputs.add(edge.toNode() + "\u0000" + edge.toPort());
            connectedOutputs.add(edge.fromNode() + "\u0000" + edge.fromPort());
        });
        Map<String, GraphNode> graphNodesById = new LinkedHashMap<>();
        graphNodes.forEach(node -> graphNodesById.put(node.id(), node));
        Map<String, String> mixerInputs = !outputRegistry && "applications".equals(generator.registry())
                && hasUnconnectedOutputs(template, connectedOutputs)
                ? generatedMixerInputs(generator, instanceKey, targets, graphNodesById, graphNodes) : Map.of();
        String fingerprint = outputRegistry
                ? DynamicRegistryIdentity.outputFingerprint(observation, instanceKey) : "";
        String outputPort = fingerprint.isBlank() ? "" : DynamicRegistryIdentity.stableOutputPortId(generator.id(), fingerprint);
        boolean selectedDirectOutput = observation != null && observation.confidence() == DynamicInstanceRegistry.Confidence.HIGH
                && DynamicRegistryIdentity.identityReliable(observation)
                && "device:role:haptic_output".equals(generator.selector())
                && DynamicRegistrySnapshotMatcher.matchesOutputSelector(generator.selector(), observation);
        for (GraphNode node : template.nodes()) {
            if (outputRegistry) {
                addOutputRegistryConnections(generator, instanceKey, node, ids, targets, graphNodesById, outputPort,
                        bindingAvailable, selectedDirectOutput, connectedInputs, result);
            } else {
                addGeneratedSourceConnections(generator, instanceKey, node, ids, targets, mixerInputs,
                        bindingAvailable, connectedOutputs, result);
            }
        }
    }

    private static Map<String, String> generatedMixerInputs(DynamicGeneratorNode generator, String instanceKey,
            List<Target> targets, Map<String, GraphNode> graphNodesById, List<GraphNode> graphNodes) {
        if (!"applications".equals(generator.registry())) return Map.of();
        Map<String, String> result = null;
        for (Target target : targets) {
            GraphNode anchor = graphNodesById.get(target.nodeId());
            if (!(anchor instanceof MixerNode mixer)) continue;
            String portId = DynamicRegistryIdentity.stableMixerInputPortId(generator.id(), instanceKey);
            if (!mixer.inputGains().containsKey(portId)) {
                Map<String, Double> gains = new LinkedHashMap<>(mixer.inputGains());
                gains.put(portId, 1.0);
                MixerNode updated = new MixerNode(mixer.id(), mixer.name(), mixer.inputs().getFirst().channels(),
                        gains, mixer.inputMutes());
                replace(graphNodes, mixer.id(), updated);
                graphNodesById.put(mixer.id(), updated);
            }
            if (result == null) result = new HashMap<>();
            result.put(targetKey(target), portId);
        }
        return result == null ? Map.of() : result;
    }
    private static boolean hasUnconnectedOutputs(AudioGraph template, Set<String> connectedOutputs) {
        for (GraphNode node : template.nodes())
            for (AudioPort output : node.outputs())
                if (!connectedOutputs.contains(node.id() + "\u0000" + output.id())) return true;
        return false;
    }

    private static void addOutputRegistryConnections(DynamicGeneratorNode generator, String instanceKey,
            GraphNode node, Map<String, String> ids, List<Target> targets, Map<String, GraphNode> graphNodesById,
            String outputPort, boolean bindingAvailable, boolean selectedDirectOutput, Set<String> connectedInputs,
            List<GraphConnection> result) {
        for (AudioPort input : node.inputs()) {
            if (connectedInputs.contains(node.id() + "\u0000" + input.id())) continue;
            for (Target target : targets) {
                GraphNode anchor = graphNodesById.get(target.nodeId());
                if (anchor instanceof SwitchNode) {
                    if (outputPort.isBlank()) continue;
                    result.add(new GraphConnection(DynamicRegistryIdentity.stableConnectionId(generator.id(), instanceKey,
                            "switch-output:" + outputPort + ":" + node.id() + ":" + input.id()), anchor.id(), outputPort,
                            ids.get(node.id()), input.id(), bindingAvailable, 1));
                } else {
                    if (!selectedDirectOutput || !hapticLimiterOutput(generator, target, anchor)) continue;
                    AudioPort sourcePort = outputPort(anchor, target.portId());
                    if (sourcePort == null) continue;
                    result.add(new GraphConnection(DynamicRegistryIdentity.stableConnectionId(generator.id(), instanceKey,
                            "direct-output:" + anchor.id() + ":" + sourcePort.id() + ":" + node.id() + ":" + input.id()),
                            anchor.id(), sourcePort.id(), ids.get(node.id()), input.id(), bindingAvailable, 1));
                }
            }
        }
    }

    private static void addGeneratedSourceConnections(DynamicGeneratorNode generator, String instanceKey,
            GraphNode node, Map<String, String> ids, List<Target> targets, Map<String, String> mixerInputs,
            boolean bindingAvailable, Set<String> connectedOutputs, List<GraphConnection> result) {
        for (AudioPort output : node.outputs()) {
            if (connectedOutputs.contains(node.id() + "\u0000" + output.id())) continue;
            Set<String> targetPorts = targets.size() > 1 ? new LinkedHashSet<>() : null;
            for (Target target : targets) {
                String targetPort = mixerInputs.getOrDefault(targetKey(target), target.portId());
                if (targetPorts != null && !targetPorts.add(target.nodeId() + "\u0000" + targetPort)) continue;
                result.add(new GraphConnection(DynamicRegistryIdentity.stableConnectionId(generator.id(), instanceKey,
                        "target-out:" + node.id() + ":" + output.id() + ":" + target.nodeId() + ":" + targetPort),
                        ids.get(node.id()), output.id(), target.nodeId(), targetPort, bindingAvailable, 1));
            }
        }
    }

    private static boolean hapticLimiterOutput(DynamicGeneratorNode generator, Target target, GraphNode anchor) {
        return "device:role:haptic_output".equals(generator.selector())
                && "octine-vibro-limiter".equals(target.nodeId()) && "out".equals(target.portId())
                && anchor instanceof FilterNode filter && "limiter".equals(filter.processor());
    }
    private static String targetKey(Target target) { return target.nodeId() + "\u0000" + target.portId(); }
    private static AudioPort outputPort(GraphNode node, String portId) {
        if (node == null) return null;
        return node.outputs().stream().filter(port -> port.id().equals(portId)).findFirst().orElse(null);
    }
    private static void replace(List<GraphNode> nodes, String id, GraphNode replacement) {
        for (int i = 0; i < nodes.size(); i++) if (id.equals(nodes.get(i).id())) {
            nodes.set(i, replacement);
            return;
        }
    }
    record Target(String nodeId, String portId) {}
}
