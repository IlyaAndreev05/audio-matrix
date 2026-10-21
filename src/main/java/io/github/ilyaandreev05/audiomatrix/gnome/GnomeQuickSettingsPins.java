package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.AudioMatrixOwnedStream;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.RouterNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

final class GnomeQuickSettingsPins {
    private static final Logger LOGGER = Logger.getLogger(GnomeQuickSettingsPins.class.getName());
    private GnomeQuickSettingsPins() {}

    static List<Map<String, Object>> build(Map<String, Object> config, PipeWireBackend.Snapshot snapshot,
                                           List<Map<String, Object>> quickControls,
                                           List<Map<String, Object>> applications,
                                           List<Map<String, Object>> outputs) {
        return build(config, snapshot, quickControls, applications, outputs, null, Map.of());
    }

    static List<Map<String, Object>> build(Map<String, Object> config, PipeWireBackend.Snapshot snapshot,
                                           List<Map<String, Object>> quickControls,
                                           List<Map<String, Object>> applications,
                                           List<Map<String, Object>> outputs,
                                           DynamicInstanceRegistry.ReconcileResult reconciliation) {
        return reconciliation == null
                ? build(config, snapshot, quickControls, applications, outputs, null, Map.of())
                : build(config, snapshot, quickControls, applications, outputs, reconciliation.effectiveGraph(),
                        reconciliation.instances());
    }

    static List<Map<String, Object>> build(Map<String, Object> config, PipeWireBackend.Snapshot snapshot,
                                           List<Map<String, Object>> quickControls,
                                           List<Map<String, Object>> applications,
                                           List<Map<String, Object>> outputs, AudioGraph effectiveGraph,
                                           Map<DynamicInstanceRegistry.InstanceAddress,
                                                   DynamicInstanceRegistry.RuntimeInstance> instances) {
        Map<DynamicInstanceRegistry.InstanceAddress, DynamicInstanceRegistry.RuntimeInstance> runtimeInstances =
                instances == null ? Map.of() : instances;
        String routeId = String.valueOf(config.getOrDefault("active_route", ""));
        AudioGraph commonGraph = null;
        try { commonGraph = GraphCommonVibro.graph(config); }
        catch (RuntimeException invalid) {
            LOGGER.log(Level.WARNING, "Cannot decode the common graph for GNOME Quick Settings", invalid);
        }
        Map<String, AudioGraph> graphs = new LinkedHashMap<>();
        if (commonGraph != null) graphs.put(GraphCommonVibro.COMMON_ROUTE_ID, commonGraph);
        AudioGraph activeGraph = effectiveGraph == null ? routeGraph(config, routeId) : effectiveGraph;
        if (activeGraph != null) graphs.put(routeId, activeGraph);
        List<Map<String, Object>> pins = new ArrayList<>(QuickSettingsConfig.pins(config));
        addAutomaticGeneratedPins(config, routeId, activeGraph, runtimeInstances, pins);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> pin : pins) {
            String pinRouteId = String.valueOf(pin.getOrDefault("route_id", ""));
            if (!graphs.containsKey(pinRouteId)) {
                AudioGraph saved = routeGraph(config, pinRouteId);
                if (saved != null) graphs.put(pinRouteId, saved);
            }
            AudioGraph pinGraph = graphs.get(pinRouteId);
            if (pinGraph == null) continue;
            String nodeId = String.valueOf(pin.getOrDefault("node_id", ""));
            GraphNode node = pinGraph.nodes().stream().filter(item -> nodeId.equals(item.id())).findFirst().orElse(null);
            if (node == null) {
                LOGGER.warning("Quick Settings pin " + pin.get("id") + " references missing node " + nodeId
                        + " on route " + pinRouteId);
                continue;
            }
            String instanceKey = string(pin.get("instance_key"));
            DynamicInstanceRegistry.RuntimeInstance runtime = instanceKey.isEmpty() || !(node instanceof DynamicGeneratorNode)
                    ? null : runtimeInstances.get(new DynamicInstanceRegistry.InstanceAddress(node.id(), instanceKey));
            Map<String, Object> overrides = runtime == null ? Map.of() : object(runtime.overrides());
            if (Boolean.FALSE.equals(overrides.get("pinned"))) continue;
            Map<String, Object> state = pinState(pin, node);
            if (node instanceof DynamicGeneratorNode && !instanceKey.isEmpty())
                state.put("available", runtime != null && runtime.available());
            if (runtime != null) {
                applyInstanceOverrides(state, overrides);
                state.put("available", runtime.available());
                state.put("confidence", runtime.confidence());
                state.put("runtimeBinding", runtime.runtimeBinding());
            }
            state.put("controls", controlsFor(pin, quickControls));
            if (node instanceof RouterNode router && router.multiple())
                state.put("routeControl", routeControl(pinRouteId, router, pinGraph));
            if (node instanceof DynamicGeneratorNode generator) {
                List<Map<String, Object>> sourceApplications = generatedApplications(generator, applications,
                        pinGraph, pinRouteId, runtimeInstances, instanceKey, runtime);
                state.put("applications", sourceApplications);
                state.put("tabAdapterAvailable", sourceApplications.stream()
                        .anyMatch(app -> Boolean.TRUE.equals(app.get("tabAdapterAvailable"))));
                if (runtime != null)
                    addGeneratedGain(state, runtime, pinGraph, pinRouteId, applications);
            } else if (node instanceof SourceNode source) {
                List<Map<String, Object>> sourceApplications = applicationsFor(source.selector(), applications, pinGraph);
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
                state.put("gain", Map.of("routeId", pinRouteId,
                        "nodeId", gain.id(), "volume", gain.gain(), "muted", gain.muted()));
            if (node instanceof MixerNode mixer)
                state.put("mixerInputs", mixerInputs(pinGraph, mixer));
            result.add(state);
        }
        result.sort(java.util.Comparator.comparingInt(item -> number(item.get("order"), 0)));
        return List.copyOf(result);
    }

