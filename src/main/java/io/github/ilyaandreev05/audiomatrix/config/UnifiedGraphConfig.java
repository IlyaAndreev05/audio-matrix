package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.RouterNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Creates and migrates the single active Audio Matrix graph. */
public final class UnifiedGraphConfig {
    public static final String ROUTE_ID = "main";
    public static final String SWITCH_ID = "octine-main-switch";
    public static final String MONITOR_OUTPUT_ID = "octine-monitor-output";
    public static final String OUTPUTS_GENERATOR_ID = "octine-outputs-generator";
    public static final String HAPTIC_OUTPUTS_GENERATOR_ID = "octine-vibro-output-generator";
    public static final String HAPTIC_MUSIC_PORT_ID = "haptic";
    public static final String MUSIC_GAIN_ID = "octine-vibro-music-volume";
    public static final String EVENT_GAIN_ID = "octine-vibro-events-volume";
    public static final String EVENT_SOURCE_ID = "octine-event-source";
    public static final String LIMITER_ID = "octine-vibro-limiter";

    private UnifiedGraphConfig() {}

    public static AudioGraph defaults() {
        return UnifiedGraphFactory.defaults();
    }

    /** Replaces old profile stacks after their full pre-migration copy has been archived. */
    public static void migrate(Map<String, Object> config) {
        LegacyLinearScaleMigration.cube(config);
        Map<String, Object> selectedProfile = activeProfile(config);
        AudioGraph oldActive = route(config, String.valueOf(selectedProfile.getOrDefault("selected_route_id", "")));
        if (oldActive == null) oldActive = route(config, String.valueOf(config.getOrDefault("active_route", "")));
        AudioGraph oldShared = null;
        try { oldShared = GraphCommonVibro.graph(config); }
        catch (RuntimeException ignored) { }

        String hapticDeviceId = hapticDeviceId(config, oldShared, oldActive);
        DeviceIdentity hapticIdentity = identity(config, hapticDeviceId);
        Map<String, Object> normalInstances = new LinkedHashMap<>();
        Map<String, Object> hapticInstances = new LinkedHashMap<>();
        List<String> outputPorts = new ArrayList<>();
        List<String> selectedOutputPorts = new ArrayList<>();
        List<String> excludedSelectors = new ArrayList<>(List.of("device:haptic-assigned"));
        String hapticSelector = "device:haptic-assigned";
        String hapticKey = "";
        if (hapticIdentity != null && hapticIdentity.reliable()) {
            hapticKey = "device:" + hapticIdentity.fingerprint();
            hapticSelector = hapticKey;
            hapticInstances.put(hapticKey, DynamicInstanceRegistry.seedOutputInstanceRecord(
                    HAPTIC_OUTPUTS_GENERATOR_ID, hapticIdentity));
            excludedSelectors.clear();
            excludedSelectors.add(hapticKey);
            setDeviceRole(config, hapticDeviceId, "haptic_output");
        }

        if (oldActive != null) for (GraphNode node : oldActive.nodes()) {
            String deviceId = outputDeviceId(node);
            if (deviceId.isBlank()) continue;
            DeviceIdentity identity = identity(config, deviceId);
            if (identity == null || !identity.reliable()) continue;
            String key = "device:" + identity.fingerprint();
            if (key.equals(hapticKey)) continue;
            normalInstances.putIfAbsent(key, DynamicInstanceRegistry.seedOutputInstanceRecord(
                    OUTPUTS_GENERATOR_ID, identity));
            String port = DynamicInstanceRegistry.stableOutputPortId(OUTPUTS_GENERATOR_ID, identity.fingerprint());
            if (!outputPorts.contains(port)) outputPorts.add(port);
            if (isOutputSelected(oldActive, node.id()) && !selectedOutputPorts.contains(port))
                selectedOutputPorts.add(port);
        }

        FilterNode limiter = limiterNode(oldShared);
        if (limiter == null) limiter = limiterNode(oldActive);
        if (limiter == null) limiter = UnifiedGraphFactory.defaultLimiter();
        List<FilterNode> hapticFilters = hapticMusicFilters(oldShared);
        for (FilterNode filter : hapticMusicFilters(oldActive))
            if (hapticFilters.stream().noneMatch(existing -> existing.id().equals(filter.id()))) hapticFilters.add(filter);
        List<FilterNode> routeFilters = new ArrayList<>();
        if (oldActive != null) for (GraphNode node : oldActive.nodes()) {
            if (!(node instanceof FilterNode filter) || filter.id().equals(limiter.id())) continue;
            if (hapticFilters.stream().noneMatch(existing -> existing.id().equals(filter.id())))
                routeFilters.add(filter);
        }

        double musicVolume = gain(oldActive, MUSIC_GAIN_ID,
                gain(oldShared, MUSIC_GAIN_ID,
                        mixerInput(oldShared, GraphCommonVibro.VIBRO_MIXER_ID, "bass",
                                mixerInput(oldShared, GraphCommonVibro.VIBRO_MIXER_ID, "music", 0.6))));
        boolean musicMute = hasGain(oldActive, MUSIC_GAIN_ID) ? muted(oldActive, MUSIC_GAIN_ID)
                : hasGain(oldShared, MUSIC_GAIN_ID) ? muted(oldShared, MUSIC_GAIN_ID)
                : mixerInputMuted(oldShared, GraphCommonVibro.VIBRO_MIXER_ID, "bass")
                        || mixerInputMuted(oldShared, GraphCommonVibro.VIBRO_MIXER_ID, "music");
        double eventVolume = gain(oldActive, EVENT_GAIN_ID,
                gain(oldShared, EVENT_GAIN_ID,
                        mixerInput(oldShared, GraphCommonVibro.VIBRO_MIXER_ID, "events", 0.65)));
        boolean eventMute = hasGain(oldActive, EVENT_GAIN_ID) ? muted(oldActive, EVENT_GAIN_ID)
                : hasGain(oldShared, EVENT_GAIN_ID) ? muted(oldShared, EVENT_GAIN_ID)
                : mixerInputMuted(oldShared, GraphCommonVibro.VIBRO_MIXER_ID, "events");
        double totalVolume = gain(oldShared, GraphCommonVibro.VIBRO_GAIN_ID,
                gain(oldActive, GraphCommonVibro.VIBRO_GAIN_ID, 0.6));
        boolean totalMute = hasGain(oldShared, GraphCommonVibro.VIBRO_GAIN_ID)
                ? muted(oldShared, GraphCommonVibro.VIBRO_GAIN_ID)
                : muted(oldActive, GraphCommonVibro.VIBRO_GAIN_ID);
        double mainApps = mixerInput(oldActive, GraphCommonVibro.MAIN_MIXER_ID, "applications", 1.0);
        boolean mainAppsMute = mixerInputMuted(oldActive, GraphCommonVibro.MAIN_MIXER_ID, "applications");
        double mainAdditional = mixerInput(oldActive, GraphCommonVibro.MAIN_MIXER_ID, "additional", 1.0);
        boolean mainAdditionalMute = mixerInputMuted(oldActive, GraphCommonVibro.MAIN_MIXER_ID, "additional");
        String eventSelector = sourceSelector(oldActive, sourceSelector(oldShared,
                "application:" + io.github.ilyaandreev05.audiomatrix.graph.EventSoundStudioSource.APPLICATION_ID));
        UnifiedGraphFactory.PreservedState preserved = new UnifiedGraphFactory.PreservedState(
                normalInstances, hapticInstances, outputPorts, selectedOutputPorts, excludedSelectors,
                !hapticKey.isBlank() && (hasEnabledInputPath(oldShared, GraphCommonVibro.VIBRO_MIXER_ID, "bass", "music")
                        || hasEnabledInputPath(oldActive, GraphCommonVibro.VIBRO_MIXER_ID, "bass", "music")),
                false, hapticSelector, eventSelector, routeFilters, hapticFilters, limiter);

        AudioGraph next = UnifiedGraphFactory.create(musicVolume, musicMute, eventVolume, eventMute,
                totalVolume, totalMute, mainApps, mainAppsMute, mainAdditional, mainAdditionalMute, preserved);
        Map<String, Object> profile = DefaultConfig.map("id", "main", "name", "Основной",
                "mode", "multi-output", "selected_route_id", ROUTE_ID, "default_output", "",
                "active_buses", new ArrayList<>(), "active_routes", new ArrayList<>(),
                "device_overrides", DefaultConfig.map());
        Map<String, Object> encoded = new LinkedHashMap<>(GraphCodec.toMap(next));
        encoded.put("profile_id", "main");
        encoded.put("group_id", "main");
        config.put("profiles", new ArrayList<>(List.of(profile)));
        config.put("active_profile", "main");
        config.put("route_groups", new ArrayList<>(List.of(DefaultConfig.map(
                "id", "main", "name", "Основной", "profile_id", "main"))));
        config.put("route_library", new ArrayList<>(List.of(encoded)));
        config.put("active_route", ROUTE_ID);
        config.remove("common_vibro_graph");
        config.remove("octine_profiles_available");
        UnifiedGraphPins.migrate(config, next);
        QuickSettingsConfig.migrate(config);

        Map<String, Object> compiled = io.github.ilyaandreev05.audiomatrix.graph.GraphPipeWireCompiler.compile(next, config);
        config.clear();
        config.putAll(compiled);
    }

