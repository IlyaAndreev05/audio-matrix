package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Single snapshot-derived catalogue. Neither discovery nor binding selects an audio branch. */
public final class DeviceDiscovery {
    public enum State { NEW, ONLINE, OFFLINE, AMBIGUOUS, CONFLICT, IGNORED }
    public record Resolution(State state, AudioNode node, String message) {}
    public record Entry(AudioNode node, DeviceIdentity identity, State state, String slotId, String conflict) {}
    private DeviceDiscovery() {}

    public static Resolution resolve(List<AudioNode> nodes, Map<String, Object> device) {
        if (Boolean.TRUE.equals(device.get("ignored"))) return new Resolution(State.IGNORED, null, "Device ignored");
        if (!(device.get("identity") instanceof Map<?, ?>)) {
            List<AudioNode> legacy = DeviceSelectorMatcher.findAll(nodes, object(device.get("selector")));
            if (legacy.isEmpty()) return new Resolution(State.OFFLINE, null, "Output unavailable");
            if (legacy.size() > 1)
                return new Resolution(State.AMBIGUOUS, null, "Legacy selector matches several outputs; choose a stable identity");
            return new Resolution(State.ONLINE, legacy.getFirst(), "");
        }
        DeviceIdentity identity = DeviceIdentity.fromMap(object(device.get("identity")));
        if (!identity.reliable()) return new Resolution(State.AMBIGUOUS, null, "Low confidence identity; explicit hardware identification required");
        List<AudioNode> matches = nodes.stream().filter(node -> node.identity() != null
                && node.identity().reliable() && node.identity().equals(identity)).toList();
        if (matches.isEmpty()) return new Resolution(State.OFFLINE, null, "Device offline or profile/channels changed");
        if (matches.size() > 1) return new Resolution(State.AMBIGUOUS, null, "Physical identity collision");
        return new Resolution(State.ONLINE, matches.getFirst(), "");
    }

    public static List<Entry> catalog(List<AudioNode> nodes, List<Map<String, Object>> devices) {
        List<Entry> result = new ArrayList<>();
        for (Map<String, Object> device : devices) {
            Resolution resolution = resolve(nodes, device);
            AudioNode node = resolution.node();
            String conflict = node == null ? "" : conflict(nodes, devices, device, node);
            DeviceIdentity identity = device.get("identity") instanceof Map<?, ?>
                    ? DeviceIdentity.fromMap(object(device.get("identity"))) : node == null ? null : node.identity();
            result.add(new Entry(node, identity, conflict.isEmpty() ? resolution.state() : State.CONFLICT,
                    String.valueOf(device.getOrDefault("id", "")), conflict.isEmpty() ? resolution.message() : conflict));
        }
        for (AudioNode node : nodes) {
            boolean known = devices.stream().anyMatch(device -> resolve(nodes, device).node() == node
                    || device.get("identity") instanceof Map<?, ?> && node.identity() != null
                    && node.identity().equals(DeviceIdentity.fromMap(object(device.get("identity")))));
            if (!known) {
                boolean ambiguous = node.identity() == null || !node.identity().reliable()
                        || nodes.stream().filter(candidate -> java.util.Objects.equals(candidate.identity(), node.identity())).count() > 1;
                result.add(new Entry(node, node.identity(), ambiguous ? State.AMBIGUOUS : State.NEW, "",
                        ambiguous ? "Low confidence identity or physical identity collision" : ""));
            }
        }
        return List.copyOf(result);
    }

    public static String conflict(List<AudioNode> nodes, List<Map<String, Object>> devices,
                                  Map<String, Object> device, AudioNode node) {
        for (Map<String, Object> other : devices) {
            if (other == device || java.util.Objects.equals(other.get("id"), device.get("id"))) continue;
            if (resolve(nodes, other).node() == node)
                return "Physical output is already assigned to " + other.get("id")
                        + ("haptic_output".equals(other.get("role")) ? " (Vibro)" : "");
        }
        return "";
    }

    public static void bind(Map<String, Object> config, String slotId, AudioNode node) {
        if (slotId == null || slotId.isBlank() || node == null || !"output".equals(node.kind()) || node.name().startsWith("audiomatrix_"))
            throw new IllegalArgumentException("Binding needs a slot id and physical output");
        if (node.identity() == null || !node.identity().reliable())
            throw new IllegalArgumentException("Device identity confidence is too low for safe reconnect");
        List<Map<String, Object>> devices = devices(config);
        Map<String, Object> existing = devices.stream().filter(device -> slotId.equals(device.get("id"))).findFirst().orElse(null);
        for (Map<String, Object> other : devices) {
            if (other == existing) continue;
            boolean same = other.get("identity") instanceof Map<?, ?>
                    && node.identity().equals(DeviceIdentity.fromMap(object(other.get("identity"))));
            if (!Boolean.TRUE.equals(other.get("ignored")) && (same || DeviceSelectorMatcher.matches(node, object(other.get("selector")))))
                throw new IllegalArgumentException("Physical output already bound to " + other.get("id") + "; change the existing route explicitly");
        }
        Map<String, Object> binding = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
        binding.put("id", slotId); binding.put("kind", "device_slot"); binding.put("identity", node.identity().toMap());
        Object alias = binding.get("name");
        if (!(alias instanceof String name) || name.isBlank()) binding.put("name", node.description());
        binding.put("role", binding.getOrDefault("role", "output"));
        binding.put("volume", binding.getOrDefault("volume", 1.0)); binding.put("mute", binding.getOrDefault("mute", false));
        binding.put("selected", existing != null && Boolean.TRUE.equals(existing.get("selected")));
        binding.put("ignored", false); binding.remove("selector");
        if (existing != null) devices.remove(existing);
        devices.add(binding); config.put("devices", devices);
    }

    public static void ignore(Map<String, Object> config, AudioNode node) {
        if (node == null || node.identity() == null || !node.identity().reliable())
            throw new IllegalArgumentException("A reliable device identity is required");
        List<Map<String, Object>> devices = devices(config);
        if (devices.stream().anyMatch(device -> node.identity().equals(DeviceIdentity.fromMap(object(device.get("identity"))))))
            throw new IllegalArgumentException("Already bound device; remove its explicit assignment first");
        Map<String, Object> ignored = new LinkedHashMap<>();
        ignored.put("id", "ignored-" + node.identity().fingerprint()); ignored.put("identity", node.identity().toMap());
        ignored.put("ignored", true); ignored.put("selected", false); ignored.put("volume", 1.0); ignored.put("mute", true);
        devices.add(ignored); config.put("devices", devices);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> devices(Map<String, Object> config) {
        return new ArrayList<>((List<Map<String, Object>>) config.getOrDefault("devices", List.of()));
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }
}