    private static void addAutomaticGeneratedPins(Map<String, Object> config, String routeId, AudioGraph graph,
            Map<DynamicInstanceRegistry.InstanceAddress, DynamicInstanceRegistry.RuntimeInstance> instances,
            List<Map<String, Object>> pins) {
        if (graph == null) return;
        Set<String> existing = new java.util.HashSet<>();
        for (Map<String, Object> pin : pins)
            existing.add(pinKey(String.valueOf(pin.getOrDefault("route_id", "")),
                    String.valueOf(pin.getOrDefault("node_id", "")), string(pin.get("instance_key"))));
        Map<String, Integer> nextOrder = new LinkedHashMap<>();
        for (Map<String, Object> pin : pins) {
            String groupId = String.valueOf(pin.getOrDefault("group_id", ""));
            nextOrder.merge(groupId, number(pin.get("order"), -1) + 1, Math::max);
        }
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        graph.nodes().forEach(node -> nodes.put(node.id(), node));
        for (DynamicInstanceRegistry.RuntimeInstance runtime : instances.values()) {
            GraphNode owner = nodes.get(runtime.generatorId());
            if (!(owner instanceof DynamicGeneratorNode generator)) continue;
            String instanceKey = runtime.instanceKey();
            String key = pinKey(routeId, generator.id(), instanceKey);
            if (existing.contains(key)) continue;
            Map<String, Object> overrides = object(runtime.overrides());
            if (Boolean.FALSE.equals(overrides.get("pinned"))) continue;
            boolean hasGeneratorPin = pins.stream().anyMatch(pin -> routeId.equals(pin.get("route_id"))
                    && generator.id().equals(pin.get("node_id")) && string(pin.get("instance_key")).isEmpty());
            if ("applications".equalsIgnoreCase(string(generator.registry())) && hasGeneratorPin) continue;
            if (!generator.autoPin() && !Boolean.TRUE.equals(overrides.get("pinned"))) continue;
            boolean showWhenOffline = Boolean.TRUE.equals(overrides.get("show_when_offline"));
            if (!runtime.available() && !showWhenOffline) continue;
            String groupId = string(overrides.getOrDefault("group_id", generator.groupId()));
            if (!containsGroup(config, groupId)) {
                Map<String, Object> parentPin = pins.stream()
                        .filter(pin -> routeId.equals(pin.get("route_id"))
                                && generator.id().equals(pin.get("node_id"))
                                && string(pin.get("instance_key")).isEmpty()).findFirst().orElse(null);
                groupId = parentPin == null ? firstGroupId(config) : string(parentPin.get("group_id"));
            }
            if (groupId.isEmpty()) continue;
            int order = overrides.get("order") instanceof Number value ? value.intValue()
                    : nextOrder.getOrDefault(groupId, 0);
            nextOrder.merge(groupId, order + 1, Math::max);
            String view = overrides.get("expanded") instanceof Boolean expanded
                    ? expanded ? "EXPANDED" : "COMPACT" : "DEFAULT";
            Map<String, Object> pin = new LinkedHashMap<>();
            pin.put("id", "generated:" + routeId + ":" + generator.id() + ":" + instanceKey);
            pin.put("route_id", routeId);
            pin.put("node_id", generator.id());
            pin.put("instance_key", instanceKey);
            pin.put("group_id", groupId);
            pin.put("order", order);
            pin.put("view", view);
            pin.put("control_ids", List.of());
            pin.put("pinned", Boolean.TRUE.equals(overrides.get("pinned")));
            for (String field : List.of("display_name", "icon", "show_children", "show_when_offline"))
                if (overrides.containsKey(field)) pin.put(field, overrides.get(field));
            Map<String, Object> binding = object(runtime.runtimeBinding());
            String runtimeName = string(binding.getOrDefault("applicationName", binding.get("nodeName")));
            if (!pin.containsKey("display_name") && !runtimeName.isBlank())
                pin.put("display_name", runtimeName);
            pins.add(pin);
            existing.add(key);
        }
    }