    public static void installDefault(Map<String, Object> config) {
        AudioGraph graph = defaults();
        Map<String, Object> profile = DefaultConfig.map("id", "main", "name", "Основной",
                "mode", "multi-output", "selected_route_id", ROUTE_ID, "default_output", "",
                "active_buses", new ArrayList<>(), "active_routes", new ArrayList<>(),
                "device_overrides", DefaultConfig.map());
        Map<String, Object> route = new LinkedHashMap<>(GraphCodec.toMap(graph));
        route.put("profile_id", "main");
        route.put("group_id", "main");
        config.put("profiles", new ArrayList<>(List.of(profile)));
        config.put("active_profile", "main");
        config.put("route_groups", new ArrayList<>(List.of(DefaultConfig.map(
                "id", "main", "name", "Основной", "profile_id", "main"))));
        config.put("route_library", new ArrayList<>(List.of(route)));
        config.put("active_route", ROUTE_ID);
        QuickSettingsConfig.migrate(config);
        UnifiedGraphPins.installDefaults(config, graph);
    }



    private static double gain(AudioGraph graph, String nodeId, double fallback) {
        if (graph == null) return fallback;
        return graph.nodes().stream().filter(node -> node.id().equals(nodeId) && node instanceof GainNode)
                .map(GainNode.class::cast).mapToDouble(GainNode::gain).findFirst().orElse(fallback);
    }

