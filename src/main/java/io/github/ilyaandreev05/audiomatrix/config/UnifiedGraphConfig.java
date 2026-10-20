package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creates and migrates the single active Audio Matrix graph. */
public final class UnifiedGraphConfig {
    public static final String ROUTE_ID = "main";
    public static final String SWITCH_ID = "octine-main-switch";
    public static final String MONITOR_OUTPUT_ID = "octine-monitor-output";
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
        Map<String, Object> monitorProfile = monitorProfile(config, selectedProfile);
        AudioGraph oldActive = route(config, String.valueOf(monitorProfile.getOrDefault("selected_route_id", "")));
        if (oldActive == null) oldActive = route(config, "octine-monitor");
        if (oldActive == null) oldActive = route(config, String.valueOf(config.getOrDefault("active_route", "")));
        AudioGraph oldShared = null;
        try { oldShared = GraphCommonVibro.graph(config); }
        catch (RuntimeException ignored) { }

        String monitorDevice = device(oldActive, MONITOR_OUTPUT_ID);
        if (monitorDevice.isBlank()) monitorDevice = deviceByName(oldActive, "monitor", "display", "экран");
        if (monitorDevice.isBlank()) monitorDevice = firstText(monitorProfile.get("default_output"), "system_default");
        String vibroDevice = device(oldShared, GraphCommonVibro.VIBRO_OUTPUT_ID);
        if (vibroDevice.isBlank()) vibroDevice = device(oldActive, GraphCommonVibro.VIBRO_OUTPUT_ID);
        if (monitorDevice.equals(vibroDevice)) vibroDevice = "";

        double musicVolume = mixerInput(oldShared, GraphCommonVibro.VIBRO_MIXER_ID, "bass", 0.6);
        boolean musicMute = mixerInputMuted(oldShared, GraphCommonVibro.VIBRO_MIXER_ID, "bass");
        double eventVolume = mixerInput(oldShared, GraphCommonVibro.VIBRO_MIXER_ID, "events", 0.65);
        boolean eventMute = mixerInputMuted(oldShared, GraphCommonVibro.VIBRO_MIXER_ID, "events");
        double totalVolume = gain(oldShared, GraphCommonVibro.VIBRO_GAIN_ID, 0.6);
        boolean totalMute = muted(oldShared, GraphCommonVibro.VIBRO_GAIN_ID);
        double mainApps = mixerInput(oldActive, GraphCommonVibro.MAIN_MIXER_ID, "applications", 1.0);
        boolean mainAppsMute = mixerInputMuted(oldActive, GraphCommonVibro.MAIN_MIXER_ID, "applications");
        double mainAdditional = mixerInput(oldActive, GraphCommonVibro.MAIN_MIXER_ID, "additional", 1.0);
        boolean mainAdditionalMute = mixerInputMuted(oldActive, GraphCommonVibro.MAIN_MIXER_ID, "additional");
        boolean limiterEnabled = limiterEnabled(oldShared);

        AudioGraph next = UnifiedGraphFactory.create(monitorDevice, vibroDevice, musicVolume, musicMute,
                eventVolume, eventMute, totalVolume, totalMute,
                mainApps, mainAppsMute, mainAdditional, mainAdditionalMute, limiterEnabled);
        next = withDeviceLevels(next, config);

        Map<String, Object> profile = DefaultConfig.map("id", "main", "name", "Основной",
                "mode", "multi-output", "selected_route_id", ROUTE_ID, "default_output", monitorDevice,
                "active_buses", new ArrayList<>(), "active_routes", new ArrayList<>(),
                "device_overrides", DefaultConfig.map());
        Map<String, Object> encoded = new java.util.LinkedHashMap<>(GraphCodec.toMap(next));
        encoded.put("profile_id", "main");
        encoded.put("group_id", "main");
        config.put("profiles", new java.util.ArrayList<>(List.of(profile)));
        config.put("active_profile", "main");
        config.put("route_groups", new java.util.ArrayList<>(List.of(DefaultConfig.map(
                "id", "main", "name", "Основной", "profile_id", "main"))));
        config.put("route_library", new java.util.ArrayList<>(List.of(encoded)));
        config.put("active_route", ROUTE_ID);
        config.remove("common_vibro_graph");
        config.remove("octine_profiles_available");
        UnifiedGraphPins.migrate(config, next);
        QuickSettingsConfig.migrate(config);

