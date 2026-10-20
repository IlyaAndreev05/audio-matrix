package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceDiscovery;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;

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
        state.put("name", slot.name());
        state.put("available", false);
        if (slot.deviceId().isBlank()) {
            state.put("state", "UNBOUND");
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
        if (binding.get("name") != null) state.put("deviceName", binding.get("name"));
        DeviceDiscovery.Resolution resolution = DeviceDiscovery.resolve(snapshot.sinks(), binding);
        AudioNode node = resolution.node();
        String conflict = node == null ? "" : DeviceDiscovery.conflict(snapshot.sinks(), devices, binding, node);
        state.put("state", conflict.isBlank() ? resolution.state().name() : "CONFLICT");
        state.put("available", node != null && conflict.isBlank());
        state.put("message", conflict.isBlank() ? resolution.message() : conflict);
        if (node != null && conflict.isBlank()) {
            state.put("deviceName", node.name());
            state.put("description", node.description());
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> devices(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
