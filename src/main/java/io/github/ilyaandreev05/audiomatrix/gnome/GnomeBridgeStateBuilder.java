package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceSelectorMatcher;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds one immutable observable payload before the bridge assigns its epoch/sequence. */
final class GnomeBridgeStateBuilder {
    private GnomeBridgeStateBuilder() {}

    static Map<String, Object> build(PipeWireBackend.Snapshot snapshot, Map<String, Object> config,
                                     long configRevision, List<BrowserAdapter> browserAdapters,
                                     DynamicInstanceRegistry.ReconcileResult graphState) {
        AudioGraph effectiveGraph = graphState == null ? null : graphState.effectiveGraph();
        Map<DynamicInstanceRegistry.InstanceAddress, DynamicInstanceRegistry.RuntimeInstance> instances =
                graphState == null ? Map.of() : graphState.instances();
        Map<String, Object> profile = activeProfile(config);
        String configured = String.valueOf(profile.getOrDefault("default_output", "system_default"));
        int defaultId = snapshot.defaultSinkId();
        List<Map<String, Object>> devices = maps(config.get("devices"));
        List<Map<String, Object>> outputs = GnomeOutputState.devices(snapshot, devices);
        AudioNode active = DeviceSelectorMatcher.resolve(snapshot.sinks(), configured, devices);
        if (active != null && active.name().startsWith("audiomatrix_")) active = null;
        if (active == null) active = snapshot.sinks().stream()
                .filter(node -> node.id() == defaultId && !node.name().startsWith("audiomatrix_"))
                .findFirst().orElse(null);
        List<Map<String, Object>> quickControls = GnomeControlBridge.quickControls(config);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("apiVersion", GnomeControlBridge.API_VERSION);
        state.put("revision", Long.toString(configRevision));
        state.put("connected", snapshot.connected());
        state.put("language", language(config));
        state.put("outputs", outputs);
        Map<String, Object> mixer = object(config.get("mixer"));
        state.put("master", Map.of(
                "volume", number(mixer.get("global_volume"), 1.0),
                "muted", Boolean.TRUE.equals(mixer.get("global_mute"))));
        Map<String, Object> rootConfig = object(object(config.get("quick_settings")).get("root"));
        boolean rootEnabled = !Boolean.FALSE.equals(rootConfig.get("enabled"));
        boolean showMaster = !Boolean.FALSE.equals(rootConfig.get("show_master"));
        boolean hideNative = Boolean.TRUE.equals(rootConfig.get("hide_native_volume"));
        List<Map<String, Object>> rootOutputs = outputs;
        Object selected = rootConfig.get("outputs");
        if (selected instanceof List<?> list && !list.isEmpty() && !list.contains("all")) {
            Set<String> wanted = list.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
            rootOutputs = outputs.stream().filter(output ->
                    wanted.contains(String.valueOf(output.get("id"))) ||
                    wanted.contains(String.valueOf(output.get("nodeName")))).toList();
        }
        state.put("root", Map.of(
                "enabled", rootEnabled, "showMaster", showMaster,
                "hideNativeVolume", hideNative, "outputs", rootOutputs));
        state.put("selectedOutput", active == null ? "system_default" : active.name());
        state.put("muted", active != null && active.muted());
        state.put("mode", profile.getOrDefault("mode", "off"));
        state.put("profile", profile.getOrDefault("name", ""));
        state.put("profiles", GnomeBridgeProfileState.profiles(config));
        state.put("activeProfileId", config.getOrDefault("active_profile", ""));
        state.put("activeRoute", activeRouteName(config));
        state.put("activeRouteId", config.getOrDefault("active_route", ""));
        state.put("routes", GnomeQuickSettingsState.routes(config));
        state.put("quickGroups", GnomeQuickSettingsState.groups(config));
        state.put("quickControls", quickControls);
        state.put("channelRoutes", GnomeChannelRouteState.channelRoutes(
                snapshot, config, profile, active, defaultId, devices));
        List<Map<String, Object>> applications = GnomeApplicationProjection.applications(snapshot, config,
                browserAdapters, graphState);
        state.put("applications", applications);
        state.put("quickPins", GnomeQuickSettingsPins.build(config, snapshot, quickControls, applications, outputs,
                effectiveGraph, instances));
        state.put("graph", GnomeGraphState.build(config, snapshot, effectiveGraph, instances));
        return state;
    }

    private static Map<String, Object> activeProfile(Map<String, Object> config) {
        String id = String.valueOf(config.get("active_profile"));
        Object list = config.get("profiles");
        if (list instanceof List<?> profiles) for (Object item : profiles) {
            if (item instanceof Map<?, ?> profile && id.equals(profile.get("id"))) {
                Map<String, Object> copy = new LinkedHashMap<>();
                profile.forEach((key, value) -> copy.put(String.valueOf(key), value));
                return copy;
            }
        }
        return Map.of();
    }

    private static String activeRouteName(Map<String, Object> config) {
        String id = String.valueOf(config.getOrDefault("active_route", ""));
        return maps(config.get("route_library")).stream().filter(item -> id.equals(item.get("id")))
                .findFirst().map(item -> String.valueOf(item.getOrDefault("name", id))).orElse(id);
    }

    private static String language(Map<String, Object> config) {
        Object ui = config.get("ui");
        return ui instanceof Map<?, ?> preferences && "en".equals(preferences.get("language")) ? "en" : "ru";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number && Double.isFinite(number.doubleValue())
                ? number.doubleValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