    private static boolean hasGain(AudioGraph graph, String nodeId) {
        return graph != null && graph.nodes().stream()
                .anyMatch(node -> node.id().equals(nodeId) && node instanceof GainNode);
    }

    private static boolean muted(AudioGraph graph, String nodeId) {
        if (graph == null) return false;
        return graph.nodes().stream().filter(node -> node.id().equals(nodeId) && node instanceof GainNode)
                .map(GainNode.class::cast).anyMatch(GainNode::muted);
    }

    private static double mixerInput(AudioGraph graph, String nodeId, String input, double fallback) {
        if (graph == null) return fallback;
        return graph.nodes().stream().filter(node -> node.id().equals(nodeId) && node instanceof MixerNode)
                .map(MixerNode.class::cast)
                .mapToDouble(node -> node.inputGains().getOrDefault(input, fallback)).findFirst().orElse(fallback);
    }

    private static boolean mixerInputMuted(AudioGraph graph, String nodeId, String input) {
        if (graph == null) return false;
        return graph.nodes().stream().filter(node -> node.id().equals(nodeId) && node instanceof MixerNode)
                .map(MixerNode.class::cast).anyMatch(node -> node.inputMuted(input));
    }

    private static FilterNode limiterNode(AudioGraph graph) {
        if (graph == null) return null;
        return graph.nodes().stream().filter(FilterNode.class::isInstance).map(FilterNode.class::cast)
                .filter(filter -> filter.id().equals(LIMITER_ID)).findFirst()
                .orElseGet(() -> graph.nodes().stream().filter(FilterNode.class::isInstance)
                        .map(FilterNode.class::cast).filter(filter -> "limiter".equals(filter.processor()))
                        .findFirst().orElse(null));
    }

