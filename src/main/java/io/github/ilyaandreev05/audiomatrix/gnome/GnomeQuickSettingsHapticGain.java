package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GnomeQuickSettingsHapticGain {
    private GnomeQuickSettingsHapticGain() {}

    static boolean hasAssignedOutput(Map<DynamicInstanceRegistry.InstanceAddress,
            DynamicInstanceRegistry.RuntimeInstance> instances) {
        return instances.values().stream().anyMatch(instance ->
                "haptic_output".equals(instance.runtimeBinding().get("role"))
                        && instance.available() && instance.bindingAvailable());
    }

    static Map<String, Object> targetState(Map<String, Object> config) {
        try {
            return GraphCommonVibro.graph(config).nodes().stream()
                    .filter(GainNode.class::isInstance).map(GainNode.class::cast)
                    .filter(gain -> GraphCommonVibro.VIBRO_GAIN_ID.equals(gain.id()))
                    .findFirst().map(gain -> Map.<String, Object>of("routeId", GraphCommonVibro.COMMON_ROUTE_ID,
                            "nodeId", gain.id(), "volume", gain.gain(), "muted", gain.muted())).orElse(null);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    static Map<String, Object> savedPinState(Map<String, Object> pin, Map<String, Object> gain,
                                              boolean available) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", pin.get("id"));
        state.put("routeId", GraphCommonVibro.COMMON_ROUTE_ID);
        state.put("nodeId", GraphCommonVibro.VIBRO_GAIN_ID);
        state.put("name", pin.getOrDefault("display_name", "Общая сила вибродинамика"));
        state.put("nodeType", "gain");
        state.put("groupId", "outputs");
        state.put("order", pin.getOrDefault("order", 0));
        state.put("view", pin.getOrDefault("view", "DEFAULT"));
        state.put("gain", gain);
        state.put("available", available);
        state.put("showWhenOffline", Boolean.TRUE.equals(pin.get("show_when_offline")));
        state.put("pinned", true);
        return state;
    }
}
