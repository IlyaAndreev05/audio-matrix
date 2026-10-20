package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceDiscovery;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GateNode;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphSubgraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphSubgraphLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.RouterNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** API v4 graph snapshot: hierarchy, capabilities, and saved/applied levels. */
final class GnomeGraphState {
    private GnomeGraphState() {}

    static Map<String, Object> build(Map<String, Object> config, PipeWireBackend.Snapshot snapshot) {
        String routeId = String.valueOf(config.getOrDefault("active_route", ""));
        Map<String, Object> encoded = GnomeGraphRoute.find(config, routeId);
        if (encoded == null) return Map.of("routeId", routeId, "nodes", List.of(), "connections", List.of(), "composites", List.of());
        AudioGraph graph;
        try { graph = GraphCodec.fromMap(encoded); }
        catch (RuntimeException invalid) { return Map.of("routeId", routeId, "nodes", List.of(), "connections", List.of(), "composites", List.of()); }
        List<GraphSubgraph> composites = GraphSubgraphLibrary.forRoute(config, routeId);
        Map<String, String> parents = new LinkedHashMap<>();
        for (GraphSubgraph group : composites) for (String id : group.nodeIds()) parents.put(id, group.id());
        Map<String, List<String>> children = new LinkedHashMap<>();
        for (GraphConnection edge : graph.connections())
            children.computeIfAbsent(edge.fromNode(), ignored -> new ArrayList<>()).add(edge.toNode());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (GraphNode node : graph.nodes()) nodes.add(node(node, parents.get(node.id()),
                children.getOrDefault(node.id(), List.of()).stream().distinct().toList(), snapshot, config));
        List<Map<String, Object>> groups = composites.stream().map(group -> Map.<String, Object>of(
                "id", group.id(), "name", group.name(), "type", "composite", "children", group.nodeIds(),
                "collapsed", group.collapsed())).toList();
        List<Map<String, Object>> connections = graph.connections().stream().map(edge -> Map.<String, Object>of(
                "id", edge.id(), "fromNode", edge.fromNode(), "fromPort", edge.fromPort(),
                "toNode", edge.toNode(), "toPort", edge.toPort(), "enabled", edge.enabled())).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("routeId", routeId);
        result.put("nodes", nodes);
        result.put("connections", connections);
        result.put("composites", groups);
        return result;
    }

    private static Map<String, Object> node(GraphNode node, String parentId, List<String> children,
                                            PipeWireBackend.Snapshot snapshot, Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", node.id());
        result.put("name", node.name());
        result.put("type", node.type());
        result.put("parentId", parentId);
        result.put("children", children);
        result.put("available", available(node, snapshot, config));
        result.put("capabilities", capabilities(node));
        result.put("savedVolume", node instanceof GainNode gain ? gain.gain()
                : node instanceof DeviceSlotNode slot ? slot.volume() : null);
        result.put("savedMuted", node instanceof GainNode gain ? gain.muted()
                : node instanceof DeviceSlotNode slot ? slot.muted() : null);
        result.put("appliedVolume", appliedVolume(node, snapshot, config));
        if (node instanceof MixerNode mixer) {
            result.put("inputVolumes", mixer.inputGains());
            result.put("inputMutes", mixer.inputMutes());
        }
        if (node instanceof RouterNode router) {
            result.put("activeLinks", router.activeLinks());
            result.put("multiple", router.multiple());
        }
        if (node instanceof GateNode gate) result.put("open", gate.open());
        if (node instanceof FilterNode filter) {
            result.put("enabled", filter.enabled());
            result.put("processor", filter.processor());
        }
        result.put("runtimeState", snapshot.connected() ? "connected" : "offline");
        return result;
    }

    private static Map<String, Object> capabilities(GraphNode node) {
        boolean gain = node instanceof GainNode;
        boolean route = node instanceof RouterNode router && router.multiple();
        boolean output = node instanceof DeviceSlotNode;
        boolean mixer = node instanceof MixerNode;
        boolean filter = node instanceof FilterNode;
        return Map.of("volume", gain || output, "mute", gain || output, "routeEnabled", route,
                "inputVolume", mixer, "inputMute", mixer, "filterParameters", filter,
                "selectDevice", output, "requiresExtension", false);
    }

    private static boolean available(GraphNode node, PipeWireBackend.Snapshot snapshot, Map<String, Object> config) {
        if (!snapshot.connected()) return false;
        if (node instanceof DeviceSlotNode slot)
            return Boolean.TRUE.equals(GnomeDeviceSlotState.describe(slot, snapshot, config).get("available"));
        if (node instanceof SourceNode source && !source.selector().equals("application:unassigned")
                && !source.selector().equals("application:*"))
            return snapshot.streams().stream().anyMatch(stream -> matches(source.selector(), stream));
        return true;
    }

    private static Double appliedVolume(GraphNode node, PipeWireBackend.Snapshot snapshot, Map<String, Object> config) {
        if (node instanceof DeviceSlotNode slot) {
            if ("system_default".equals(slot.deviceId())) {
                AudioNode resolved = snapshot.sinks().stream().filter(item -> item.id() == snapshot.defaultSinkId())
                        .findFirst().orElse(null);
                return resolved == null || !Double.isFinite(resolved.volume()) ? null : resolved.volume();
            }
            Map<String, Object> binding = maps(config.get("devices")).stream()
                    .filter(item -> slot.deviceId().equals(item.get("id"))).findFirst().orElse(null);
            AudioNode resolved = binding == null ? null : DeviceDiscovery.resolve(snapshot.sinks(), binding).node();
            return resolved == null || !Double.isFinite(resolved.volume()) ? null : resolved.volume();
        }
        if (node instanceof SourceNode source) {
            List<AudioNode> streams = snapshot.streams().stream().filter(stream -> matches(source.selector(), stream)).toList();
            if (streams.isEmpty()) return null;
            return streams.stream().filter(stream -> Double.isFinite(stream.volume())).mapToDouble(AudioNode::volume).average().orElse(Double.NaN);
        }
        return null;
    }

    private static boolean matches(String selector, AudioNode stream) {
        if (selector.startsWith("application:")) return selector.substring(12).equals(stream.applicationId());
        if (selector.startsWith("process:")) return selector.substring(8).equals(stream.processName());
        return false;
    }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