    private static List<FilterNode> hapticMusicFilters(AudioGraph graph) {
        if (graph == null) return new ArrayList<>();
        List<FilterNode> result = new ArrayList<>();
        for (GraphNode node : graph.nodes()) {
            if (!(node instanceof FilterNode filter)) continue;
            if (pathToInput(graph, filter.id(), GraphCommonVibro.VIBRO_MIXER_ID,
                    Set.of("bass", "music"), new HashSet<>())) result.add(filter);
        }
        return result;
    }

    private static boolean pathToInput(AudioGraph graph, String fromNode, String targetNode,
                                       Set<String> targetPorts, Set<String> visited) {
        if (graph == null || !visited.add(fromNode)) return false;
        for (GraphConnection edge : graph.connections()) {
            if (!edge.enabled() || !edge.fromNode().equals(fromNode)) continue;
            if (edge.toNode().equals(targetNode) && targetPorts.contains(edge.toPort())) return true;
            if (pathToInput(graph, edge.toNode(), targetNode, targetPorts, new HashSet<>(visited))) return true;
        }
        return false;
    }

    private static boolean hasEnabledInputPath(AudioGraph graph, String nodeId, String... inputPorts) {
        if (graph == null) return false;
        Set<String> ports = Set.of(inputPorts);
        return graph.connections().stream().filter(GraphConnection::enabled)
                .filter(edge -> edge.toNode().equals(nodeId) && ports.contains(edge.toPort()))
                .anyMatch(edge -> hasActivePath(graph, edge.fromNode(), new HashSet<>()));
    }

    private static boolean isOutputSelected(AudioGraph graph, String nodeId) {
        return graph != null && hasActivePath(graph, nodeId, new HashSet<>());
    }

    private static boolean hasActivePath(AudioGraph graph, String nodeId, Set<String> visited) {
        if (graph == null || !visited.add(nodeId)) return false;
        GraphNode current = graph.nodes().stream().filter(node -> node.id().equals(nodeId)).findFirst().orElse(null);
        if (current == null) return false;
        if (current instanceof SourceNode || "dynamic_generator".equals(current.type())) return true;
        for (GraphConnection edge : graph.connections()) {
            if (!edge.enabled() || !edge.toNode().equals(nodeId)) continue;
            GraphNode source = graph.nodes().stream().filter(node -> node.id().equals(edge.fromNode()))
                    .findFirst().orElse(null);
            if (source instanceof RouterNode router) {
                for (String link : router.activeLinks()) {
                    String[] parts = link.split("->", 2);
                    if (parts.length != 2 || !parts[1].equals(edge.fromPort())) continue;
                    boolean connected = graph.connections().stream().filter(GraphConnection::enabled)
                            .filter(input -> input.toNode().equals(router.id()) && input.toPort().equals(parts[0]))
                            .anyMatch(input -> hasActivePath(graph, input.fromNode(), new HashSet<>(visited)));
                    if (connected) return true;
                }
            } else if (hasActivePath(graph, edge.fromNode(), new HashSet<>(visited))) return true;
        }
        return false;
    }

