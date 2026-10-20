package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.AudioMatrixOwnedStream;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GnomeQuickSettingsPins {
    private GnomeQuickSettingsPins() {}

    static List<Map<String, Object>> build(Map<String, Object> config, PipeWireBackend.Snapshot snapshot,
                                           List<Map<String, Object>> quickControls,
                                           List<Map<String, Object>> applications,
                                           List<Map<String, Object>> outputs) {
        String routeId = String.valueOf(config.getOrDefault("active_route", ""));
        AudioGraph commonGraph = null;
        try { commonGraph = GraphCommonVibro.graph(config); }
        catch (RuntimeException invalid) { /* Invalid common state is not published. */ }
        Map<String, AudioGraph> graphs = new LinkedHashMap<>();
        if (commonGraph != null) graphs.put(GraphCommonVibro.COMMON_ROUTE_ID, commonGraph);
        AudioGraph activeGraph = routeGraph(config, routeId);
        if (activeGraph != null) graphs.put(routeId, activeGraph);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> pin : QuickSettingsConfig.pins(config)) {
            String pinRouteId = String.valueOf(pin.getOrDefault("route_id", ""));
            boolean common = GraphCommonVibro.COMMON_ROUTE_ID.equals(pinRouteId);
            boolean active = routeId.equals(pinRouteId);
            if (!common && !active && !graphs.containsKey(pinRouteId)) {
                AudioGraph inactive = routeGraph(config, pinRouteId);
                if (inactive != null) graphs.put(pinRouteId, inactive);
            }
            AudioGraph pinGraph = graphs.get(pinRouteId);
            if (pinGraph == null) continue;
            String nodeId = String.valueOf(pin.getOrDefault("node_id", ""));
            GraphNode node = pinGraph.nodes().stream().filter(item -> nodeId.equals(item.id())).findFirst().orElse(null);
            if (node == null) continue;
            if (!common && !active && !(node instanceof OutputNode) && !(node instanceof DeviceSlotNode)) continue;
            if (node instanceof FilterNode filter && "phase_invert".equals(filter.processor())) continue;
            if (isEndpointGain(pinGraph, node)) continue;
            Map<String, Object> state = pinState(pin, node);
            state.put("pinned", common);
            state.put("controls", controlsFor(pin, quickControls));
            if (node instanceof RouterNode router && router.multiple())
                state.put("routeControl", routeControl(pinRouteId, router, pinGraph));
            if (node instanceof SourceNode source) {
                List<Map<String, Object>> sourceApplications = applicationsFor(source, applications, pinGraph);
                state.put("applications", sourceApplications);
                state.put("tabAdapterAvailable", sourceApplications.stream()
                        .anyMatch(app -> Boolean.TRUE.equals(app.get("tabAdapterAvailable"))));
                GainNode sourceGain = GnomeGraphSourceGain.forSource(pinGraph, source.id());
                if (sourceGain != null) state.put("sourceGain", sourceGainState(
                        String.valueOf(pin.get("route_id")), source, sourceGain));
            }
            if (node instanceof OutputNode output)
                state.put("output", outputFor(output, pinGraph, snapshot, config, outputs));
            if (node instanceof DeviceSlotNode slot)
                state.put("output", GnomeDeviceSlotState.describe(slot, snapshot, config));
            if (node instanceof GainNode gain)
                state.put("gain", Map.of("routeId", String.valueOf(pin.get("route_id")),
                        "nodeId", gain.id(), "volume", gain.gain(), "muted", gain.muted()));
            if (node instanceof MixerNode mixer)
                state.put("mixerInputs", mixerInputs(pinGraph, mixer));
            result.add(state);
        }
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

    private static boolean isEndpointGain(AudioGraph graph, GraphNode node) {
        if (!(node instanceof GainNode gain) || gain.endpointNodeId().isBlank()) return false;
        return graph.nodes().stream().anyMatch(endpoint -> endpoint.id().equals(gain.endpointNodeId())
                && (endpoint instanceof SourceNode || endpoint instanceof OutputNode));
    }

    private static Map<String, Object> pinState(Map<String, Object> pin, GraphNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", pin.get("id"));
        result.put("routeId", pin.get("route_id"));
        result.put("nodeId", node.id());
        result.put("name", node.name());
        result.put("nodeType", node.type());
        result.put("groupId", pin.get("group_id"));
        result.put("order", pin.getOrDefault("order", 0));
        result.put("view", pin.getOrDefault("view", "DEFAULT"));
        return result;
    }

    private static List<Map<String, Object>> controlsFor(Map<String, Object> pin,
                                                          List<Map<String, Object>> controls) {
        Set<String> ids = strings(pin.get("control_ids"));
        return controls.stream().filter(control -> ids.contains(String.valueOf(control.get("id")))).toList();
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
                "nodeId", router.id(), "nodeType", "switch", "kind", "choice",
                "label", "Музыкальный маршрут", "multiple", true,
                "value", router.activeLinks(), "options", options);
    }

    private static List<Map<String, Object>> applicationsFor(SourceNode source,
                                                              List<Map<String, Object>> applications,
                                                              AudioGraph graph) {
        String selector = source.selector();
        List<String> exactSelectors = graph.nodes().stream().filter(SourceNode.class::isInstance)
                .map(SourceNode.class::cast).map(SourceNode::selector)
                .filter(value -> !value.equals("application:*") && !value.equals("application:unassigned")).toList();
        return applications.stream().filter(app -> matches(selector, app, exactSelectors)).toList();
    }

    private static boolean matches(String selector, Map<String, Object> app, List<String> explicit) {
        String appId = String.valueOf(app.getOrDefault("application_id", ""));
        String process = String.valueOf(app.getOrDefault("process", ""));
        if ("io.github.ilyaandreev05.eventsoundstudio".equals(appId)
                || AudioMatrixOwnedStream.matches(applicationNode(app))) return false;
        if (selector.startsWith("application:") && !selector.equals("application:*")
                && !selector.equals("application:unassigned"))
            return selector.substring("application:".length()).equals(appId);
        if (selector.startsWith("process:")) return selector.substring("process:".length()).equals(process);
        if (selector.equals("application:unassigned") || selector.equals("application:*"))
            return explicit.stream().noneMatch(value -> value.equals("application:" + appId) || value.equals("process:" + process));
        return false;
    }

    private static AudioNode applicationNode(Map<String, Object> app) {
        return new AudioNode(-1, "", "", "Stream/Output/Audio", "", String.valueOf(app.getOrDefault("name", "")),
                String.valueOf(app.getOrDefault("process", "")), String.valueOf(app.getOrDefault("application_id", "")),
                2, List.of(), 1.0, false, null);
    }

    private static Map<String, Object> outputFor(OutputNode output, AudioGraph graph,
                                                  PipeWireBackend.Snapshot snapshot,
                                                  Map<String, Object> config, List<Map<String, Object>> outputs) {
        List<Map<String, Object>> devices = maps(config.get("devices"));
        AudioNode resolved = output.deviceId().equals("system_default")
                ? snapshot.sinks().stream().filter(node -> node.id() == snapshot.defaultSinkId()).findFirst().orElse(null)
                : GnomeOutputState.resolve(snapshot.sinks(), output.deviceId(), devices);
        Map<String, Object> state = resolved == null
                ? new LinkedHashMap<>(Map.of("available", false, "id", output.deviceId(), "name", output.name()))
                : outputs.stream().filter(item -> resolved.name().equals(item.get("name")))
                    .findFirst().map(item -> withAvailability(item, true))
                    .orElseGet(() -> new LinkedHashMap<>(Map.of("available", false,
                            "id", output.deviceId(), "name", output.name())));
        GainNode gain = GnomeGraphOutputGain.forOutput(graph, output);
        if (gain == null) return state;
        state.put("volume", gain.gain());
        state.put("muted", gain.muted());
        state.put("graphGainNodeId", gain.id());
        return state;
    }

    private static Map<String, Object> withAvailability(Map<String, Object> value, boolean available) {
        Map<String, Object> copy = new LinkedHashMap<>(value);
        copy.put("available", available);
        return copy;
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
