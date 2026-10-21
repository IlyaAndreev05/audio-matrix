package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.GraphValidator;
import io.github.ilyaandreev05.audiomatrix.graph.RouterNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Idempotent graph controls used by the GNOME API v4. */
final class GraphNodeCommand {
    private GraphNodeCommand() {}

    static void setVolume(Map<String, Object> config, String routeId, String nodeId, double volume) {
        if (!Double.isFinite(volume) || volume < 0 || volume > 1)
            throw new IllegalArgumentException("Node volume must be between 0 and 1");
        mutateGain(config, routeId, nodeId, gain -> new GainNode(gain.id(), gain.name(),
                gain.inputs().getFirst().channels(), volume, gain.muted(), gain.endpointNodeId()));
    }

    static void setMuted(Map<String, Object> config, String routeId, String nodeId, boolean muted) {
        mutateGain(config, routeId, nodeId, gain -> new GainNode(gain.id(), gain.name(),
                gain.inputs().getFirst().channels(), gain.gain(), muted, gain.endpointNodeId()));
    }

    static void setRouteEnabled(Map<String, Object> config, String routeId, String nodeId,
                                String linkId, boolean enabled) {
        Map<String, Object> route = GraphUiRoutes.route(config, routeId);
        AudioGraph graph = GraphCodec.fromMap(route);
        RouterNode router = graph.nodes().stream().filter(node -> node.id().equals(nodeId)
                && node instanceof RouterNode).map(RouterNode.class::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Route switch no longer exists"));
        if (!router.multiple()) throw new IllegalArgumentException("This route selector is not a MultiSwitch");
        String[] parts = linkId.split("->", -1);
        boolean connected = parts.length == 2
                && router.inputs().stream().anyMatch(port -> port.id().equals(parts[0]))
                && router.outputs().stream().anyMatch(port -> port.id().equals(parts[1]))
                && graph.connections().stream().anyMatch(edge -> edge.enabled()
                        && edge.toNode().equals(router.id()) && edge.toPort().equals(parts[0]))
                && graph.connections().stream().anyMatch(edge -> edge.enabled()
                        && edge.fromNode().equals(router.id()) && edge.fromPort().equals(parts[1]));
        if (!connected) throw new IllegalArgumentException("Route link is not connected");
        List<String> links = new ArrayList<>(router.activeLinks());
        if (enabled && !links.contains(linkId)) links.add(linkId);
        if (!enabled) links.remove(linkId);
        AudioGraph changed = new AudioGraph(graph.id(), graph.name(), replace(graph.nodes(), router,
                new io.github.ilyaandreev05.audiomatrix.graph.SwitchNode(router.id(), router.name(),
                        router.inputs().getFirst().channels(), router.outputs().stream().map(port -> port.id()).toList(), links)),
                graph.connections());
        save(config, routeId, route, changed);
    }

    static GraphNode targetNode(Map<String, Object> config, String routeId, String nodeId) {
        AudioGraph graph = GraphCodec.fromMap(GraphUiRoutes.route(config, routeId));
        return graph.nodes().stream().filter(item -> item.id().equals(nodeId)
                        && (item instanceof GainNode || item instanceof DeviceSlotNode))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Volume node no longer exists"));
    }

    static Object propertyValue(Map<String, Object> config, String routeId, String nodeId, String property) {
        GraphNode node = targetNode(config, routeId, nodeId);
        if ("volume".equals(property)) {
            if (node instanceof GainNode gain) return gain.gain();
            return deviceBinding(config, ((DeviceSlotNode) node).deviceId())
                    .getOrDefault("volume", 1.0);
        }
        if ("muted".equals(property)) {
            if (node instanceof GainNode gain) return gain.muted();
            return deviceBinding(config, ((DeviceSlotNode) node).deviceId())
                    .getOrDefault("mute", false);
        }
        throw new IllegalArgumentException("Unsupported node property");
    }

    static void setDeviceBindingLevel(Map<String, Object> config, String routeId, String nodeId,
                                     Double volume, Boolean muted) {
        GraphNode node = targetNode(config, routeId, nodeId);
        if (!(node instanceof DeviceSlotNode slot))
            throw new IllegalArgumentException("Node is not a device output");
        if (volume != null && (!Double.isFinite(volume) || volume < 0 || volume > 1))
            throw new IllegalArgumentException("Device volume must be between 0 and 1");
        Map<String, Object> binding = deviceBinding(config, slot.deviceId());
        if (volume != null) binding.put("volume", volume);
        if (muted != null) binding.put("mute", muted);
    }

    static Map<String, Object> deviceBinding(Map<String, Object> config, String reference) {
        String id = reference != null && reference.startsWith("device:")
                ? reference.substring("device:".length()) : reference;
        if (id == null || id.isBlank() || "system_default".equals(id))
            throw new IllegalArgumentException("Device output has no saved binding");
        for (Object value : config.get("devices") instanceof List<?> list ? list : List.of()) {
            if (!(value instanceof Map<?, ?> raw) || !id.equals(raw.get("id"))) continue;
            @SuppressWarnings("unchecked") Map<String, Object> binding = (Map<String, Object>) raw;
            if (Boolean.TRUE.equals(binding.get("ignored")))
                throw new IllegalArgumentException("Device output binding is unavailable");
            Object role = binding.get("role");
            if (role instanceof String configuredRole
                    && !List.of("output", "speaker", "speakers", "subwoofer", "haptic_output")
                    .contains(configuredRole))
                throw new IllegalArgumentException("Device binding is not an output");
            return binding;
        }
        throw new IllegalArgumentException("Device output binding no longer exists");
    }

    private static void mutateGain(Map<String, Object> config, String routeId, String nodeId,
                                   java.util.function.Function<GainNode, GainNode> mutation) {
        Map<String, Object> route = GraphUiRoutes.route(config, routeId);
        AudioGraph graph = GraphCodec.fromMap(route);
        GainNode node = graph.nodes().stream().filter(item -> item.id().equals(nodeId)
                        && item instanceof GainNode)
                .map(GainNode.class::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Gain node no longer exists"));
        AudioGraph changed = new AudioGraph(graph.id(), graph.name(),
                replace(graph.nodes(), node, mutation.apply(node)), graph.connections());
        save(config, routeId, route, changed);
    }

    private static List<GraphNode> replace(List<GraphNode> source, GraphNode old, GraphNode replacement) {
        List<GraphNode> nodes = new ArrayList<>(source);
        nodes.set(nodes.indexOf(old), replacement);
        return nodes;
    }

    private static void save(Map<String, Object> config, String routeId, Map<String, Object> route,
                             AudioGraph changed) {
        List<String> errors = GraphValidator.validate(changed);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        GraphRouteLibrary.saveRoute(config, changed, String.valueOf(route.getOrDefault("group_id", "")));
    }
}
