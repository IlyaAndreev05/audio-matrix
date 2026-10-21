package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioMatrixOwnedStream;
import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.EventSoundStudioSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Projects the live streams into the application list published to the GNOME panel. */
final class GnomeApplicationProjection {
    private GnomeApplicationProjection() {}

    static List<Map<String, Object>> applications(PipeWireBackend.Snapshot snapshot, Map<String, Object> config,
                                                   List<BrowserAdapter> browserAdapters,
                                                   DynamicInstanceRegistry.ReconcileResult graphState) {
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (AudioNode stream : snapshot.streams()) {
            if (AudioMatrixOwnedStream.matches(stream) || eventStream(stream)) continue;
            String appId = stream.applicationId() == null ? "" : stream.applicationId();
            String process = stream.processName() == null ? "" : stream.processName();
            String key = !appId.isBlank() ? "app:" + appId
                    : !process.isBlank() ? "process:" + process : "stream:" + stream.name();
            Map<String, Object> group = groups.computeIfAbsent(key, ignored -> group(key, stream, appId, process));
            @SuppressWarnings("unchecked") List<Map<String, Object>> children =
                    (List<Map<String, Object>>) group.get("streams");
            children.add(stream(stream));
        }
        applyLevels(groups, config, graphState);
        appendBrowserTabs(groups, snapshot, browserAdapters, graphState);
        return List.copyOf(groups.values());
    }

    /** The event branch owns its own application: the ordinary list never shows the event stream. */
    private static boolean eventStream(AudioNode stream) {
        return EventSoundStudioSource.APPLICATION_ID.equals(stream.applicationId())
                || EventSoundStudioSource.APPLICATION_ID.equals(stream.processName());
    }

    /** Every application carries the level its owner keeps: a saved rule first, the generated instance otherwise. */
    private static void applyLevels(Map<String, Map<String, Object>> groups, Map<String, Object> config,
                                    DynamicInstanceRegistry.ReconcileResult graphState) {
        AudioGraph effectiveGraph = graphState == null ? null : graphState.effectiveGraph();
        Map<DynamicInstanceRegistry.InstanceAddress, DynamicInstanceRegistry.RuntimeInstance> instances =
                graphState == null ? Map.of() : graphState.instances();
        for (Map<String, Object> group : groups.values()) {
            GnomeApplicationLevels.Level resolved = GnomeApplicationLevels.resolve(config, effectiveGraph, instances,
                    String.valueOf(group.get("application_id")), String.valueOf(group.get("process")));
            if (resolved == null) continue;
            group.put("volume", resolved.volume());
            group.put("muted", resolved.muted());
        }
    }

    private static Map<String, Object> group(String key, AudioNode stream, String appId, String process) {
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
    }

    private static Map<String, Object> stream(AudioNode stream) {
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("id", stream.id());
        child.put("name", stream.name());
        child.put("description", stream.description());
        child.put("volume", level(stream.volume()));
        child.put("muted", stream.muted());
        return child;
    }

    private static void appendBrowserTabs(Map<String, Map<String, Object>> groups, PipeWireBackend.Snapshot snapshot,
                                           List<BrowserAdapter> browserAdapters,
                                           DynamicInstanceRegistry.ReconcileResult graphState) {
        for (BrowserAdapter adapter : browserAdapters == null ? List.<BrowserAdapter>of() : browserAdapters) {
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
                    (List<Map<String, Object>>) group.computeIfAbsent("browserTabs",
                            ignored -> new ArrayList<Map<String, Object>>());
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
}
