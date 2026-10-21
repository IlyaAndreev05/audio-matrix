package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure graph checks; validation never creates PipeWire nodes or plays audio. */
public final class GraphValidator {
    private GraphValidator() {}

    public static List<String> validate(AudioGraph graph) {
        List<String> errors = new ArrayList<>();
        Map<String, GraphNode> nodes = new HashMap<>();
        for (GraphNode node : graph.nodes()) {
            if (nodes.putIfAbsent(node.id(), node) != null) errors.add("Duplicate node id: " + node.id());
        }
        Set<String> generatedNodeIds = new HashSet<>(), generatedCompositeIds = new HashSet<>();
        for (GraphNode node : graph.nodes()) if (node instanceof DynamicGeneratorNode generator)
            validateGenerator(generator, graph, nodes, generatedNodeIds, generatedCompositeIds, errors);
        validateOutputSelectorPartitions(graph.nodes(), errors);

        Set<String> connectionIds = new HashSet<>();
        Set<String> enabledEndpointPairs = new HashSet<>();
        Set<String> occupiedInputs = new HashSet<>();
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (GraphConnection edge : graph.connections()) {
            if (!connectionIds.add(edge.id())) errors.add("Duplicate connection id: " + edge.id());
            GraphNode from = nodes.get(edge.fromNode()), to = nodes.get(edge.toNode());
            if (from instanceof DynamicGeneratorNode) {
                if (to == null) errors.add("Connection " + edge.id() + " references a missing node");
                else {
                    AudioPort target = port(to, edge.toPort());
                    if (target == null || target.direction() != AudioPort.Direction.INPUT)
                        errors.add("Legacy generator connection " + edge.id() + " has no input port "
                                + edge.toNode() + "." + edge.toPort());
                }
                // This is a legacy control relation, not a PCM edge from the generator.
                continue;
            }
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
            AudioPort source = port(from, edge.fromPort());
            AudioPort target = port(to, edge.toPort());
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
        if (hasCycle(nodes.keySet(), adjacency)) errors.add("Graph contains a feedback cycle");
        return List.copyOf(errors);
    }

    private static void validateGenerator(DynamicGeneratorNode generator, AudioGraph graph,
                                          Map<String, GraphNode> graphNodes, Set<String> generatedNodeIds,
                                          Set<String> generatedCompositeIds, List<String> errors) {
        Map<String, Object> parameters = generator.parameters();
        Object rawRegistry = parameters.get("registry"), rawTemplate = parameters.get("template");
        String registry = rawRegistry instanceof String value ? value : "";
        String templateName = rawTemplate instanceof String value ? value : "";
        if (!Set.of("applications", "outputs", "browser_tabs").contains(registry))
            errors.add("Dynamic generator " + generator.id() + " has an unknown registry: " + rawRegistry);
        if (!DynamicGeneratorNode.compatibleTemplate(registry, templateName))
            errors.add("Dynamic generator " + generator.id() + " has an incompatible template " + templateName
                    + " for registry " + registry);
        if (Set.of("applications", "outputs", "browser_tabs").contains(registry)
                && !supportedSelector(registry, generator.selector()))
            errors.add("Dynamic generator " + generator.id() + " has an unsupported selector " + generator.selector());
        Object initialGain = parameters.get("initial_gain");
        if (!(initialGain instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < 0.0 || number.doubleValue() > 1.0)
            errors.add("Dynamic generator " + generator.id() + " initial_gain must be between 0 and 1");
        requireBoolean(parameters, "auto_pin", generator.id(), errors);
        requireBoolean(parameters, "retain_on_disappear", generator.id(), errors);
        requireBoolean(parameters, "restore_on_reappear", generator.id(), errors);
        if (parameters.containsKey("auto_select_trusted_hdmi_on_first_discovery")) {
            requireBoolean(parameters, "auto_select_trusted_hdmi_on_first_discovery", generator.id(), errors);
            if (!"outputs".equals(registry))
                errors.add("Dynamic generator " + generator.id()
                        + " auto_select_trusted_hdmi_on_first_discovery is only valid for outputs");
        }
        if (!(parameters.get("group_id") instanceof String))
            errors.add("Dynamic generator " + generator.id() + " group_id must be a string");
        if (!(parameters.get("excluded_selectors") instanceof List<?> selectors)
                || selectors.stream().anyMatch(value -> !(value instanceof String selector) || selector.isBlank()))
            errors.add("Dynamic generator " + generator.id() + " excluded_selectors must contain nonblank strings");

        AudioGraph templateGraph = decodeTemplate(generator, errors);
        TemplateBoundary boundary = null;
        if (templateGraph != null) {
            if (templateGraph.nodes().stream().anyMatch(DynamicGeneratorNode.class::isInstance))
                errors.add("Dynamic generator " + generator.id() + " template must not contain another generator");
            else for (String problem : validate(templateGraph))
                errors.add("Dynamic generator " + generator.id() + " template: " + problem);
            boundary = validateTemplateShape(generator, registry, templateGraph, errors);
            if (boundary != null && !boundary.channels().equals(generator.channels()))
                errors.add("Dynamic generator " + generator.id() + " channels do not match its template boundary");
        }
        validateConnectTo(generator, graph, graphNodes, boundary, registry, errors);
        validateInstances(generator, templateGraph, generatedNodeIds, generatedCompositeIds, errors);
    }

    private static boolean supportedSelector(String registry, String selector) {
        return switch (registry) {
            case "applications" -> "application:unassigned".equals(selector) || "application:*".equals(selector)
                    || (selector.startsWith("application:") && !selector.substring("application:".length()).isBlank())
                    || (selector.startsWith("process:") && !selector.substring("process:".length()).isBlank());
            case "outputs" -> selector.startsWith("device:") && selector.length() > "device:".length();
            case "browser_tabs" -> "browser:*".equals(selector) || "browser_tabs:*".equals(selector)
                    || (selector.startsWith("browser:") && !selector.substring("browser:".length()).isBlank());
            default -> false;
        };
    }

    private static void validateOutputSelectorPartitions(List<GraphNode> nodes, List<String> errors) {
        List<DynamicGeneratorNode> outputs = nodes.stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).filter(node -> "outputs".equals(node.registry())).toList();
        for (DynamicGeneratorNode generator : outputs) {
            String selector = generator.selector();
            if (!selector.startsWith("device:") || selector.length() == "device:".length())
                errors.add("Dynamic output generator " + generator.id() + " selector must be an exact device: identity or device:*");
        }
        for (int first = 0; first < outputs.size(); first++) for (int second = first + 1; second < outputs.size(); second++) {
            DynamicGeneratorNode left = outputs.get(first), right = outputs.get(second);
            String overlap = overlappingOutputSelector(left.selector(), right.selector());
            if (overlap == null || excludesOutputSelector(left, overlap) || excludesOutputSelector(right, overlap)) continue;
            errors.add("Dynamic output generators " + left.id() + " and " + right.id()
                    + " overlap for selector " + overlap + "; add an explicit excluded_selector");
        }
    }

    private static String overlappingOutputSelector(String left, String right) {
        if (!left.startsWith("device:") || !right.startsWith("device:")) return null;
        boolean leftWildcard = "device:*".equals(left) || "device:all".equals(left);
        boolean rightWildcard = "device:*".equals(right) || "device:all".equals(right);
        if (leftWildcard && rightWildcard) return "device:*";
        if (leftWildcard) return right;
        if (rightWildcard) return left;
        return left.equals(right) ? left : null;
    }

    private static boolean excludesOutputSelector(DynamicGeneratorNode generator, String selector) {
        return generator.excludedSelectors().contains(selector)
                || generator.excludedSelectors().contains("device:*")
                || generator.excludedSelectors().contains("device:all");
    }

    private static AudioGraph decodeTemplate(DynamicGeneratorNode generator, List<String> errors) {
        Object rawNodes = generator.parameters().get("template_nodes");
        Object rawConnections = generator.parameters().get("template_connections");
        if (!(rawNodes instanceof List<?> nodeValues)) {
            errors.add("Dynamic generator " + generator.id() + " template_nodes must be an array");
            return null;
        }
        if (!(rawConnections instanceof List<?> connectionValues)) {
            errors.add("Dynamic generator " + generator.id() + " template_connections must be an array");
            return null;
        }
        if (nodeValues.stream().anyMatch(value -> !(value instanceof Map<?, ?>)))
            errors.add("Dynamic generator " + generator.id() + " template_nodes entries must be objects");
        if (connectionValues.stream().anyMatch(value -> !(value instanceof Map<?, ?>)))
            errors.add("Dynamic generator " + generator.id() + " template_connections entries must be objects");
        if (nodeValues.stream().anyMatch(value -> !(value instanceof Map<?, ?>))
                || connectionValues.stream().anyMatch(value -> !(value instanceof Map<?, ?>))) return null;
        List<Map<String, Object>> nodes = nodeValues.stream().map(value -> stringMap((Map<?, ?>) value)).toList();
        List<Map<String, Object>> connections = connectionValues.stream()
                .map(value -> stringMap((Map<?, ?>) value)).toList();
        Map<String, Object> encoded = new HashMap<>();
        encoded.put("id", "template-" + generator.id());
        encoded.put("name", generator.name() + " template");
        encoded.put("nodes", nodes);
        encoded.put("connections", connections);
        try {
            return GraphCodec.fromMap(encoded);
        } catch (RuntimeException invalid) {
            errors.add("Dynamic generator " + generator.id() + " has an invalid template: " + invalid.getMessage());
            return null;
        }
    }

    private static TemplateBoundary validateTemplateShape(DynamicGeneratorNode generator, String registry,
                                                           AudioGraph template, List<String> errors) {
        if (!DynamicGeneratorNode.compatibleTemplate(registry, generator.template())) return null;
        List<SourceNode> sources = template.nodes().stream().filter(SourceNode.class::isInstance)
                .map(SourceNode.class::cast).toList();
        List<GainNode> gains = template.nodes().stream().filter(GainNode.class::isInstance)
                .map(GainNode.class::cast).toList();
        List<OutputNode> outputs = template.nodes().stream().filter(OutputNode.class::isInstance)
                .map(OutputNode.class::cast).toList();
        if ("applications".equals(registry) || "browser_tabs".equals(registry)) {
            if (sources.size() != 1 || gains.size() != 1 || !outputs.isEmpty()) {
                errors.add("Dynamic generator " + generator.id()
                        + " application and browser-tab templates require one Source and one Gain, without Output");
                return null;
            }
            List<GraphNode> terminals = template.nodes().stream()
                    .filter(node -> !node.outputs().isEmpty())
                    .filter(node -> template.connections().stream().noneMatch(edge -> edge.fromNode().equals(node.id())))
                    .toList();
            if (terminals.size() != 1 || terminals.getFirst().outputs().size() != 1
                    || !allNodesBetween(template, sources.getFirst().id(), terminals.getFirst().id())) {
                errors.add("Dynamic generator " + generator.id()
                        + " source template must have one connected output boundary");
                return null;
            }
            return new TemplateBoundary(terminals.getFirst().outputs().getFirst().channels(),
                    AudioPort.Direction.INPUT);
        }
        if ("outputs".equals(registry)) {
            if (!sources.isEmpty() || gains.size() != 1 || outputs.size() != 1) {
                errors.add("Dynamic output templates require one Gain and one Output, without Source");
                return null;
            }
            List<GraphNode> roots = template.nodes().stream()
                    .filter(node -> !node.inputs().isEmpty())
                    .filter(node -> template.connections().stream().noneMatch(edge -> edge.toNode().equals(node.id())))
                    .toList();
            if (roots.size() != 1 || roots.getFirst().inputs().size() != 1
                    || !allNodesBetween(template, roots.getFirst().id(), outputs.getFirst().id())) {
                errors.add("Dynamic output template must have one connected input boundary");
                return null;
            }
            return new TemplateBoundary(roots.getFirst().inputs().getFirst().channels(),
                    AudioPort.Direction.INPUT);
        }
        return null;
    }

    private static boolean allNodesBetween(AudioGraph graph, String start, String end) {
        Map<String, Set<String>> forward = new HashMap<>(), reverse = new HashMap<>();
        for (GraphConnection edge : graph.connections()) {
            if (!edge.enabled()) continue;
            forward.computeIfAbsent(edge.fromNode(), ignored -> new HashSet<>()).add(edge.toNode());
            reverse.computeIfAbsent(edge.toNode(), ignored -> new HashSet<>()).add(edge.fromNode());
        }
        Set<String> fromStart = reachable(start, forward), toEnd = reachable(end, reverse);
        return fromStart.contains(end) && graph.nodes().stream()
                .allMatch(node -> fromStart.contains(node.id()) && toEnd.contains(node.id()));
    }

    private static Set<String> reachable(String start, Map<String, Set<String>> adjacency) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current)) continue;
            for (String next : adjacency.getOrDefault(current, Set.of())) pending.addLast(next);
        }
        return visited;
    }

    private static void validateConnectTo(DynamicGeneratorNode generator, AudioGraph graph,
                                          Map<String, GraphNode> nodes, TemplateBoundary boundary,
                                          String registry, List<String> errors) {
        Object raw = generator.parameters().get("connect_to");
        if (!generator.parameters().containsKey("connect_to")) {
            GraphConnection legacy = graph.connections().stream()
                    .filter(edge -> edge.fromNode().equals(generator.id())).findFirst().orElse(null);
            if (legacy == null) return;
            raw = Map.of("node_id", legacy.toNode(), "port_id", legacy.toPort());
        }
        if (!(raw instanceof Map<?, ?> connectTo)) {
            errors.add("Dynamic generator " + generator.id() + " connect_to must be an object");
            return;
        }
        Object rawNodeId = connectTo.get("node_id"), rawPortId = connectTo.get("port_id");
        if (!(rawNodeId instanceof String nodeId) || nodeId.isBlank()
                || !(rawPortId instanceof String portId) || portId.isBlank()) {
            errors.add("Dynamic generator " + generator.id() + " connect_to needs node_id and port_id");
            return;
        }
        GraphNode target = nodes.get(nodeId);
        if (target == null) {
            errors.add("Dynamic generator " + generator.id() + " connect_to references missing node " + nodeId);
            return;
        }
        AudioPort port = port(target, portId);
        AudioPort.Direction expected = "outputs".equals(registry)
                ? target instanceof SwitchNode ? AudioPort.Direction.INPUT : AudioPort.Direction.OUTPUT
                : boundary == null ? AudioPort.Direction.INPUT : boundary.direction();
        if (port == null || port.direction() != expected) {
            errors.add("Dynamic generator " + generator.id() + " connect_to has no "
                    + expected.name().toLowerCase() + " port " + nodeId + "." + portId);
            return;
        }
        if (boundary != null && !boundary.channels().equals(port.channels()))
            errors.add("Dynamic generator " + generator.id() + " connect_to channel layout does not match");
    }

    private static void validateInstances(DynamicGeneratorNode generator, AudioGraph template,
                                          Set<String> nodeIds, Set<String> compositeIds,
                                          List<String> errors) {
        Object rawInstances = generator.parameters().get("instances");
        if (!(rawInstances instanceof Map<?, ?> instances)) {
            errors.add("Dynamic generator " + generator.id() + " instances must be an object");
            return;
        }
        Set<String> roles = new LinkedHashSet<>();
        if (template != null) template.nodes().forEach(node -> roles.add(node.id()));
        for (Map.Entry<?, ?> entry : instances.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                errors.add("Dynamic generator " + generator.id() + " has a blank instance key");
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?> instance)) {
                errors.add("Dynamic generator " + generator.id() + " instance " + key + " must be an object");
                continue;
            }
            if (!(instance.get("node_ids") instanceof Map<?, ?> encodedNodeIds)) {
                errors.add("Dynamic generator " + generator.id() + " instance " + key + " node_ids must be an object");
            } else {
                Set<String> instanceIds = new HashSet<>();
                for (String role : roles) {
                    if (!(encodedNodeIds.get(role) instanceof String value) || value.isBlank())
                        errors.add("Dynamic generator " + generator.id() + " instance " + key
                                + " is missing node id for template role " + role);
                }
                for (Map.Entry<?, ?> nodeEntry : encodedNodeIds.entrySet()) {
                    if (!(nodeEntry.getKey() instanceof String role) || role.isBlank()
                            || !(nodeEntry.getValue() instanceof String value) || value.isBlank()) {
                        errors.add("Dynamic generator " + generator.id() + " instance " + key
                                + " has an invalid node_ids entry");
                        continue;
                    }
                    if (!instanceIds.add(value) || !nodeIds.add(value))
                        errors.add("Dynamic generator " + generator.id() + " has duplicate generated node id " + value);
                }
            }
            Object rawComposite = instance.get("composite_id");
            if (!(rawComposite instanceof String compositeId) || compositeId.isBlank())
                errors.add("Dynamic generator " + generator.id() + " instance " + key + " composite_id must be nonblank");
            else if (!compositeIds.add(compositeId))
                errors.add("Dynamic generator " + generator.id() + " has duplicate composite id " + compositeId);
            if (!(instance.get("overrides") instanceof Map<?, ?> overrides)) {
                errors.add("Dynamic generator " + generator.id() + " instance " + key + " overrides must be an object");
            } else validateOverrides(generator, key, overrides, errors);
        }
    }

    private static void validateOverrides(DynamicGeneratorNode generator, String key, Map<?, ?> overrides,
                                          List<String> errors) {
        for (Map.Entry<?, ?> entry : overrides.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            boolean valid = switch (name) {
                case "gain" -> value instanceof Number number && Double.isFinite(number.doubleValue())
                        && number.doubleValue() >= 0.0 && number.doubleValue() <= 1.0;
                case "muted", "pinned", "expanded", "show_children", "show_when_offline" -> value instanceof Boolean;
                case "display_name", "icon", "group_id" -> value instanceof String;
                case "order" -> value instanceof Number number && Double.isFinite(number.doubleValue())
                        && number.doubleValue() == Math.rint(number.doubleValue());
                default -> true;
            };
            if (!valid) errors.add("Dynamic generator " + generator.id() + " instance " + key
                    + " has an invalid " + name + " override");
        }
    }

    private static void requireBoolean(Map<String, Object> parameters, String name, String generatorId,
                                       List<String> errors) {
        if (!(parameters.get(name) instanceof Boolean))
            errors.add("Dynamic generator " + generatorId + " " + name + " must be boolean");
    }

    private static AudioPort port(GraphNode node, String id) {
        return node.ports().stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
    }

    private static Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new HashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static boolean hasCycle(Set<String> nodes, Map<String, Set<String>> adjacency) {
        Set<String> visiting = new HashSet<>(), visited = new HashSet<>();
        for (String node : nodes) if (cycle(node, adjacency, visiting, visited)) return true;
        return false;
    }

    private static boolean cycle(String node, Map<String, Set<String>> adjacency,
                                 Set<String> visiting, Set<String> visited) {
        if (visited.contains(node)) return false;
        if (!visiting.add(node)) return true;
        for (String next : adjacency.getOrDefault(node, Set.of()))
            if (cycle(next, adjacency, visiting, visited)) return true;
        visiting.remove(node);
        visited.add(node);
        return false;
    }

    private record TemplateBoundary(List<String> channels, AudioPort.Direction direction) {}
}
