package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;
import java.util.Map;

final class DynamicGeneratorConnectionValidator {
    private DynamicGeneratorConnectionValidator() {}

    static void validate(DynamicGeneratorNode generator, AudioGraph graph, Map<String, GraphNode> nodes,
                         DynamicGeneratorTemplateValidator.Boundary boundary, AudioGraph template,
                         String registry,
                         Map<DynamicInstanceRegistry.InstanceAddress, DynamicInstanceRegistry.RuntimeInstance> runtimeInstances,
                         List<String> errors) {
        List<GraphConnection> legacyEdges = graph.connections().stream()
                .filter(edge -> edge.fromNode().equals(generator.id())).toList();
        boolean explicit = generator.parameters().containsKey("connect_to");
        Target target;
        if (explicit) {
            target = decode(generator, generator.parameters().get("connect_to"), errors);
            if (legacyEdges.size() > 1) {
                errors.add("Dynamic generator " + generator.id() + " may have at most one legacy control connection");
            } else if (legacyEdges.size() == 1 && target != null) {
                GraphConnection legacy = legacyEdges.getFirst();
                if (!target.nodeId().equals(legacy.toNode()) || !target.portId().equals(legacy.toPort()))
                    errors.add("Dynamic generator " + generator.id()
                            + " legacy control connection must match connect_to node_id and port_id");
            }
        } else if (legacyEdges.size() == 1) {
            GraphConnection legacy = legacyEdges.getFirst();
            target = new Target(legacy.toNode(), legacy.toPort());
        } else {
            target = null;
            if (legacyEdges.size() > 1)
                errors.add("Dynamic generator " + generator.id()
                        + " needs exactly one legacy control connection when connect_to is absent");
            else if ("outputs".equals(registry)) {
                errors.add(HapticOutputAnchorValidator.isHapticOutput(generator, registry)
                        ? "Haptic output generator " + generator.id() + " must connect_to the final limiter out port"
                        : "Dynamic output generator " + generator.id() + " must connect_to a SwitchNode input");
            }
        }
        if (target == null) return;
        GraphNode node = nodes.get(target.nodeId());
        if (node == null) {
            errors.add("Dynamic generator " + generator.id() + " connect_to references missing node " + target.nodeId());
            return;
        }
        if (HapticOutputAnchorValidator.isHapticOutput(generator, registry)) {
            HapticOutputAnchorValidator.validate(generator, graph, nodes, boundary, template,
                    target.nodeId(), target.portId(), runtimeInstances, errors);
            return;
        }
        if ("outputs".equals(registry) && !(node instanceof SwitchNode)) {
            errors.add("Dynamic output generator " + generator.id()
                    + " must connect_to a SwitchNode input; RouterNode.activeLinks selects generated outputs");
            return;
        }
        if ("outputs".equals(registry)) {
            long switches = graph.nodes().stream().filter(SwitchNode.class::isInstance).count();
            if (switches != 1) {
                errors.add("Dynamic output generators require the graph's sole SwitchNode input; found "
                        + switches + " SwitchNodes");
                return;
            }
        }
        AudioPort.Direction expected = "outputs".equals(registry) ? AudioPort.Direction.INPUT
                : boundary == null ? AudioPort.Direction.INPUT : boundary.direction();
        AudioPort port = GraphValidationSupport.port(node, target.portId());
        if (port == null || port.direction() != expected) {
            errors.add("Dynamic generator " + generator.id() + " connect_to has no "
                    + expected.name().toLowerCase() + " port " + target.nodeId() + "." + target.portId());
            return;
        }
        List<String> channels = boundary == null ? generator.channels() : boundary.channels();
        if (!channels.equals(port.channels()))
            errors.add("Dynamic generator " + generator.id() + " connect_to channel layout does not match " + channels);
    }

    private static Target decode(DynamicGeneratorNode generator, Object raw, List<String> errors) {
        if (!(raw instanceof Map<?, ?> values)) {
            errors.add("Dynamic generator " + generator.id() + " connect_to must be an object");
            return null;
        }
        if (values.size() != 2 || !values.containsKey("node_id") || !values.containsKey("port_id")) {
            errors.add("Dynamic generator " + generator.id()
                    + " connect_to must contain exactly node_id and port_id");
            return null;
        }
        Object rawNodeId = values.get("node_id"), rawPortId = values.get("port_id");
        if (!(rawNodeId instanceof String nodeId) || nodeId.isBlank()
                || !(rawPortId instanceof String portId) || portId.isBlank()) {
            errors.add("Dynamic generator " + generator.id() + " connect_to needs nonblank node_id and port_id strings");
            return null;
        }
        return new Target(nodeId, portId);
    }

    private record Target(String nodeId, String portId) {}
}
