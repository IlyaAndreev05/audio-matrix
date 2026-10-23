package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Projects only adapter-observed tabs; a live captured binding is required for PCM volume. */
final class GnomeBrowserTabState {
    private GnomeBrowserTabState() {}

    static Map<String, Object> describe(BrowserAdapter.BrowserSnapshot browser, BrowserAdapter.BrowserTab tab,
            Map<String, Object> config, DynamicInstanceRegistry.ReconcileResult graphState) {
        String captureKey = tab.captureStreamKey() == null ? "" : tab.captureStreamKey();
        DynamicInstanceRegistry.RuntimeInstance instance = graphState == null ? null
                : graphState.instances().values().stream().filter(item -> matches(item.runtimeBinding(), browser, tab))
                    .findFirst().orElse(null);
        DynamicGeneratorNode generator = instance == null ? null : graphState.effectiveGraph().nodes().stream()
                .filter(DynamicGeneratorNode.class::isInstance).map(DynamicGeneratorNode.class::cast)
                .filter(node -> node.id().equals(instance.generatorId())).findFirst().orElse(null);
        Map<String, Object> overrides = instance == null ? Map.of() : instance.overrides();
        if (Boolean.FALSE.equals(overrides.get("pinned"))) return null;
        boolean explicit = instance != null && QuickSettingsConfig.pins(config).stream().anyMatch(pin ->
                instance.generatorId().equals(pin.get("node_id")) && instance.instanceKey().equals(pin.get("instance_key"))
                        && !Boolean.FALSE.equals(pin.get("pinned")));
        if (generator != null && !generator.autoPin() && !explicit
                && !Boolean.TRUE.equals(overrides.get("pinned"))) return null;
        boolean individuallyPinned = explicit || Boolean.TRUE.equals(overrides.get("pinned"));
        boolean show = generator == null ? individuallyPinned || tab.audible() || tab.muted() || !captureKey.isBlank()
                : generator.acceptsBrowserTabActivity(tab.audible(), tab.muted(), !captureKey.isBlank(), individuallyPinned);
        if (!show) return null;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", browser.browser() + ":" + browser.instanceId() + ":" + browser.profileId() + ":" + tab.sessionToken());
        state.put("browser", browser.browser());
        state.put("instanceId", browser.instanceId());
        state.put("profileId", browser.profileId());
        state.put("sessionToken", tab.sessionToken());
        state.put("tabId", tab.tabId());
        state.put("title", overrides.getOrDefault("display_name", tab.title()));
        if (overrides.containsKey("icon")) state.put("icon", overrides.get("icon"));
        state.put("audible", tab.audible());
        state.put("muted", tab.muted());
        state.put("available", true);
        state.put("muteAvailable", true);
        state.put("volumeAvailable", false);
        state.put("volumeCapability", tab.volumeCapability());
        if (!captureKey.isBlank()) state.put("captureStreamKey", captureKey);
        if (instance != null && instance.gainControlAvailable() && "captured".equals(tab.volumeCapability())
                && !captureKey.isBlank()) {
            GainNode gain = graphState.effectiveGraph().nodes().stream().filter(GainNode.class::isInstance)
                    .map(GainNode.class::cast).filter(node -> node.id().equals(instance.nodeIds().get("gain")))
                    .findFirst().orElse(null);
            if (gain != null) {
                state.put("volume", gain.gain());
                state.put("muted", tab.muted() || gain.muted());
                state.put("volumeAvailable", true);
                state.put("commandTarget", Map.of("routeId", graphState.effectiveGraph().id(),
                        "nodeId", instance.generatorId(), "instanceKey", instance.instanceKey()));
            }
        }
        return state;
    }

    private static boolean matches(Map<String, Object> binding, BrowserAdapter.BrowserSnapshot browser,
                                   BrowserAdapter.BrowserTab tab) {
        return browser.browser().equals(binding.get("browser")) && browser.instanceId().equals(binding.get("instanceId"))
                && browser.profileId().equals(binding.get("profileId")) && tab.sessionToken().equals(binding.get("sessionToken"));
    }
}
