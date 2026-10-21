package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioMatrixOwnedStream;
import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceSelectorMatcher;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        state.put("channelRoutes", GnomeControlBridge.channelRoutes(
                snapshot, config, profile, active, defaultId, devices));
        List<Map<String, Object>> applications = applications(snapshot, browserAdapters, graphState);
        state.put("applications", applications);
        state.put("quickPins", GnomeQuickSettingsPins.build(config, snapshot, quickControls, applications, outputs,
                effectiveGraph, instances));
        state.put("graph", GnomeGraphState.build(config, snapshot, effectiveGraph, instances));
        return state;
    }

    private static List<Map<String, Object>> applications(PipeWireBackend.Snapshot snapshot,
                                                           List<BrowserAdapter> browserAdapters,
                                                           DynamicInstanceRegistry.ReconcileResult graphState) {
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (AudioNode stream : snapshot.streams()) {
            if (AudioMatrixOwnedStream.matches(stream)) continue;
            String appId = stream.applicationId() == null ? "" : stream.applicationId();
            String process = stream.processName() == null ? "" : stream.processName();
            String key = !appId.isBlank() ? "app:" + appId
                    : !process.isBlank() ? "process:" + process : "stream:" + stream.name();
            Map<String, Object> group = groups.computeIfAbsent(key, ignored -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("id", key);
                created.put("name", stream.applicationName().isBlank() ? stream.description() : stream.applicationName());
                created.put("application_id", appId);
                created.put("process", process);
                created.put("streams", new ArrayList<Map<String, Object>>());
                created.put("volume", level(stream.volume()));
                created.put("muted", stream.muted());
                created.put("available", true);
                created.put("volumeAvailable", true);
                created.put("tabAdapterAvailable", false);
                return created;
            });
            @SuppressWarnings("unchecked") List<Map<String, Object>> children =
                    (List<Map<String, Object>>) group.get("streams");
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("id", stream.id());
            child.put("name", stream.name());
            child.put("description", stream.description());
            child.put("volume", level(stream.volume()));
            child.put("muted", stream.muted());
            children.add(child);
        }
        for (BrowserAdapter adapter : browserAdapters) {
            List<BrowserAdapter.BrowserSnapshot> browsers = adapter.snapshots();
            if (browsers.isEmpty()) continue;
            Map<String, Object> group = groups.values().stream()
                    .filter(item -> adapter.matches(String.valueOf(item.get("application_id")),
                            String.valueOf(item.get("process")), String.valueOf(item.get("name"))))
                    .filter(item -> !String.valueOf(item.get("application_id")).isBlank()
                            || !String.valueOf(item.get("process")).isBlank())
                    .findFirst().orElse(null);
            if (group == null) {
                String key = "browser-parent:" + adapter.id();
                group = groups.computeIfAbsent(key, ignored -> browserParent(adapter, key));
            }
            @SuppressWarnings("unchecked") List<Map<String, Object>> tabs =
                    (List<Map<String, Object>>) group.computeIfAbsent("browserTabs", ignored -> new ArrayList<Map<String, Object>>());
            for (BrowserAdapter.BrowserSnapshot browser : browsers)
                for (BrowserAdapter.BrowserTab tab : browser.tabs()) tabs.add(browserTab(browser, tab));
            boolean captured = tabs.stream().anyMatch(tab ->
                    tab.get("captureStreamKey") instanceof String key && !key.isBlank()
                            && capturedBindingReady(key, snapshot, graphState));
            @SuppressWarnings("unchecked") List<Map<String, Object>> streams =
                    (List<Map<String, Object>>) group.get("streams");
            Map<String, Object> capabilities = adapter.capabilities(streams.size(), captured);
            group.put("browserAdapter", capabilities);
            group.put("tabAdapterAvailable", Boolean.TRUE.equals(capabilities.get("independentTabVolume")));
        }
        return List.copyOf(groups.values());
    }

    private static boolean capturedBindingReady(String captureStreamKey, PipeWireBackend.Snapshot snapshot,
                                                DynamicInstanceRegistry.ReconcileResult graphState) {
        if (graphState == null) return false;
        String nodeName = "audio-matrix-chrome-" + captureStreamKey;
        boolean streamPresent = false;
        for (AudioNode stream : snapshot.streams())
            if (nodeName.equals(stream.name())) { streamPresent = true; break; }
        if (!streamPresent) return false;
        return graphState.instances().values().stream().anyMatch(instance -> {
            Map<String, Object> binding = instance.runtimeBinding();
            return instance.available()
                    && captureStreamKey.equals(binding.get("captureStreamKey"))
                    && nodeName.equals(binding.get("nodeName"))
                    && "captured".equals(binding.get("volumeCapability"))
                    && binding.get("applicationRuleId") instanceof String id && !id.isBlank();
        });
    }
    private static Map<String, Object> browserParent(BrowserAdapter adapter, String key) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("id", key);
        group.put("name", adapter.displayName());
        group.put("application_id", "");
        group.put("process", "");
        group.put("streams", new ArrayList<Map<String, Object>>());
        group.put("volume", 1.0);
        group.put("muted", false);
        group.put("available", false);
        group.put("volumeAvailable", false);
        group.put("tabAdapterAvailable", false);
        return group;
    }

    private static Map<String, Object> browserTab(BrowserAdapter.BrowserSnapshot browser,
                                                   BrowserAdapter.BrowserTab tab) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", browser.browser() + ":" + browser.instanceId() + ":" + browser.profileId()
                + ":" + tab.sessionToken());
        state.put("browser", browser.browser());
        state.put("instanceId", browser.instanceId());
        state.put("profileId", browser.profileId());
        state.put("sessionToken", tab.sessionToken());
        state.put("tabId", tab.tabId());
        state.put("title", tab.title());
        state.put("audible", tab.audible());
        state.put("muted", tab.muted());
        state.put("volumeCapability", tab.volumeCapability());
        if (tab.captureStreamKey() != null && !tab.captureStreamKey().isBlank())
            state.put("captureStreamKey", tab.captureStreamKey());
        return state;
    }

    private static double level(double volume) {
        return Double.isFinite(volume) ? Math.max(0, Math.min(1, volume)) : 1.0;
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
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
