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
        mutateLevel(config, routeId, nodeId, node -> {
            if (node instanceof GainNode gain)
                return new GainNode(gain.id(), gain.name(), gain.inputs().getFirst().channels(),
                        volume, false, gain.endpointNodeId());
            if (node instanceof DeviceSlotNode slot)
                return new DeviceSlotNode(slot.id(), slot.name(), slot.deviceId(),
                        slot.inputs().getFirst().channels(), volume, slot.muted());
            throw new IllegalArgumentException("Node has no volume control");
        });
    }

    static void setMuted(Map<String, Object> config, String routeId, String nodeId, boolean muted) {
        mutateLevel(config, routeId, nodeId, node -> {
            if (node instanceof GainNode gain)
                return new GainNode(gain.id(), gain.name(), gain.inputs().getFirst().channels(),
                        gain.gain(), muted, gain.endpointNodeId());
            if (node instanceof DeviceSlotNode slot)
                return new DeviceSlotNode(slot.id(), slot.name(), slot.deviceId(),
                        slot.inputs().getFirst().channels(), slot.volume(), muted);
            throw new IllegalArgumentException("Node has no mute control");
        });
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

    private static void mutateLevel(Map<String, Object> config, String routeId, String nodeId,
                                   java.util.function.Function<GraphNode, GraphNode> mutation) {
        Map<String, Object> route = GraphUiRoutes.route(config, routeId);
        AudioGraph graph = GraphCodec.fromMap(route);
        GraphNode node = graph.nodes().stream().filter(item -> item.id().equals(nodeId)
                && (item instanceof GainNode || item instanceof DeviceSlotNode)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Volume node no longer exists"));
        AudioGraph changed = new AudioGraph(graph.id(), graph.name(), replace(graph.nodes(), node, mutation.apply(node)),
                graph.connections());
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
