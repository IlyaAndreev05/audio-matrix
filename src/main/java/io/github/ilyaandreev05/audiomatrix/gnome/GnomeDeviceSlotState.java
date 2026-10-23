package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceDiscovery;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.config.DeviceDisplayNames;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Device slots expose binding status, never a fabricated routing or power switch. */
final class GnomeDeviceSlotState {
    private GnomeDeviceSlotState() {}

    static Map<String, Object> describe(DeviceSlotNode slot, PipeWireBackend.Snapshot snapshot,
                                         Map<String, Object> config) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", slot.deviceId());
        state.put("name", displayName(config, slot.deviceId(), slot.name()));
        state.put("available", false);
        if (slot.deviceId().isBlank()) {
            state.put("state", "UNBOUND");
            return state;
        }
        if ("system_default".equals(slot.deviceId())) {
            AudioNode node = snapshot.sinks().stream().filter(item -> item.id() == snapshot.defaultSinkId()).findFirst().orElse(null);
            state.put("state", node == null ? "OFFLINE" : "ONLINE");
            state.put("available", node != null);
            if (node != null) {
                String name = displayName(Map.of(), slot.deviceId(), node.description());
                state.put("name", name);
                state.put("deviceName", name);
                state.put("description", description(node.description()));
                state.put("commandId", "name:" + node.name());
                state.put("savedVolume", slot.volume());
                state.put("savedMuted", slot.muted());
                state.put("appliedVolume", Double.isFinite(node.volume()) ? Math.max(0, Math.min(1, node.volume())) : null);
                state.put("appliedMuted", node.muted());
            }
            return state;
        }
        List<Map<String, Object>> devices = devices(config.get("devices"));
        String id = slot.deviceId().startsWith("device:") ? slot.deviceId().substring(7) : slot.deviceId();
        List<Map<String, Object>> bindings = devices.stream().filter(device -> id.equals(device.get("id"))).toList();
        if (bindings.isEmpty()) {
            state.put("state", "OFFLINE");
            state.put("message", "Device binding is missing");
            return state;
        }
        if (bindings.size() != 1) {
            state.put("state", "CONFLICT");
            state.put("message", "Device binding is duplicated");
            return state;
        }
        Map<String, Object> binding = bindings.getFirst();
        String name = displayName(binding, slot.name());
        state.put("name", name);
        state.put("deviceName", name);
        DeviceDiscovery.Resolution resolution = DeviceDiscovery.resolve(snapshot.sinks(), binding);
        AudioNode node = resolution.node();
        String conflict = node == null ? "" : DeviceDiscovery.conflict(snapshot.sinks(), devices, binding, node);
        state.put("state", conflict.isBlank() ? resolution.state().name() : "CONFLICT");
        state.put("available", node != null && conflict.isBlank());
        state.put("message", conflict.isBlank() ? resolution.message() : conflict);
        if (node != null && conflict.isBlank()) {
            String activeName = displayName(binding, node.description());
            state.put("name", activeName);
            state.put("deviceName", activeName);
            state.put("description", description(node.description()));
            state.put("commandId", "device:" + id);
            state.put("volume", volumeForDisplay(slot, node));
            state.put("muted", node.muted());
            state.put("savedVolume", slot.volume());
            state.put("savedMuted", slot.muted());
            state.put("appliedVolume", Double.isFinite(node.volume()) ? Math.max(0, Math.min(1, node.volume())) : null);
            state.put("appliedMuted", node.muted());
        }
        return state;
    }
    private static String displayName(Map<String, Object> config, String deviceId, String detectedName) {
        String label = DeviceDisplayNames.displayName(config, deviceId, safeDetectedName(detectedName));
        if ("Выходное устройство".equals(label) && isSystemInput(detectedName)) return "Системный вход";
        if ("Выходное устройство".equals(label) && inputRole(config, deviceId)) return "Устройство ввода";
        return label;
    }

    private static String displayName(Map<String, Object> binding, String detectedName) {
        String label = DeviceDisplayNames.displayName(binding, safeDetectedName(detectedName));
        if ("Выходное устройство".equals(label)
                && (inputRole(binding.get("role")) || isSystemInput(detectedName))) return "Системный вход";
        return label;
    }

    private static String description(String detectedName) {
        if (isSystemInput(detectedName)) return "Системный вход";
        return DeviceDisplayNames.displayName(Map.of(), safeDetectedName(detectedName));
    }

    private static String safeDetectedName(String detectedName) {
        return isSystemInput(detectedName) ? "" : detectedName == null ? "" : detectedName;
    }

    private static boolean isSystemInput(String value) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).startsWith("system input/");
    }

    private static boolean inputRole(Map<String, Object> config, String deviceId) {
        String id = deviceId != null && deviceId.startsWith("device:")
                ? deviceId.substring("device:".length()) : deviceId;
        return devices(config.get("devices")).stream()
                .filter(device -> id != null && id.equals(device.get("id")))
                .anyMatch(device -> inputRole(device.get("role")));
    }

    private static boolean inputRole(Object role) {
        return role instanceof String value
                && java.util.Set.of("input", "microphone", "accelerometer", "source").contains(value);
    }


    private static double volumeForDisplay(DeviceSlotNode slot, AudioNode node) {
        if (node.muted() && slot.volume() > 0) return slot.volume();
        return Double.isFinite(node.volume()) ? Math.max(0, Math.min(1, node.volume())) : slot.volume();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> devices(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
