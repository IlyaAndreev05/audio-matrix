package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceSelectorMatcher;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.DeviceDisplayNames;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creates stable GNOME mixer output references from persistent device selectors. */
public final class GnomeOutputState {
    private GnomeOutputState() {}

    static List<Map<String, Object>> devices(PipeWireBackend.Snapshot snapshot, List<Map<String, Object>> configured) {
        List<Map<String, Object>> outputs = new ArrayList<>();
        int defaultId = snapshot.defaultSinkId();
        for (AudioNode sink : snapshot.sinks()) {
            if (sink.name().startsWith("audiomatrix_")) continue;
            outputs.add(device(sink, configured, defaultId));
        }
        return List.copyOf(outputs);
    }

    static Map<String, Object> device(AudioNode sink, List<Map<String, Object>> configured, int defaultId) {
        Map<String, Object> output = new LinkedHashMap<>();
        String detectedName = detectedName(sink);
        output.put("id", commandId(sink, configured));
        output.put("nodeName", sink.name());
        output.put("name", displayName(sink, configured, detectedName));
        output.put("description", DeviceDisplayNames.displayName(Map.of(), detectedName));
        output.put("channels", sink.channels());
        output.put("default", sink.id() == defaultId);
        output.put("available", true);
        output.put("muted", sink.muted());
        output.put("volume", Double.isFinite(sink.volume()) ? Math.max(0, Math.min(1, sink.volume())) : 0);
        return output;
    }

    public static AudioNode resolve(List<AudioNode> available, String reference, List<Map<String, Object>> configured) {
        if (reference == null || reference.isBlank()) return null;
        if (reference.startsWith("device:"))
            return DeviceSelectorMatcher.resolve(available, reference.substring("device:".length()), configured);
        if (reference.startsWith("name:")) {
            String name = reference.substring("name:".length());
            return available.stream().filter(node -> node.name().equals(name)).findFirst().orElse(null);
        }
        return DeviceSelectorMatcher.resolve(available, reference, configured);
    }

    private static String detectedName(AudioNode sink) {
        return sink.description() == null || sink.description().isBlank() ? sink.name() : sink.description();
    }

    private static String displayName(AudioNode sink, List<Map<String, Object>> configured, String detectedName) {
        List<Map<String, Object>> matches = configured.stream()
                .filter(device -> matchesConfiguredDevice(sink, device))
                .toList();
        return matches.size() == 1
                ? DeviceDisplayNames.displayName(matches.getFirst(), detectedName)
                : DeviceDisplayNames.displayName(Map.of(), detectedName);
    }

    private static boolean matchesConfiguredDevice(AudioNode sink, Map<String, Object> device) {
        if (DeviceSelectorMatcher.matches(sink, object(device.get("selector")))) return true;
        if (sink.identity() == null || !(device.get("identity") instanceof Map<?, ?> identity)) return false;
        return sink.identity().fingerprint().equals(identity.get("fingerprint"));
    }

    private static String commandId(AudioNode sink, List<Map<String, Object>> configured) {
        List<String> matches = configured.stream()
                .filter(device -> DeviceSelectorMatcher.matches(sink, object(device.get("selector"))))
                .map(device -> String.valueOf(device.getOrDefault("id", "")))
                .filter(id -> !id.isBlank()).distinct().toList();
        return matches.size() == 1 ? "device:" + matches.getFirst() : "name:" + sink.name();
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
