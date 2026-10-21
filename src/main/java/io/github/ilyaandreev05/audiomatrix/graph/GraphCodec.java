package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable JSON-compatible encoding for saved graph routes. */
public final class GraphCodec {
    private GraphCodec() {}

    public static Map<String, Object> toMap(AudioGraph graph) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (GraphNode node : graph.nodes()) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("id", node.id()); encoded.put("name", node.name()); encoded.put("type", node.type());
            encoded.put("channels", node instanceof DynamicGeneratorNode generator ? generator.channels()
                    : node.outputs().isEmpty() ? node.inputs().getFirst().channels() : node.outputs().getFirst().channels());
            encoded.put("parameters", node.parameters());
            nodes.add(encoded);
        }
        List<Map<String, Object>> edges = graph.connections().stream().map(edge -> Map.<String, Object>of(
                "id", edge.id(), "from_node", edge.fromNode(), "from_port", edge.fromPort(),
                "to_node", edge.toNode(), "to_port", edge.toPort(), "enabled", edge.enabled(), "gain", edge.gain())).toList();
        return Map.of("id", graph.id(), "name", graph.name(), "nodes", nodes, "connections", edges);
    }

    public static AudioGraph fromMap(Map<String, Object> encoded) {
        String id = text(encoded.get("id")), name = text(encoded.get("name"));
        List<GraphNode> nodes = new ArrayList<>();
        for (Map<String, Object> item : maps(encoded.get("nodes"))) {
            String nodeId = text(item.get("id")), nodeName = text(item.get("name")), type = text(item.get("type"));
            List<String> channels = strings(item.get("channels"));
            Map<String, Object> parameters = object(item.get("parameters"));
            nodes.add(switch (type) {
                case "source" -> new SourceNode(nodeId, nodeName, text(parameters.get("selector")), channels);
                case "dynamic_generator" -> {
                    if (!(parameters.get("selector") instanceof String selector))
                        throw new IllegalArgumentException("Dynamic generator selector must be a string");
                    yield new DynamicGeneratorNode(nodeId, nodeName, selector, channels, parameters);
                }
                case "device_slot" -> new DeviceSlotNode(nodeId, nodeName, text(parameters.get("device_id")), channels,
                        number(parameters.get("volume"), 0), Boolean.TRUE.equals(parameters.get("muted")));
                case "output" -> new OutputNode(nodeId, nodeName, text(parameters.get("device_id")), channels,
                        parameters.containsKey("channel_matrix") ? ChannelMatrix.parse(parameters.get("channel_matrix")) : null);
                case "gain" -> new GainNode(nodeId, nodeName, channels, number(parameters.get("gain"), 1),
                        Boolean.TRUE.equals(parameters.get("muted")), text(parameters.get("endpoint_node_id")));
                case "gate" -> new GateNode(nodeId, nodeName, channels, Boolean.TRUE.equals(parameters.getOrDefault("open", Boolean.TRUE)));
                // Both historical types migrate losslessly to the canonical multi-select Switch.
                case "switch", "multi_switch" -> new SwitchNode(nodeId, nodeName, channels, ports(parameters.get("output_ports"),List.of("out")), strings(parameters.get("active_links")));
                case "selector" -> new SelectorNode(nodeId, nodeName, channels, ports(parameters.get("input_ports"),List.of("in")), strings(parameters.get("active_links")));
                case "multi_selector" -> new MultiSelectorNode(nodeId, nodeName, channels, ports(parameters.get("input_ports"),List.of("in")), strings(parameters.get("active_links")));
                case "matrix" -> new MatrixNode(nodeId, nodeName, channels, ports(parameters.get("input_ports"),List.of("in")), ports(parameters.get("output_ports"),List.of("out")), strings(parameters.get("active_links")));
                case "router" -> new RouterNode(nodeId, nodeName, channels, channels,
                        strings(parameters.get("active_links")), Boolean.TRUE.equals(parameters.get("multiple")));
                case "mixer" -> decodeMixer(nodeId, nodeName, channels, parameters);
                case "filter" -> new FilterNode(nodeId, nodeName, channels, text(parameters.get("processor")),
                        !Boolean.FALSE.equals(parameters.get("enabled")), without(parameters, "processor", "enabled"));
                default -> throw new IllegalArgumentException("Unsupported graph node type: " + type);
            });
        }
        List<GraphConnection> edges = new ArrayList<>();
        for (Map<String, Object> item : maps(encoded.get("connections"))) edges.add(new GraphConnection(
                text(item.get("id")), text(item.get("from_node")), text(item.get("from_port")),
                text(item.get("to_node")), text(item.get("to_port")), !Boolean.FALSE.equals(item.get("enabled")),
                number(item.get("gain"), 1)));
        return new AudioGraph(id, name, nodes, edges);
    }

    /**
     * Additively upgrades schema-10 graph snapshots without rewriting topology or non-generator values.
     * The returned map is a shallow copy of the config with only graph containers holding dynamic
     * generators copied and normalized; the input map is left unchanged.
     */
    public static Map<String, Object> upgradeSchema10Config(Map<String, Object> config) {
        Map<String, Object> upgraded = new LinkedHashMap<>(config);
        if (upgraded.get("route_library") instanceof List<?> routes)
            upgraded.put("route_library", normalizeRoutes(routes));
        if (upgraded.get("saved_copies") instanceof List<?> copies)
            upgraded.put("saved_copies", normalizeSavedCopies(copies));
        if (upgraded.get("common_vibro_graph") instanceof Map<?, ?> common)
            upgraded.put("common_vibro_graph", normalizeRoute(common));
        return upgraded;
    }

    private static List<Object> normalizeRoutes(List<?> routes) {
        List<Object> normalized = new ArrayList<>(routes.size());
        for (Object route : routes)
            normalized.add(route instanceof Map<?, ?> map ? normalizeRoute(map) : route);
        return normalized;
    }

    private static List<Object> normalizeSavedCopies(List<?> copies) {
        List<Object> normalized = new ArrayList<>(copies.size());
        for (Object value : copies) {
            if (!(value instanceof Map<?, ?> rawCopy)) {
                normalized.add(value);
                continue;
            }
            Map<String, Object> copy = object(rawCopy);
            if (copy.get("route") instanceof Map<?, ?> route)
                copy.put("route", normalizeRoute(route));
            normalized.add(copy);
        }
        return normalized;
    }

    private static Map<String, Object> normalizeRoute(Map<?, ?> rawRoute) {
        Map<String, Object> route = object(rawRoute);
        if (!(route.get("nodes") instanceof List<?> rawNodes)) return route;
        List<Object> nodes = new ArrayList<>(rawNodes.size());
        for (Object value : rawNodes) {
            if (!(value instanceof Map<?, ?> rawNode) || !"dynamic_generator".equals(rawNode.get("type"))) {
                nodes.add(value);
                continue;
            }
            Map<String, Object> node = object(rawNode);
            Object rawParameters = node.get("parameters");
            Object rawChannels = node.get("channels");
            if (!(rawParameters instanceof Map<?, ?> parameterMap)
                    || !(parameterMap.get("selector") instanceof String selector) || selector.isBlank()
                    || !(rawChannels instanceof List<?> channelValues)
                    || channelValues.isEmpty()
                    || channelValues.stream().anyMatch(channel -> !(channel instanceof String))) {
                nodes.add(value);
                continue;
            }
            List<String> channels = channelValues.stream().map(String.class::cast).toList();
            boolean hasHdmiSelectionPolicy =
                    parameterMap.containsKey("auto_select_trusted_hdmi_on_first_discovery");
            Map<String, Object> parameters = DynamicGeneratorNode.normalizeParameters(
                    selector, channels, object(parameterMap));
            if ("outputs".equals(parameters.get("registry")) && !hasHdmiSelectionPolicy)
                parameters.put("auto_select_trusted_hdmi_on_first_discovery", Boolean.FALSE);
            if (!parameters.containsKey("connect_to")) {
                Map<String, Object> inferred = inferConnectTo(node.get("id"), route.get("connections"));
                if (inferred != null) parameters.put("connect_to", inferred);
            }
            node.put("parameters", parameters);
            nodes.add(node);
        }
        route.put("nodes", nodes);
        return route;
    }

    private static Map<String, Object> inferConnectTo(Object rawGeneratorId, Object rawConnections) {
        if (!(rawGeneratorId instanceof String generatorId) || generatorId.isBlank()) return null;
        for (Map<String, Object> connection : maps(rawConnections)) {
            if (!generatorId.equals(connection.get("from_node"))) continue;
            Object nodeId = connection.get("to_node"), portId = connection.get("to_port");
            if (nodeId instanceof String node && !node.isBlank()
                    && portId instanceof String port && !port.isBlank())
                return new LinkedHashMap<>(Map.of("node_id", node, "port_id", port));
        }
        return null;
    }


    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
    }
    private static List<String> strings(Object value) { return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(); }
    private static List<String> ports(Object value,List<String> fallback){List<String> result=strings(value);return result.isEmpty()?fallback:result;}
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>(); map.forEach((key, item) -> result.put(String.valueOf(key), item)); return result;
    }
    private static Map<String, Double> numbers(Map<String, Object> value) {
        Map<String, Double> result = new LinkedHashMap<>(); value.forEach((key, item) -> result.put(key, number(item, Double.NaN))); return result;
    }
    private static MixerNode decodeMixer(String id, String name, List<String> channels, Map<String, Object> parameters) {
        Object encodedGains = parameters.get("input_gains");
        if (!(encodedGains instanceof Map<?, ?> rawGains)) return new MixerNode(id, name, channels, numbers(parameters));
        Map<String, Object> gains = new LinkedHashMap<>();
        rawGains.forEach((key, value) -> gains.put(String.valueOf(key), value));
        Map<String, Double> decodedGains = numbers(gains);
        Map<String, Boolean> decodedMutes = new LinkedHashMap<>();
        if (parameters.get("input_mutes") instanceof Map<?, ?> rawMutes)
            rawMutes.forEach((key, value) -> decodedMutes.put(String.valueOf(key), Boolean.TRUE.equals(value)));
        return new MixerNode(id, name, channels, decodedGains, decodedMutes);
    }
    private static Map<String, Object> without(Map<String, Object> value, String... excluded) {
        Map<String, Object> result = new LinkedHashMap<>(value); for (String key : excluded) result.remove(key); return result;
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static double number(Object value, double fallback) { return value instanceof Number number ? number.doubleValue() : fallback; }
}