    private static List<Map<String, Object>> generatedApplications(DynamicGeneratorNode generator,
            List<Map<String, Object>> applications, AudioGraph graph, String routeId,
            Map<DynamicInstanceRegistry.InstanceAddress, DynamicInstanceRegistry.RuntimeInstance> instances,
            String pinnedInstanceKey, DynamicInstanceRegistry.RuntimeInstance pinnedRuntime) {
        String registry = string(generator.registry());
        if ("browser_tabs".equalsIgnoreCase(registry))
            return browserApplications(generator, applications, graph, routeId, pinnedInstanceKey, pinnedRuntime);
        if (!"applications".equalsIgnoreCase(registry)) return List.of();
        List<Map<String, Object>> selected = applicationsFor(generator.selector(), applications, graph);
        List<Map<String, Object>> result = new ArrayList<>(selected.size());
        for (Map<String, Object> application : selected) {
            DynamicInstanceRegistry.RuntimeInstance runtime = instances.values().stream()
                    .filter(instance -> generator.id().equals(instance.generatorId()))
                    .filter(instance -> runtimeBindingMatches(instance.runtimeBinding(), application)
                            || instance.instanceKey().equals(string(application.get("id"))))
                    .filter(instance -> pinnedInstanceKey.isEmpty()
                            || pinnedInstanceKey.equals(instance.instanceKey()))
                    .findFirst().orElse(null);
            if (runtime == null) continue;
            Map<String, Object> copy = new LinkedHashMap<>(application);
            copy.put("instanceKey", runtime.instanceKey());
            copy.put("target", Map.of("routeId", routeId, "nodeId", generator.id(),
                    "instanceKey", runtime.instanceKey()));
            copy.put("available", runtime.available());
            result.add(copy);
        }
        return List.copyOf(result);
    }

    private static List<Map<String, Object>> browserApplications(DynamicGeneratorNode generator,
            List<Map<String, Object>> applications, AudioGraph graph, String routeId, String instanceKey,
            DynamicInstanceRegistry.RuntimeInstance runtime) {
        List<Map<String, Object>> selected = applicationsFor(generator.selector(), applications, graph);
        if (instanceKey.isEmpty()) return selected;
        String captureKey = string(object(runtime == null ? null : runtime.runtimeBinding()).get("captureStreamKey"));
        if (captureKey.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> application : selected) {
            Map<String, Object> copy = new LinkedHashMap<>(application);
            List<Map<String, Object>> tabs = maps(application.get("tabs"));
            boolean nested = application.get("browserAdapter") instanceof Map<?, ?>;
            Map<String, Object> adapter = object(application.get("browserAdapter"));
            if (tabs.isEmpty()) tabs = maps(adapter.get("tabs"));
            List<Map<String, Object>> known = tabs.stream()
                    .filter(tab -> captureKey.equals(string(tab.get("captureStreamKey")))).toList();
            if (known.isEmpty()) continue;
            if (nested) {
                adapter.put("tabs", known);
                copy.put("browserAdapter", adapter);
            } else {
                copy.put("tabs", known);
            }
            copy.put("instanceKey", instanceKey);
            copy.put("target", Map.of("routeId", routeId, "nodeId", generator.id(), "instanceKey", instanceKey));
            result.add(copy);
        }
        return List.copyOf(result);
    }

    private static boolean runtimeBindingMatches(Object bindingValue, Map<String, Object> application) {
        Map<String, Object> binding = object(bindingValue);
        String appId = string(binding.get("applicationId"));
        String process = string(binding.get("processName"));
        String name = string(binding.get("applicationName"));
        return !appId.isEmpty() && (appId.equals(string(application.get("application_id")))
                || appId.equals(string(application.get("id"))))
                || !process.isEmpty() && process.equals(string(application.get("process")))
                || !name.isEmpty() && name.equals(string(application.get("name")));
    }

    private static Map<String, Object> applicationForRuntime(
            DynamicInstanceRegistry.RuntimeInstance runtime, List<Map<String, Object>> applications) {
        return applications.stream().filter(application ->
                runtimeBindingMatches(runtime.runtimeBinding(), application)
                        || runtime.instanceKey().equals(string(application.get("id"))))
                .findFirst().orElse(null);
    }

