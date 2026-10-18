package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.List;
import java.util.Map;

/** Resolves persistent selector values against the current PipeWire snapshot, never a transient node ID. */
public final class DeviceSelectorMatcher {
    private DeviceSelectorMatcher() {}

    public static boolean matches(AudioNode node, Map<String, Object> selector) {
        if (node == null || selector == null) return false;
        String value = selector.get("value") instanceof String text ? text : "";
        if (value.isBlank()) return false;
        return switch (String.valueOf(selector.get("kind"))) {
            case "name_equals" -> node.name().equals(value);
            case "name_contains" -> node.name().toLowerCase(java.util.Locale.ROOT).contains(value.toLowerCase(java.util.Locale.ROOT));
            case "description_contains" -> node.description().toLowerCase(java.util.Locale.ROOT).contains(value.toLowerCase(java.util.Locale.ROOT));
            default -> false;
        };
    }

    public static AudioNode find(List<AudioNode> nodes, Map<String, Object> selector) {
        List<AudioNode> matches = findAll(nodes, selector);
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    public static List<AudioNode> findAll(List<AudioNode> nodes, Map<String, Object> selector) {
        return nodes.stream().filter(node -> matches(node, selector)).toList();
    }

    /** Supports legacy profile values containing a PipeWire name or selector value as well as stable device IDs. */
    public static AudioNode resolve(List<AudioNode> nodes, String reference, List<Map<String, Object>> devices) {
        if (reference == null || reference.isBlank() || "system_default".equals(reference)) return null;
        List<Map<String, Object>> configured = devices.stream().filter(device -> reference.equals(device.get("id"))).toList();
        if (!configured.isEmpty()) {
            if (configured.size() != 1) return null;
            Map<String, Object> device = configured.getFirst();
            AudioNode node = DeviceDiscovery.resolve(nodes, device).node();
            return node != null && DeviceDiscovery.conflict(nodes, devices, device, node).isEmpty() ? node : null;
        }
        // Legacy raw names remain explicit references; never use a default sink or first fuzzy match.
        List<AudioNode> direct = nodes.stream().filter(node -> node.name().equals(reference)).toList();
        if (!direct.isEmpty()) return direct.size() == 1 ? direct.getFirst() : null;
        java.util.LinkedHashSet<AudioNode> resolved = new java.util.LinkedHashSet<>();
        for (Map<String, Object> device : devices) {
            Map<String, Object> selector = object(device.get("selector"));
            if (reference.equals(selector.get("value"))) {
                AudioNode node = DeviceDiscovery.resolve(nodes, device).node();
                if (node != null && DeviceDiscovery.conflict(nodes, devices, device, node).isEmpty()) resolved.add(node);
            }
        }
        return resolved.size() == 1 ? resolved.iterator().next() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
