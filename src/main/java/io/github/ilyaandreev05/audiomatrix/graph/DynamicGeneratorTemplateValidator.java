package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DynamicGeneratorTemplateValidator {
    private DynamicGeneratorTemplateValidator() {}

    static Result validate(DynamicGeneratorNode generator, String registry, List<String> errors) {
        AudioGraph template = decode(generator, errors);
        if (template == null) return new Result(null, null);
        if (GraphFeedbackCycle.contains(template))
            errors.add("Dynamic generator " + generator.id() + " template contains a feedback cycle");
        if (template.nodes().stream().anyMatch(DynamicGeneratorNode.class::isInstance)) {
            errors.add("Dynamic generator " + generator.id() + " template must not contain another generator");
        } else {
            for (String problem : GraphValidator.validate(template))
                errors.add("Dynamic generator " + generator.id() + " template: " + problem);
        }
        Boundary boundary = validateShape(generator, registry, template, errors);
        if (boundary != null && !boundary.channels().equals(generator.channels()))
            errors.add("Dynamic generator " + generator.id() + " channels do not match its template boundary");
        return new Result(template, boundary);
    }

    private static AudioGraph decode(DynamicGeneratorNode generator, List<String> errors) {
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

    private static Boundary validateShape(DynamicGeneratorNode generator, String registry,
                                         AudioGraph template, List<String> errors) {
        if (!DynamicGeneratorNode.compatibleTemplate(registry, generator.template())) return null;
        List<SourceNode> sources = template.nodes().stream().filter(SourceNode.class::isInstance)
                .map(SourceNode.class::cast).toList();
        List<GainNode> gains = template.nodes().stream().filter(GainNode.class::isInstance)
                .map(GainNode.class::cast).toList();
        List<OutputNode> outputs = template.nodes().stream().filter(OutputNode.class::isInstance)
                .map(OutputNode.class::cast).toList();
        if ("applications".equals(registry) || "browser_tabs".equals(registry))
            return validateSourceTemplate(generator, template, sources, gains, outputs, errors);
        if ("outputs".equals(registry))
            return validateOutputTemplate(generator, template, sources, gains, outputs, errors);
        return null;
    }

    private static Boundary validateSourceTemplate(DynamicGeneratorNode generator, AudioGraph template,
                                                    List<SourceNode> sources, List<GainNode> gains,
                                                    List<OutputNode> outputs, List<String> errors) {
        if (sources.size() != 1 || gains.size() != 1 || !outputs.isEmpty()) {
            errors.add("Dynamic generator " + generator.id()
                    + " application and browser-tab templates require one Source and one Gain, without Output");
            return null;
        }
        List<GraphNode> terminals = template.nodes().stream().filter(node -> !node.outputs().isEmpty())
                .filter(node -> template.connections().stream().noneMatch(edge -> edge.fromNode().equals(node.id())))
                .toList();
        if (terminals.size() != 1 || terminals.getFirst().outputs().size() != 1
                || !allNodesBetween(template, sources.getFirst().id(), terminals.getFirst().id())) {
            errors.add("Dynamic generator " + generator.id() + " source template must have one connected output boundary");
            return null;
        }
        return new Boundary(terminals.getFirst().outputs().getFirst().channels(), AudioPort.Direction.INPUT);
    }

    private static Boundary validateOutputTemplate(DynamicGeneratorNode generator, AudioGraph template,
                                                   List<SourceNode> sources, List<GainNode> gains,
                                                   List<OutputNode> outputs, List<String> errors) {
        if (!sources.isEmpty() || gains.size() != 1 || outputs.size() != 1) {
            errors.add("Dynamic output templates require one Gain and one Output, without Source");
            return null;
        }
        List<GraphNode> roots = template.nodes().stream().filter(node -> !node.inputs().isEmpty())
                .filter(node -> template.connections().stream().noneMatch(edge -> edge.toNode().equals(node.id())))
                .toList();
        if (roots.size() != 1 || roots.getFirst().inputs().size() != 1
                || !allNodesBetween(template, roots.getFirst().id(), outputs.getFirst().id())) {
            errors.add("Dynamic output template must have one connected input boundary");
            return null;
        }
        return new Boundary(roots.getFirst().inputs().getFirst().channels(), AudioPort.Direction.INPUT);
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

    private static Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new HashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    record Boundary(List<String> channels, AudioPort.Direction direction) {}
    record Result(AudioGraph graph, Boundary boundary) {}
}
