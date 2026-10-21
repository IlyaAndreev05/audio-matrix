package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Adds a missing ordinary-output generator without rewriting a schema-10 route. */
final class GraphSchema10OutputAugmenter {
    private static final String HAPTIC_SELECTOR = "device:role:haptic_output";
    private GraphSchema10OutputAugmenter() {}

    static Map<String, String> deviceFingerprints(Object rawDevices) {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (Map<String, Object> device : maps(rawDevices)) {
            if (!(device.get("id") instanceof String id) || id.isBlank()
                    || !(device.get("identity") instanceof Map<?, ?> rawIdentity)) continue;
            try {
                DeviceIdentity identity = DeviceIdentity.fromMap(object(rawIdentity));
                if (identity.reliable()) fingerprints.put(id, identity.fingerprint());
            } catch (RuntimeException ignored) {
                // An unreliable identity must not be used to suppress a generated physical branch.
            }
        }
        return Map.copyOf(fingerprints);
    }

    static Map<String, Object> addMissingOutputs(Map<String, Object> route,
                                                Map<String, String> deviceFingerprints) {
        List<Map<String, Object>> nodes = maps(route.get("nodes"));
        if (nodes.isEmpty()) return route;
        boolean hasApplications = false, hasNormalOutputs = false;
        Set<String> applicationMerges = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            if (!"dynamic_generator".equals(node.get("type"))) continue;
            Map<String, Object> parameters = object(node.get("parameters"));
            String registry = text(parameters.get("registry"));
            String selector = text(parameters.get("selector"));
            hasApplications |= "applications".equals(registry);
            hasNormalOutputs |= "outputs".equals(registry)
                    && ("device:*".equals(selector) || "device:all".equals(selector));
            if ("applications".equals(registry)) {
                String mergeId = text(object(parameters.get("connect_to")).get("node_id"));
                if (!mergeId.isBlank()) applicationMerges.add(mergeId);
            }
        }
        if (!hasApplications || hasNormalOutputs || applicationMerges.isEmpty()) return route;
        Map<String, Map<String, Object>> nodesById = new LinkedHashMap<>();
        nodes.forEach(node -> nodesById.put(text(node.get("id")), node));
        Set<String> switchIds = new LinkedHashSet<>();
        for (Map<String, Object> connection : maps(route.get("connections"))) {
            String from = text(connection.get("from_node")), to = text(connection.get("to_node"));
            Map<String, Object> target = nodesById.get(to);
            if (applicationMerges.contains(from) && target != null && isSwitch(target)
                    && "in".equals(connection.get("to_port"))) switchIds.add(to);
        }
        if (switchIds.size() != 1) return route;
        String switchId = switchIds.iterator().next();
        Map<String, Object> switchNode = nodesById.get(switchId);
        List<String> channels = strings(switchNode.get("channels"));
        if (channels.isEmpty()) return route;
        Set<String> excluded = new LinkedHashSet<>();
        excluded.add(HAPTIC_SELECTOR);
        for (Map<String, Object> node : nodes) {
            if (!"output".equals(node.get("type")) && !"device_slot".equals(node.get("type"))) continue;
            String deviceId = text(object(node.get("parameters")).get("device_id"));
            if (deviceId.isBlank() || "device:unbound".equals(deviceId) || "system_default".equals(deviceId)) continue;
            String fingerprint = exactFingerprint(deviceId, deviceFingerprints);
            // A static branch without a reliable identity stays canonical; the runtime resolves it.
            if (fingerprint == null) continue;
            excluded.add("device:" + fingerprint);
        }
        String id = generatorId(text(route.get("id")), nodesById.keySet());
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("registry", "outputs");
        parameters.put("template", "default-output");
        parameters.put("connect_to", Map.of("node_id", switchId, "port_id", "in"));
        parameters.put("auto_select_trusted_hdmi_on_first_discovery", Boolean.FALSE);
        parameters.put("excluded_selectors", List.copyOf(excluded));
        parameters.put("instances", Map.of());
        DynamicGeneratorNode generator = new DynamicGeneratorNode(id, "Выходы", "device:*", channels, parameters);
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("id", generator.id());
        encoded.put("name", generator.name());
        encoded.put("type", generator.type());
        encoded.put("channels", generator.channels());
        encoded.put("parameters", generator.parameters());
        nodes.add(encoded);
        route.put("nodes", nodes);
        return route;
    }

    private static String exactFingerprint(String deviceId, Map<String, String> deviceFingerprints) {
        String reference = deviceId.startsWith("device:") ? deviceId.substring("device:".length()) : deviceId;
        if (reference.matches("[a-f0-9]{64}")) return reference;
        String fingerprint = deviceFingerprints.get(reference);
        return fingerprint != null && fingerprint.matches("[a-f0-9]{64}") ? fingerprint : null;
    }

    private static String generatorId(String routeId, Set<String> existingIds) {
        String seed = "schema10-output-generator\u0000" + routeId;
        String base = "dynamic-outputs-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
        String candidate = base;
        for (int suffix = 2; existingIds.contains(candidate); suffix++) candidate = base + "-" + suffix;
        return candidate;
    }

    private static boolean isSwitch(Map<String, Object> node) {
        return "switch".equals(node.get("type")) || "multi_switch".equals(node.get("type"));
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) result.add(object(map));
        return result;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
