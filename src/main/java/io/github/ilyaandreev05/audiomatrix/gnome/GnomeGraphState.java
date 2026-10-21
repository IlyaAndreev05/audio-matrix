package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceDiscovery;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
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
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** API v5 graph projection: hierarchy, capabilities, runtime instances, and saved/applied levels. */
final class GnomeGraphState {
    private GnomeGraphState() {}

    static Map<String, Object> build(Map<String, Object> config, PipeWireBackend.Snapshot snapshot) {
        return build(config, snapshot, null, Map.of(), List.of());
    }

    static Map<String, Object> build(Map<String, Object> config, PipeWireBackend.Snapshot snapshot,
                                     DynamicInstanceRegistry.ReconcileResult reconciliation) {
        if (reconciliation == null)
            return build(config, snapshot);
        return build(config, snapshot, reconciliation.effectiveGraph(), reconciliation.instances(),
                reconciliation.generatedSubgraphs());
    }

    static Map<String, Object> build(Map<String, Object> config, PipeWireBackend.Snapshot snapshot,
                                     AudioGraph effectiveGraph,
                                     Map<DynamicInstanceRegistry.InstanceAddress, DynamicInstanceRegistry.RuntimeInstance> instances,
                                     List<GraphSubgraph> generatedSubgraphs) {
        String routeId = String.valueOf(config.getOrDefault("active_route", ""));
        AudioGraph graph = effectiveGraph;
        if (graph == null) {
            Map<String, Object> encoded = GnomeGraphRoute.find(config, routeId);
            if (encoded == null) return empty(routeId);
            try { graph = GraphCodec.fromMap(encoded); }
            catch (RuntimeException invalid) { return empty(routeId); }
        }
        Map<String, GraphSubgraph> compositeIndex = new LinkedHashMap<>();
        for (GraphSubgraph group : GraphSubgraphLibrary.forRoute(config, routeId))
            compositeIndex.put(group.id(), group);
        for (GraphSubgraph group : generatedSubgraphs)
            compositeIndex.put(group.id(), group);
        List<GraphSubgraph> composites = List.copyOf(compositeIndex.values());
        Map<String, String> parents = new LinkedHashMap<>();
        for (GraphSubgraph group : composites)
            for (String id : group.nodeIds()) parents.put(id, group.id());
        Map<String, List<String>> children = new LinkedHashMap<>();
        for (GraphConnection edge : graph.connections())
            children.computeIfAbsent(edge.fromNode(), ignored -> new ArrayList<>()).add(edge.toNode());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (GraphNode node : graph.nodes())
            nodes.add(node(node, parents.get(node.id()),
                    children.getOrDefault(node.id(), List.of()).stream().distinct().toList(),
                    snapshot, config, instances));
        List<Map<String, Object>> groups = composites.stream().map(group -> Map.<String, Object>of(
                "id", group.id(), "name", group.name(), "type", "composite", "children", group.nodeIds(),
                "collapsed", group.collapsed(), "parentId", group.parentId() == null ? "" : group.parentId()))
                .toList();
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

    private static Map<String, Object> empty(String routeId) {
        return Map.of("routeId", routeId, "nodes", List.of(), "connections", List.of(), "composites", List.of());
    }

    private static Map<String, Object> node(GraphNode node, String parentId, List<String> children,
                                            PipeWireBackend.Snapshot snapshot, Map<String, Object> config,
                                            Map<DynamicInstanceRegistry.InstanceAddress,
                                                    DynamicInstanceRegistry.RuntimeInstance> instances) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean isAvailable = available(node, snapshot, config, instances);
        result.put("id", node.id());
        result.put("name", node.name());
        result.put("type", node.type());
        result.put("parentId", parentId);
        result.put("children", children);
        result.put("available", isAvailable);
        result.put("capabilities", capabilities(node));
        result.put("savedVolume", node instanceof GainNode gain ? gain.gain()
                : node instanceof DeviceSlotNode slot ? slot.volume() : null);
        result.put("savedMuted", node instanceof GainNode gain ? gain.muted()
                : node instanceof DeviceSlotNode slot ? slot.muted() : null);
        result.put("appliedVolume", appliedVolume(node, snapshot, config, instances));
        if (node instanceof DynamicGeneratorNode generator) {
            result.put("registry", generator.registry());
            result.put("template", generator.template());
            result.put("instances", instances.values().stream()
                    .filter(instance -> generator.id().equals(instance.generatorId()))
                    .map(GnomeGraphState::instanceState).toList());
        }
        DynamicInstanceRegistry.RuntimeInstance runtime = runtimeInstanceForNode(node.id(), instances);
        if (runtime != null) {
            result.put("generatorId", runtime.generatorId());
            result.put("instanceKey", runtime.instanceKey());
            result.put("available", snapshot.connected() && runtime.available());
            result.put("confidence", runtime.confidence());
            result.put("runtimeBinding", runtime.runtimeBinding());
            if (runtime.compositeId() != null) result.put("compositeId", runtime.compositeId());
            result.put("overrides", runtime.overrides());
        }
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
        result.put("runtimeState", isAvailable ? "connected" : "offline");
        return result;
    }

    private static Map<String, Object> instanceState(DynamicInstanceRegistry.RuntimeInstance instance) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatorId", instance.generatorId());
        result.put("instanceKey", instance.instanceKey());
        result.put("nodeIds", instance.nodeIds());
        result.put("available", instance.available());
        result.put("confidence", instance.confidence());
        result.put("runtimeBinding", instance.runtimeBinding());
        result.put("overrides", instance.overrides());
        if (instance.compositeId() != null) result.put("compositeId", instance.compositeId());
        return result;
    }

    private static DynamicInstanceRegistry.RuntimeInstance runtimeInstanceForNode(
            String nodeId, Map<DynamicInstanceRegistry.InstanceAddress,
                    DynamicInstanceRegistry.RuntimeInstance> instances) {
        return instances.values().stream()
                .filter(instance -> instance.nodeIds().containsValue(nodeId))
                .findFirst().orElse(null);
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

    private static boolean available(GraphNode node, PipeWireBackend.Snapshot snapshot, Map<String, Object> config,
                                     Map<DynamicInstanceRegistry.InstanceAddress,
                                             DynamicInstanceRegistry.RuntimeInstance> instances) {
        if (!snapshot.connected()) return false;
        if (node instanceof DeviceSlotNode slot)
            return Boolean.TRUE.equals(GnomeDeviceSlotState.describe(slot, snapshot, config).get("available"));
        if (node instanceof DynamicGeneratorNode generator)
            return instances.values().stream().anyMatch(instance -> generator.id().equals(instance.generatorId())
                    && instance.available());
        DynamicInstanceRegistry.RuntimeInstance runtime = runtimeInstanceForNode(node.id(), instances);
        if (runtime != null) return runtime.available();
        if (node instanceof SourceNode source && !source.selector().equals("application:unassigned")
                && !source.selector().equals("application:*"))
            return snapshot.streams().stream().anyMatch(stream -> matches(source.selector(), stream));
        return true;
    }

    private static Double appliedVolume(GraphNode node, PipeWireBackend.Snapshot snapshot, Map<String, Object> config,
                                        Map<DynamicInstanceRegistry.InstanceAddress,
                                                DynamicInstanceRegistry.RuntimeInstance> instances) {
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
        DynamicInstanceRegistry.RuntimeInstance runtime = runtimeInstanceForNode(node.id(), instances);
        if (runtime != null && runtime.runtimeBinding() instanceof Map<?, ?> binding
                && binding.get("volume") instanceof Number volume && Double.isFinite(volume.doubleValue()))
            return volume.doubleValue();
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