    private static String hapticDeviceId(Map<String, Object> config, AudioGraph oldShared, AudioGraph oldActive) {
        List<String> roleIds = maps(config.get("devices")).stream()
                .filter(device -> "haptic_output".equals(device.get("role")))
                .map(device -> String.valueOf(device.getOrDefault("id", "")))
                .filter(id -> !id.isBlank()).distinct().toList();
        if (roleIds.size() == 1) return roleIds.getFirst();
        if (roleIds.size() > 1) return "";
        String id = device(oldShared, GraphCommonVibro.VIBRO_OUTPUT_ID);
        if (id.isBlank()) id = device(oldActive, GraphCommonVibro.VIBRO_OUTPUT_ID);
        return "system_default".equals(id) ? "" : id;
    }

    private static DeviceIdentity identity(Map<String, Object> config, String deviceId) {
        Object rawIdentity = deviceBinding(config, deviceId).get("identity");
        if (!(rawIdentity instanceof Map<?, ?> map)) return null;
        DeviceIdentity identity = DeviceIdentity.fromMap(object(map));
        return identity.reliable() ? identity : null;
    }

    private static void setDeviceRole(Map<String, Object> config, String deviceId, String role) {
        if (deviceId == null || deviceId.isBlank()) return;
        String id = deviceId.startsWith("device:") ? deviceId.substring("device:".length()) : deviceId;
        for (Map<String, Object> device : maps(config.get("devices"))) {
            if (id.equals(device.get("id"))) {
                device.put("role", role);
                return;
            }
        }
    }

    private static Map<String, Object> deviceBinding(Map<String, Object> config, String deviceId) {
        if (deviceId == null || deviceId.isBlank() || "system_default".equals(deviceId)) return Map.of();
        String id = deviceId.startsWith("device:") ? deviceId.substring("device:".length()) : deviceId;
        return maps(config.get("devices")).stream().filter(item -> id.equals(item.get("id")))
                .findFirst().orElse(Map.of());
    }

    private static String device(AudioGraph graph, String id) {
        if (graph == null) return "";
        return graph.nodes().stream().filter(node -> node.id().equals(id)).map(UnifiedGraphConfig::outputDeviceId)
                .filter(value -> !value.isBlank()).findFirst().orElse("");
    }

    private static String outputDeviceId(GraphNode node) {
        if (node instanceof DeviceSlotNode slot) return slot.deviceId();
        if (node instanceof OutputNode output) return output.deviceId();
        return "";
    }

    private static String sourceSelector(AudioGraph graph, String fallback) {
        if (graph == null) return fallback;
        return graph.nodes().stream().filter(node -> EVENT_SOURCE_ID.equals(node.id()) && node instanceof SourceNode)
                .map(SourceNode.class::cast).map(SourceNode::selector).findFirst().orElse(fallback);
    }

    private static AudioGraph route(Map<String, Object> config, String id) {
        if (id.isBlank()) return null;
        Map<String, Object> encoded = maps(config.get("route_library")).stream()
                .filter(item -> id.equals(item.get("id"))).findFirst().orElse(null);
        if (encoded == null) return null;
        try { return GraphCodec.fromMap(encoded); } catch (RuntimeException ignored) { return null; }
    }

    private static Map<String, Object> activeProfile(Map<String, Object> config) {
        String id = String.valueOf(config.getOrDefault("active_profile", ""));
        return maps(config.get("profiles")).stream().filter(profile -> id.equals(profile.get("id"))).findFirst()
                .orElseGet(() -> maps(config.get("profiles")).stream().findFirst().orElse(DefaultConfig.map()));
    }

    private static Map<String, Object> object(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object raw) {
        return raw instanceof List<?> list ? (List<Map<String, Object>>) list : new ArrayList<>();
    }
}