    private static void addGeneratedGain(Map<String, Object> state, DynamicInstanceRegistry.RuntimeInstance runtime,
                                         AudioGraph graph, String routeId,
                                         List<Map<String, Object>> applications) {
        String gainId = runtime.nodeIds().get("gain");
        if (gainId == null || gainId.isBlank()) return;
        GainNode gain = graph.nodes().stream().filter(node -> gainId.equals(node.id()) && node instanceof GainNode)
                .map(GainNode.class::cast).findFirst().orElse(null);
        if (gain == null) return;
        Map<String, Object> binding = object(runtime.runtimeBinding());
        boolean capturedTab = !string(binding.get("captureStreamKey")).isEmpty();
        Map<String, Object> application = capturedTab ? null : applicationForRuntime(runtime, applications);
        double volume = gain.gain();
        boolean muted = gain.muted();
        if (application != null) {
            if (application.get("volume") instanceof Number number) volume = number.doubleValue();
            if (application.get("muted") instanceof Boolean value) muted = value;
        } else {
            Map<String, Object> overrides = object(runtime.overrides());
            if (overrides.get("gain") instanceof Number number) volume = number.doubleValue();
            if (overrides.get("muted") instanceof Boolean value) muted = value;
        }
        state.put("gain", Map.of("routeId", routeId, "nodeId", gainId, "volume", volume, "muted", muted));
    }

    private static void applyInstanceOverrides(Map<String, Object> state, Map<String, Object> overrides) {
        if (overrides.get("pinned") instanceof Boolean value) state.put("pinned", value);
        if (overrides.get("group_id") instanceof String value) state.put("groupId", value);
        if (overrides.get("order") instanceof Number value) state.put("order", value.intValue());
        if (overrides.get("expanded") instanceof Boolean expanded)
            state.put("view", expanded ? "EXPANDED" : "COMPACT");
        if (overrides.get("display_name") instanceof String value && !value.isBlank())
            state.put("displayName", value);
        if (overrides.get("icon") instanceof String value && !value.isBlank()) state.put("icon", value);
        if (overrides.get("show_children") instanceof Boolean value) state.put("showChildren", value);
        if (overrides.get("show_when_offline") instanceof Boolean value) state.put("showWhenOffline", value);
    }

    private static String pinKey(String routeId, String nodeId, String instanceKey) {
        return routeId + "\u0000" + nodeId + "\u0000" + instanceKey;
    }

    private static boolean containsGroup(Map<String, Object> config, String groupId) {
        return !groupId.isEmpty() && QuickSettingsConfig.groups(config).stream()
                .anyMatch(group -> groupId.equals(group.get("id")));
    }

    private static String firstGroupId(Map<String, Object> config) {
        return QuickSettingsConfig.groups(config).stream()
                .min(java.util.Comparator.comparingInt(group -> number(group.get("order"), 0)))
                .map(group -> string(group.get("id"))).orElse("");
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> object(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map)
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
    private static AudioGraph routeGraph(Map<String, Object> config, String routeId) {
        if (routeId.isBlank()) return null;
        Map<String, Object> route = maps(config.get("route_library")).stream()
                .filter(item -> routeId.equals(item.get("id"))).findFirst().orElse(null);
        if (route == null) {
            LOGGER.warning("Quick Settings references missing graph route " + routeId);
            return null;
        }
        try { return GraphCodec.fromMap(route); }
        catch (RuntimeException invalid) {
            LOGGER.log(Level.WARNING, "Cannot decode graph route " + routeId + " for GNOME Quick Settings", invalid);
            return null;
        }
    }

    private static Map<String, Object> sourceGainState(String routeId, SourceNode source, GainNode gain) {
        return Map.of("routeId", routeId, "sourceNodeId", source.id(), "gainNodeId", gain.id(),
                "volume", gain.gain(), "muted", gain.muted());
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
        result.put("instanceKey", String.valueOf(pin.getOrDefault("instance_key", "")));
        if (node instanceof DynamicGeneratorNode generator) {
            result.put("registry", string(generator.registry()));
            result.put("template", string(generator.template()));
        }
        result.put("pinned", !Boolean.FALSE.equals(pin.get("pinned")));
        Object displayName = pin.get("display_name");
        if (displayName instanceof String value && !value.isBlank()) result.put("displayName", value);
        Object icon = pin.get("icon");
        if (icon instanceof String value && !value.isBlank()) result.put("icon", value);
        if (pin.get("show_children") instanceof Boolean value) result.put("showChildren", value);
        if (pin.get("show_when_offline") instanceof Boolean value) result.put("showWhenOffline", value);
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

    private static List<Map<String, Object>> applicationsFor(String selector,
                                                              List<Map<String, Object>> applications,
                                                              AudioGraph graph) {
        List<String> exactSelectors = graph.nodes().stream()
                .map(node -> node instanceof SourceNode source ? source.selector()
                        : node instanceof DynamicGeneratorNode generator ? generator.selector() : "")
                .filter(value -> !value.isBlank() && !value.equals("application:*")
                        && !value.equals("application:unassigned")).toList();
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
