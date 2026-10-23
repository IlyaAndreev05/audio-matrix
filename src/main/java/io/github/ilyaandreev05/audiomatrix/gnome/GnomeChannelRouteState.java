package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceSelectorMatcher;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Projects configured active channels only onto the selected real physical output. */
final class GnomeChannelRouteState {
    private GnomeChannelRouteState() {}

    static List<Map<String, Object>> channelRoutes(PipeWireBackend.Snapshot snapshot,
            Map<String, Object> config, Map<String, Object> profile, AudioNode selected,
            int defaultId, List<Map<String, Object>> devices) {
        if (selected == null) return List.of();
        List<String> activeIds = strings(profile.get("active_routes"));
        List<Map<String, Object>> buses = maps(config.get("buses"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> route : maps(config.get("routes"))) {
            String id = String.valueOf(route.getOrDefault("id", ""));
            if (id.isBlank() || !activeIds.contains(id) || Boolean.FALSE.equals(route.get("enabled"))) continue;
            String target = String.valueOf(route.getOrDefault("to", ""));
            if (buses.stream().anyMatch(bus -> target.equals(bus.get("id")))) continue;
            AudioNode output = "system_default".equals(target)
                    ? snapshot.sinks().stream().filter(node -> node.id() == defaultId).findFirst().orElse(null)
                    : DeviceSelectorMatcher.resolve(snapshot.sinks(), target, devices);
            if (output == null || output.id() != selected.id()) continue;
            result.add(Map.of("id", id, "name", route.getOrDefault("name", id),
                    "channel_map", route.getOrDefault("channel_map", "stereo")));
        }
        return List.copyOf(result);
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> list
                ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