        // Keep the generated runtime route table aligned with the selected graph. The selected
        // branch sends ordinary applications to the monitor only; EVENT remains independently routed.
        Map<String, Object> compiled = io.github.ilyaandreev05.audiomatrix.graph.GraphPipeWireCompiler.compile(next, config);
        config.clear();
        config.putAll(compiled);
    }

    public static void installDefault(Map<String, Object> config) {
        AudioGraph graph = defaults();
        Map<String, Object> profile = DefaultConfig.map("id", "main", "name", "Основной",
                "mode", "multi-output", "selected_route_id", ROUTE_ID, "default_output", "system_default",
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

    private static AudioGraph withDeviceLevels(AudioGraph graph, Map<String, Object> config) {
        List<io.github.ilyaandreev05.audiomatrix.graph.GraphNode> nodes = graph.nodes().stream().map(node -> {
            if (!(node instanceof DeviceSlotNode slot)) return node;
            Map<String, Object> binding = deviceBinding(config, slot.deviceId());
            double volume = binding.get("volume") instanceof Number volumeValue ? volumeValue.doubleValue() : slot.volume();
            boolean muted = binding.get("mute") instanceof Boolean muteValue ? muteValue : slot.muted();
            if (!Double.isFinite(volume) || volume < 0 || volume > 1) volume = slot.volume();
            return new DeviceSlotNode(slot.id(), slot.name(), slot.deviceId(),
                    slot.inputs().getFirst().channels(), volume, muted);
        }).toList();
        return new AudioGraph(graph.id(), graph.name(), nodes, graph.connections());
    }

    private static Map<String, Object> deviceBinding(Map<String, Object> config, String deviceId) {
        if (deviceId == null || deviceId.isBlank() || "system_default".equals(deviceId)) return Map.of();
        String id = deviceId.startsWith("device:") ? deviceId.substring("device:".length()) : deviceId;
        return maps(config.get("devices")).stream().filter(item -> id.equals(item.get("id")))
                .findFirst().orElse(Map.of());
    }
    private static boolean muted(AudioGraph graph, String nodeId) {
        if (graph == null) return false;
        return graph.nodes().stream().filter(node -> node.id().equals(nodeId) && node instanceof GainNode)
                .map(GainNode.class::cast).anyMatch(GainNode::muted);
    }
    private static double mixerInput(AudioGraph graph, String nodeId, String input, double fallback) {
        if (graph == null) return fallback;
        return graph.nodes().stream().filter(node -> node.id().equals(nodeId) && node instanceof MixerNode)
                .map(MixerNode.class::cast).mapToDouble(node -> node.inputGains().getOrDefault(input, fallback)).findFirst().orElse(fallback);
    }
    private static boolean mixerInputMuted(AudioGraph graph, String nodeId, String input) {
        if (graph == null) return false;
        return graph.nodes().stream().filter(node -> node.id().equals(nodeId) && node instanceof MixerNode)
                .map(MixerNode.class::cast).anyMatch(node -> node.inputMuted(input));
    }
    private static boolean limiterEnabled(AudioGraph graph) {
        if (graph == null) return true;
        return graph.nodes().stream().filter(node -> node instanceof FilterNode filter && "limiter".equals(filter.processor()))
                .map(FilterNode.class::cast).findFirst().map(FilterNode::enabled).orElse(true);
    }
    private static String device(AudioGraph graph, String id) {
        if (graph == null) return "";
        return graph.nodes().stream().filter(node -> node.id().equals(id)).map(node ->
                node instanceof DeviceSlotNode slot ? slot.deviceId() : node instanceof OutputNode output ? output.deviceId() : "")
                .filter(value -> !value.isBlank()).findFirst().orElse("");
    }
    private static String deviceByName(AudioGraph graph, String... names) {
        if (graph == null) return "";
        return graph.nodes().stream().filter(node -> node instanceof DeviceSlotNode || node instanceof OutputNode)
                .filter(node -> { String value = (node.id() + " " + node.name()).toLowerCase();
                    return java.util.Arrays.stream(names).anyMatch(value::contains); })
                .map(node -> node instanceof DeviceSlotNode slot ? slot.deviceId() : ((OutputNode) node).deviceId())
                .filter(value -> !value.isBlank()).findFirst().orElse("");
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
    private static Map<String, Object> monitorProfile(Map<String, Object> config, Map<String, Object> fallback) {
        return maps(config.get("profiles")).stream().filter(profile -> {
            String id = String.valueOf(profile.getOrDefault("id", "")).toLowerCase();
            String name = String.valueOf(profile.getOrDefault("name", "")).toLowerCase();
            return id.equals("monitor") || name.contains("монитор") || name.contains("monitor");
        }).findFirst().orElse(fallback);
    }
    private static String firstText(Object raw, String fallback) {
        String value = raw == null ? "" : String.valueOf(raw).trim();
        return value.isBlank() ? fallback : value;
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
