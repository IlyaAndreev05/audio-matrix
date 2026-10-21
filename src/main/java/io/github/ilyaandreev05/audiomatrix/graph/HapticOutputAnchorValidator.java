package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.UnifiedGraphConfig;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class HapticOutputAnchorValidator {
    private static final String SELECTOR = "device:role:haptic_output";

    private HapticOutputAnchorValidator() {}

    static boolean isHapticOutput(DynamicGeneratorNode generator, String registry) {
        return "outputs".equals(registry) && SELECTOR.equals(generator.selector());
    }

    static void validate(DynamicGeneratorNode generator, AudioGraph graph, Map<String, GraphNode> nodes,
                         DynamicGeneratorTemplateValidator.Boundary boundary, AudioGraph template,
                         String nodeId, String portId,
                         Map<DynamicInstanceRegistry.InstanceAddress, DynamicInstanceRegistry.RuntimeInstance> runtimeInstances,
                         List<String> errors) {
        if (!UnifiedGraphConfig.LIMITER_ID.equals(nodeId)) {
            errors.add("Haptic output generator " + generator.id() + " must connect_to the isolated final limiter");
            return;
        }
        GraphNode candidate = nodes.get(nodeId);
        if (!(candidate instanceof FilterNode limiter) || !"limiter".equals(limiter.processor())
                || !limiter.enabled()) {
            errors.add("Haptic output generator " + generator.id()
                    + " connect_to must target the enabled final limiter " + UnifiedGraphConfig.LIMITER_ID);
            return;
        }
        if (!"out".equals(portId)) {
            errors.add("Haptic output generator " + generator.id() + " must connect_to the final limiter out port");
            return;
        }
        AudioPort port = GraphValidationSupport.port(limiter, portId);
        List<String> channels = boundary == null ? generator.channels() : boundary.channels();
        if (port == null || port.direction() != AudioPort.Direction.OUTPUT
                || !generator.channels().equals(port.channels()) || !channels.equals(port.channels())) {
            errors.add("Haptic output generator " + generator.id()
                    + " final limiter output channels must match generator/template channels " + channels);
            return;
        }
        requireSingleRoleGenerator(generator, graph, errors);
        rejectOtherDirectGenerators(generator, graph, limiter.id(), errors);
        rejectUnexpectedLimiterOutputs(generator, graph, limiter, template, runtimeInstances, errors);
        validateIsolatedHapticPath(generator, graph, nodes, limiter, runtimeInstances, errors);
    }

    private static void requireSingleRoleGenerator(DynamicGeneratorNode generator, AudioGraph graph,
                                                   List<String> errors) {
        long count = graph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast)
                .filter(candidate -> isHapticOutput(candidate, candidate.registry())).count();
        if (count != 1)
            errors.add("Graph must contain exactly one haptic-role output generator; found " + count);
    }

    private static void rejectOtherDirectGenerators(DynamicGeneratorNode generator, AudioGraph graph,
                                                    String limiterId, List<String> errors) {
        for (GraphNode node : graph.nodes()) {
            if (!(node instanceof DynamicGeneratorNode other) || other.id().equals(generator.id())
                    || !"outputs".equals(other.registry())) continue;
            Object rawTarget = other.parameters().get("connect_to");
            boolean targetsLimiter = rawTarget instanceof Map<?, ?> target
                    && limiterId.equals(target.get("node_id"));
            targetsLimiter |= graph.connections().stream().anyMatch(edge -> edge.fromNode().equals(other.id())
                    && limiterId.equals(edge.toNode()));
            if (targetsLimiter)
                errors.add("Dynamic output generator " + other.id()
                        + " must not share the isolated haptic limiter anchor");
        }
    }

    private static void rejectUnexpectedLimiterOutputs(DynamicGeneratorNode generator, AudioGraph graph,
                                                       FilterNode limiter, AudioGraph template,
                                                       Map<DynamicInstanceRegistry.InstanceAddress,
                                                       DynamicInstanceRegistry.RuntimeInstance> runtimeInstances,
                                                       List<String> errors) {
        for (GraphConnection edge : graph.connections()) {
            if (limiter.id().equals(edge.fromNode())
                    && !isGeneratedHapticBoundaryEdge(generator, graph, template, limiter, runtimeInstances, edge))
                errors.add("The final haptic limiter may connect only to this generator's generated output boundary");
        }
    }

    private static boolean isGeneratedHapticBoundaryEdge(DynamicGeneratorNode generator, AudioGraph graph,
                                                         AudioGraph template, FilterNode limiter,
                                                         Map<DynamicInstanceRegistry.InstanceAddress,
                                                         DynamicInstanceRegistry.RuntimeInstance> runtimeInstances,
                                                         GraphConnection edge) {
        if (template == null || !limiter.id().equals(edge.fromNode()) || !"out".equals(edge.fromPort())) return false;
        Set<String> connectedInputs = new HashSet<>();
        template.connections().stream().filter(GraphConnection::enabled)
                .forEach(connection -> connectedInputs.add(connection.toNode() + "\u0000" + connection.toPort()));
        for (GraphGeneratedInstances.Instance instance : GraphGeneratedInstances.of(generator, runtimeInstances)) {
            Map<String, String> ids = instance.nodeIds();
            for (GraphNode node : template.nodes()) for (AudioPort input : node.inputs()) {
                if (connectedInputs.contains(node.id() + "\u0000" + input.id())) continue;
                if (!(ids.get(node.id()) instanceof String generatedId) || !generatedId.equals(edge.toNode())
                        || !input.id().equals(edge.toPort())) continue;
                GraphNode materialized = graph.nodes().stream().filter(value -> generatedId.equals(value.id()))
                        .findFirst().orElse(null);
                if (materialized == null || !node.type().equals(materialized.type())) continue;
                String connectionId = DynamicRegistryIdentity.stableConnectionId(generator.id(), instance.key(),
                        "direct-output:" + limiter.id() + ":out:" + node.id() + ":" + input.id());
                if (connectionId.equals(edge.id())) return true;
            }
        }
        return false;
    }

    private static void validateIsolatedHapticPath(DynamicGeneratorNode generator, AudioGraph graph,
                                                   Map<String, GraphNode> nodes, FilterNode limiter,
                                                   Map<DynamicInstanceRegistry.InstanceAddress,
                                                   DynamicInstanceRegistry.RuntimeInstance> runtimeInstances,
                                                   List<String> errors) {
        GraphNode eventSource = nodes.get(UnifiedGraphConfig.EVENT_SOURCE_ID);
        GraphNode eventGain = nodes.get(UnifiedGraphConfig.EVENT_GAIN_ID);
        GraphNode hapticMixer = nodes.get(GraphCommonVibro.VIBRO_MIXER_ID);
        GraphNode totalGain = nodes.get(GraphCommonVibro.VIBRO_GAIN_ID);
        if (!(eventSource instanceof SourceNode source)
                || !EventSoundStudioSource.SELECTOR.equals(source.selector())
                || !(eventGain instanceof GainNode) || !(hapticMixer instanceof MixerNode)
                || !(totalGain instanceof GainNode)) {
            errors.add("Haptic output requires the isolated EVENT source, event gain, haptic mixer, and total gain");
            return;
        }
        if (!singleEnabledConnection(graph, nodes, source.id(), "out", eventGain.id(), "in", generator.channels())
                || !singleEnabledConnection(graph, nodes, eventGain.id(), "out", hapticMixer.id(), "events",
                generator.channels())
                || !singleEnabledConnection(graph, nodes, totalGain.id(), "out", limiter.id(), "in",
                generator.channels())) {
            errors.add("The EVENT path must feed only the haptic mixer and the total haptic gain must feed the final limiter with matching channels");
        }
        Set<String> hapticOutputs = generatedOutputIds(generator, graph, runtimeInstances);
        Set<String> reached = reachableFrom(source.id(), graph, nodes);
        if (!reached.contains(totalGain.id()) || !reached.contains(limiter.id()))
            errors.add("The isolated EVENT branch must reach the haptic total gain and final limiter");
        if (!reachableFrom(hapticMixer.id(), graph, nodes).contains(totalGain.id()))
            errors.add("The haptic mixer must feed the total haptic gain before the final limiter");
        for (GraphNode node : graph.nodes()) {
            if (reached.contains(node.id()) && (node instanceof OutputNode || node instanceof DeviceSlotNode)
                    && !hapticOutputs.contains(node.id())) {
                errors.add("The isolated EVENT path must not reach an ordinary output or device slot");
                break;
            }
        }
    }

    private static boolean singleEnabledConnection(AudioGraph graph, Map<String, GraphNode> nodes,
                                                  String from, String fromPort, String to, String toPort,
                                                  List<String> channels) {
        List<GraphConnection> outgoing = graph.connections().stream().filter(GraphConnection::enabled)
                .filter(edge -> edge.fromNode().equals(from)).toList();
        if (outgoing.size() != 1) return false;
        GraphConnection edge = outgoing.getFirst();
        if (!fromPort.equals(edge.fromPort()) || !to.equals(edge.toNode()) || !toPort.equals(edge.toPort())) return false;
        AudioPort source = GraphValidationSupport.port(nodes.get(from), fromPort);
        AudioPort target = GraphValidationSupport.port(nodes.get(to), toPort);
        return source != null && target != null && source.channels().equals(target.channels())
                && channels.equals(source.channels());
    }

    private static Set<String> generatedOutputIds(DynamicGeneratorNode generator, AudioGraph graph,
                                                  Map<DynamicInstanceRegistry.InstanceAddress,
                                                  DynamicInstanceRegistry.RuntimeInstance> runtimeInstances) {
        Set<String> result = new HashSet<>();
        for (GraphGeneratedInstances.Instance instance : GraphGeneratedInstances.of(generator, runtimeInstances))
            for (String id : instance.nodeIds().values())
                if (graph.nodes().stream().anyMatch(node -> id.equals(node.id()) && node instanceof OutputNode))
                    result.add(id);
        return result;
    }

    private static Set<String> reachableFrom(String start, AudioGraph graph, Map<String, GraphNode> nodes) {
        Set<String> visited = new HashSet<>(), visitedNodes = new HashSet<>();
        ArrayDeque<Position> pending = new ArrayDeque<>();
        pending.add(new Position(start, ""));
        while (!pending.isEmpty()) {
            Position current = pending.removeFirst();
            String address = current.nodeId() + "\u0000" + current.inputPort();
            if (!visited.add(address)) continue;
            visitedNodes.add(current.nodeId());
            GraphNode node = nodes.get(current.nodeId());
            if (node == null) continue;
            for (GraphConnection edge : graph.connections()) {
                if (!edge.enabled() || !edge.fromNode().equals(node.id())) continue;
                if (node instanceof RouterNode router
                        && !router.activeLinks().contains(current.inputPort() + "->" + edge.fromPort())) continue;
                pending.addLast(new Position(edge.toNode(), edge.toPort()));
            }
        }
        return visitedNodes;
    }

    private record Position(String nodeId, String inputPort) {}
}
