package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.RouterNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GnomeQuickSettingsPins {
    private GnomeQuickSettingsPins() {}

    static List<Map<String, Object>> build(Map<String, Object> config, PipeWireBackend.Snapshot snapshot,
                                           List<Map<String, Object>> quickControls,
                                           List<Map<String, Object>> applications,
                                           List<Map<String, Object>> outputs,
                                           AudioGraph effectiveGraph,
                                           Map<DynamicInstanceRegistry.InstanceAddress,
                                                   DynamicInstanceRegistry.RuntimeInstance> instances) {
        String routeId = String.valueOf(config.getOrDefault("active_route", ""));
        AudioGraph commonGraph = null;
        try { commonGraph = GraphCommonVibro.graph(config); }
        catch (RuntimeException invalid) { /* Invalid common state is not published. */ }
        Map<String, AudioGraph> graphs = new LinkedHashMap<>();
        Map<String, List<String>> claims = new LinkedHashMap<>();
        Set<String> generatedNodes = generatedNodeIds(instances);
        if (commonGraph != null) {
            graphs.put(GraphCommonVibro.COMMON_ROUTE_ID, commonGraph);
            claims.put(GraphCommonVibro.COMMON_ROUTE_ID, GnomeQuickSettingsPinDetails.claimSelectors(commonGraph, generatedNodes));
        }
        AudioGraph activeGraph = effectiveGraph != null && routeId.equals(effectiveGraph.id())
                ? effectiveGraph : routeGraph(config, routeId);
        if (activeGraph != null) {
            graphs.put(routeId, activeGraph);
            claims.put(routeId, GnomeQuickSettingsPinDetails.claimSelectors(activeGraph, generatedNodes));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> deferredSharedGain = new ArrayList<>();
        for (Map<String, Object> pin : QuickSettingsConfig.pins(config)) {
            String pinRouteId = String.valueOf(pin.getOrDefault("route_id", ""));
            boolean common = GraphCommonVibro.COMMON_ROUTE_ID.equals(pinRouteId);
            boolean active = routeId.equals(pinRouteId);
            if (!common && !active && !graphs.containsKey(pinRouteId)) {
                AudioGraph inactive = routeGraph(config, pinRouteId);
                if (inactive != null) {
                    graphs.put(pinRouteId, inactive);
                    claims.put(pinRouteId, GnomeQuickSettingsPinDetails.claimSelectors(inactive, generatedNodes));
                }
            }
            AudioGraph pinGraph = graphs.get(pinRouteId);
            if (pinGraph == null) continue;
            List<String> claimSelectors = claims.getOrDefault(pinRouteId, List.of());
            String nodeId = String.valueOf(pin.getOrDefault("node_id", ""));
            String instanceKey = String.valueOf(pin.getOrDefault("instance_key", ""));
            if (!instanceKey.isBlank()) {
                Map<String, Object> generated = GnomeQuickSettingsGeneratedPins.savedPin(pin, pinRouteId,
                        pinGraph, snapshot, config, applications, outputs, instances);
                if (generated != null) result.add(generated);
                continue;
            }
            GraphNode node = pinGraph.nodes().stream().filter(item -> nodeId.equals(item.id())).findFirst().orElse(null);
            if (node == null) continue;
            if (!common && !active && !(node instanceof OutputNode) && !(node instanceof DeviceSlotNode)) continue;
            if (node instanceof FilterNode filter && "phase_invert".equals(filter.processor())) continue;
            Map<String, Object> state = pinState(pin, node);
            state.put("pinned", common);
            state.put("controls", controlsFor(pin, quickControls, node));
            if (node instanceof RouterNode router && router.multiple())
                state.put("routeControl", routeControl(pinRouteId, router, pinGraph));
            if (node instanceof SourceNode source) {
                publishApplications(state, source.selector(), applications, claimSelectors);
                GainNode sourceGain = GnomeGraphSourceGain.forSource(pinGraph, source.id());
                if (sourceGain != null) state.put("sourceGain", sourceGainState(
                        String.valueOf(pin.get("route_id")), source, sourceGain));
            }
            if (node instanceof DynamicGeneratorNode generator && "applications".equals(generator.registry()))
                publishApplications(state, generator.selector(), applications, claimSelectors);
            if (node instanceof OutputNode output)
                state.put("output", GnomeQuickSettingsPinDetails.outputFor(output, pinGraph, snapshot, config, outputs));
            if (node instanceof DeviceSlotNode slot)
                state.put("output", GnomeDeviceSlotState.describe(slot, snapshot, config));
            if (node instanceof GainNode gain) {
                if (GraphCommonVibro.COMMON_ROUTE_ID.equals(pinRouteId)
                        && GraphCommonVibro.VIBRO_GAIN_ID.equals(gain.id())) {
                    Map<String, Object> shared = GnomeQuickSettingsHapticGain.targetState(config);
                    if (shared != null)
                        deferredSharedGain.add(GnomeQuickSettingsHapticGain.savedPinState(pin, shared, false));
                    continue;
                }
                state.put("gain", Map.of("routeId", String.valueOf(pin.get("route_id")),
                        "nodeId", gain.id(), "volume", gain.gain(), "muted", gain.muted()));
            }
            if (node instanceof MixerNode mixer)
                state.put("mixerInputs", mixerInputs(pinGraph, mixer));
            result.add(state);
        }
        GnomeQuickSettingsGeneratedPins.appendAutoPins(result, config, routeId, activeGraph, snapshot,
                applications, outputs, instances);
        boolean hapticOutput = GnomeQuickSettingsHapticGain.hasAssignedOutput(instances);
        if (result.stream().noneMatch(pin -> pin.containsKey("sharedGain")))
            for (Map<String, Object> pin : deferredSharedGain)
                if (Boolean.TRUE.equals(pin.get("showWhenOffline")) || !hapticOutput) result.add(pin);
        result.sort(java.util.Comparator.comparingInt(item -> number(item.get("order"), 0)));
        return List.copyOf(result);
    }

    private static AudioGraph routeGraph(Map<String, Object> config, String routeId) {
        if (routeId.isBlank()) return null;
        Map<String, Object> route = maps(config.get("route_library")).stream()
                .filter(item -> routeId.equals(item.get("id"))).findFirst().orElse(null);
        if (route == null) return null;
        try { return GraphCodec.fromMap(route); }
        catch (RuntimeException invalid) { return null; }
    }

    private static Map<String, Object> sourceGainState(String routeId, SourceNode source, GainNode gain) {
        return Map.of("routeId", routeId, "sourceNodeId", source.id(), "gainNodeId", gain.id(),
                "volume", gain.gain(), "muted", gain.muted());
    }

    /** Publishes the applications served by a source node or by the catch-all application generator. */
    private static void publishApplications(Map<String, Object> state, String selector,
                                            List<Map<String, Object>> applications, List<String> claims) {
        List<Map<String, Object>> served = GnomeQuickSettingsPinDetails.applicationsFor(
                selector, applications, claims);
        state.put("applications", served);
        state.put("tabAdapterAvailable", served.stream()
                .anyMatch(app -> Boolean.TRUE.equals(app.get("tabAdapterAvailable"))));
    }

    /** The nodes the registry materialized for a generator instance: they never claim an application. */
    private static Set<String> generatedNodeIds(Map<DynamicInstanceRegistry.InstanceAddress,
                                                 DynamicInstanceRegistry.RuntimeInstance> instances) {
        Set<String> result = new LinkedHashSet<>();
        if (instances == null) return result;
        for (DynamicInstanceRegistry.RuntimeInstance instance : instances.values())
            result.addAll(instance.nodeIds().values());
        return result;
    }

    private static Map<String, Object> pinState(Map<String, Object> pin, GraphNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", pin.get("id"));
        result.put("routeId", pin.get("route_id"));
        result.put("nodeId", node.id());
        result.put("name", pin.getOrDefault("display_name", node.name()));
        result.put("nodeType", node.type());
        result.put("groupId", pin.get("group_id"));
        result.put("order", pin.getOrDefault("order", 0));
        result.put("view", pin.getOrDefault("view", "DEFAULT"));
        if (pin.get("icon") instanceof String icon && !icon.isBlank()) result.put("icon", icon);
        if (pin.get("show_children") instanceof Boolean children) result.put("showChildren", children);
        if (pin.get("show_when_offline") instanceof Boolean offline) result.put("showWhenOffline", offline);
        return result;
    }

    private static List<Map<String, Object>> controlsFor(Map<String, Object> pin,
                                                          List<Map<String, Object>> controls,
                                                          GraphNode node) {
        Set<String> ids = strings(pin.get("control_ids"));
        return controls.stream().filter(control -> ids.contains(String.valueOf(control.get("id"))))
                .filter(control -> !(node instanceof GainNode
                        && Set.of("gain", "muted").contains(String.valueOf(control.get("parameterId")))))
                .toList();
    }

    private static List<Map<String, Object>> mixerInputs(AudioGraph graph, MixerNode mixer) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Double> input : mixer.inputGains().entrySet()) {
            GraphConnection connection = graph.connections().stream().filter(GraphConnection::enabled)
                    .filter(edge -> mixer.id().equals(edge.toNode()) && input.getKey().equals(edge.toPort()))
                    .findFirst().orElse(null);
            GraphNode source = connection == null ? null : graph.nodes().stream()
                    .filter(node -> node.id().equals(connection.fromNode())).findFirst().orElse(null);
            if (source instanceof GainNode gain && !gain.endpointNodeId().isBlank())
                source = graph.nodes().stream().filter(node -> node.id().equals(gain.endpointNodeId()))
                        .findFirst().orElse(source);
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("id", input.getKey());
            state.put("name", source == null ? input.getKey() : source.name());
            state.put("volume", input.getValue());
            state.put("muted", mixer.inputMuted(input.getKey()));
            result.add(state);
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> routeControl(String routeId, RouterNode router, AudioGraph graph) {
        List<GraphConnection> inputs = graph.connections().stream().filter(GraphConnection::enabled)
                .filter(edge -> router.id().equals(edge.toNode())).toList();
        List<GraphConnection> outputs = graph.connections().stream().filter(GraphConnection::enabled)
                .filter(edge -> router.id().equals(edge.fromNode())).toList();
        List<Map<String, Object>> options = new ArrayList<>();
        for (GraphConnection input : inputs) for (GraphConnection output : outputs) {
            GraphNode target = graph.nodes().stream().filter(node -> node.id().equals(output.toNode()))
                    .findFirst().orElse(null);
            options.add(Map.of("id", input.toPort() + "->" + output.fromPort(),
                    "label", target == null ? output.fromPort() : target.name()));
        }
        return Map.of("id", "route:" + routeId + ":" + router.id(), "routeId", routeId,
                "nodeId", router.id(), "switchNodeId", router.id(), "nodeType", "switch", "kind", "choice",
                "label", "Музыкальный маршрут", "multiple", true,
                "value", router.activeLinks(), "options", options);
    }


    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static Set<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return Set.of();
        return list.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
