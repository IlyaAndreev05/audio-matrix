package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Additive schema-10 normalization, separate from configuration migration policy. */
final class GraphSchema10Upgrade {
    private GraphSchema10Upgrade() {}

    static Map<String, Object> upgrade(Map<String, Object> config) {
        Map<String, Object> upgraded = new LinkedHashMap<>(config);
        Map<String, String> deviceFingerprints = GraphSchema10OutputAugmenter.deviceFingerprints(config.get("devices"));
        if (upgraded.get("route_library") instanceof List<?> routes) {
            List<Object> normalized = normalizeRoutes(routes, deviceFingerprints);
            upgraded.put("route_library", normalized);
            upgraded.put("graph_ui_subgraphs", GraphGeneratorComposites.copiedContainers(upgraded.get("graph_ui_subgraphs")));
            GraphGeneratorComposites.ensureConfigRoutes(upgraded, normalized);
        }
        if (upgraded.get("saved_copies") instanceof List<?> copies)
            upgraded.put("saved_copies", normalizeSavedCopies(copies, deviceFingerprints));
        if (upgraded.get("common_vibro_graph") instanceof Map<?, ?> common)
            upgraded.put("common_vibro_graph", normalizeRoute(common, deviceFingerprints));
        return upgraded;
    }

    private static List<Object> normalizeRoutes(List<?> routes, Map<String, String> deviceFingerprints) {
        List<Object> normalized = new ArrayList<>(routes.size());
        for (Object route : routes)
            normalized.add(route instanceof Map<?, ?> map ? normalizeRoute(map, deviceFingerprints) : route);
        return normalized;
    }

    private static List<Object> normalizeSavedCopies(List<?> copies, Map<String, String> deviceFingerprints) {
        List<Object> normalized = new ArrayList<>(copies.size());
        for (Object value : copies) {
            if (!(value instanceof Map<?, ?> rawCopy)) {
                normalized.add(value);
                continue;
            }
            Map<String, Object> copy = object(rawCopy);
            if (copy.get("route") instanceof Map<?, ?> route)
                copy.put("route", normalizeRoute(route, deviceFingerprints));
            normalized.add(copy);
        }
        return normalized;
    }

    private static Map<String, Object> normalizeRoute(Map<?, ?> rawRoute, Map<String, String> deviceFingerprints) {
        Map<String, Object> route = object(rawRoute);
        if (!(route.get("nodes") instanceof List<?> rawNodes)) return route;
        Map<String, Map<String, Object>> nodesById = new LinkedHashMap<>();
        for (Object value : rawNodes)
            if (value instanceof Map<?, ?> rawNode) nodesById.put(text(rawNode.get("id")), object(rawNode));
        List<Object> nodes = new ArrayList<>(rawNodes.size());
        for (Object value : rawNodes) {
            if (!(value instanceof Map<?, ?> rawNode) || !"dynamic_generator".equals(rawNode.get("type"))) {
                nodes.add(value);
                continue;
            }
            nodes.add(normalizeGenerator(rawNode, route, nodesById));
        }
        route.put("nodes", nodes);
        return GraphSchema10OutputAugmenter.addMissingOutputs(route, deviceFingerprints);
    }

    private static Map<String, Object> normalizeGenerator(Map<?, ?> rawNode, Map<String, Object> route,
                                                          Map<String, Map<String, Object>> nodesById) {
        Map<String, Object> node = object(rawNode);
        Object rawParameters = node.get("parameters"), rawChannels = node.get("channels");
        if (!(rawParameters instanceof Map<?, ?> parameterMap)
                || !(parameterMap.get("selector") instanceof String selector) || selector.isBlank()
                || !(rawChannels instanceof List<?> channels) || channels.isEmpty()
                || channels.stream().anyMatch(channel -> !(channel instanceof String))) return node;
        List<String> channelList = channels.stream().map(String.class::cast).toList();
        boolean hasHdmiPolicy = parameterMap.containsKey("auto_select_trusted_hdmi_on_first_discovery");
        Map<String, Object> parameters = DynamicGeneratorNode.normalizeParameters(
                selector, channelList, object(parameterMap));
        if ("outputs".equals(parameters.get("registry")) && !hasHdmiPolicy)
            parameters.put("auto_select_trusted_hdmi_on_first_discovery", Boolean.FALSE);
        if (!parameters.containsKey("connect_to")) {
            Map<String, Object> inferred = inferConnectTo(node.get("id"), route.get("connections"), nodesById);
            if (inferred != null) {
                parameters.put("connect_to", inferred);
                alignLegacyControlEdge(node.get("id"), inferred, route);
            }
        }
        node.put("parameters", parameters);
        return node;
    }

    /**
     * Infers the anchor of a legacy generator. The generated children enter the Merge, so intermediate
     * endpoint Gain and processor nodes are skipped and the mixer the chain finally reaches is used.
     */
    private static Map<String, Object> inferConnectTo(Object rawGeneratorId, Object rawConnections,
                                                      Map<String, Map<String, Object>> nodesById) {
        if (!(rawGeneratorId instanceof String generatorId) || generatorId.isBlank()) return null;
        List<Map<String, Object>> connections = maps(rawConnections);
        Map<String, Object> direct = firstTarget(generatorId, connections);
        if (direct == null) return null;
        Map<String, Object> current = direct;
        Set<String> visited = new LinkedHashSet<>();
        while (current != null && visited.add(text(current.get("node_id")))) {
            Map<String, Object> node = nodesById.get(text(current.get("node_id")));
            if (node == null) break;
            String type = text(node.get("type"));
            if ("mixer".equals(type)) return current;
            if (!"gain".equals(type) && !"filter".equals(type) && !"gate".equals(type)) break;
            Map<String, Object> next = firstTarget(text(current.get("node_id")), connections);
            if (next == null) break;
            current = next;
        }
        return direct;
    }

    private static Map<String, Object> firstTarget(String fromNode, List<Map<String, Object>> connections) {
        for (Map<String, Object> connection : connections) {
            if (!fromNode.equals(connection.get("from_node"))) continue;
            Object nodeId = connection.get("to_node"), portId = connection.get("to_port");
            if (nodeId instanceof String node && !node.isBlank()
                    && portId instanceof String port && !port.isBlank())
                return new LinkedHashMap<>(Map.of("node_id", node, "port_id", port));
        }
        return null;
    }

    /** The legacy control edge follows the inferred anchor; the audio chain stays untouched. */
    private static void alignLegacyControlEdge(Object rawGeneratorId, Map<String, Object> target,
                                                Map<String, Object> route) {
        if (!(rawGeneratorId instanceof String generatorId) || generatorId.isBlank()) return;
        if (!(route.get("connections") instanceof List<?> rawConnections)) return;
        List<Object> connections = new ArrayList<>(rawConnections.size());
        for (Object value : rawConnections) {
            if (value instanceof Map<?, ?> rawConnection && generatorId.equals(rawConnection.get("from_node"))) {
                Map<String, Object> connection = object(rawConnection);
                connection.put("to_node", target.get("node_id"));
                connection.put("to_port", target.get("port_id"));
                connections.add(connection);
                continue;
            }
            connections.add(value);
        }
        route.put("connections", connections);
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> object((Map<?, ?>) item)).toList();
    }

    private static Map<String, Object> object(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
