package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Resolves a generated live output to exactly one explicitly saved device, never another sink. */
public final class GeneratedOutputBinding {
    private GeneratedOutputBinding() {}

    public static Optional<String> resolve(Map<String, Object> config, Map<String, Object> binding,
                                           PipeWireBackend.Snapshot snapshot, List<AudioNode> sinks) {
        if (snapshot == null || !snapshot.connected() || !(binding.get("nodeId") instanceof Number id)
                || !(binding.get("identity") instanceof Map<?, ?> rawIdentity)) return Optional.empty();
        @SuppressWarnings("unchecked") Map<String, Object> identityMap = (Map<String, Object>) rawIdentity;
        DeviceIdentity identity = DeviceIdentity.fromMap(identityMap);
        if (!identity.reliable()) return Optional.empty();
        List<AudioNode> live = sinks.stream().filter(node -> node.id() == id.intValue()
                && "output".equals(node.kind()) && node.outputAvailable()
                && node.name().equals(binding.get("deviceId")) && identity.equals(node.identity())).toList();
        if (live.size() != 1) return Optional.empty();
        if (sinks.stream().filter(node -> identity.equals(node.identity())).count() != 1) return Optional.empty();
        AudioNode output = live.getFirst();
        Object rawDevices = config.get("devices");
        if (!(rawDevices instanceof List<?> devices)) return Optional.empty();
        String match = null;
        for (Object raw : devices) {
            if (!(raw instanceof Map<?, ?>)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> device = (Map<String, Object>) raw;
            DeviceDiscovery.Resolution resolution = DeviceDiscovery.resolve(sinks, device);
            if (resolution.state() != DeviceDiscovery.State.ONLINE || resolution.node() != output) continue;
            Object rawId = device.get("id");
            if (!(rawId instanceof String deviceId) || deviceId.isBlank() || "system_default".equals(deviceId)
                    || deviceId.startsWith("device:") || match != null) return Optional.empty();
            match = deviceId;
        }
        if (match == null) return Optional.empty();
        String selected = match;
        if (devices.stream().filter(raw -> raw instanceof Map<?, ?> device
                && selected.equals(device.get("id"))).count() != 1) return Optional.empty();
        return Optional.of(match);
    }
}
