package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class QuickSettingsPinLibrary {
    private QuickSettingsPinLibrary() {}

    static List<Map<String, Object>> pins(Map<String, Object> config) {
        Map<String, Object> settings = object(config.get("quick_settings"));
        config.put("quick_settings", settings);
        Object value = settings.get("pins");
        if (!(value instanceof List<?> list)) {
            List<Map<String, Object>> created = new ArrayList<>();
            settings.put("pins", created);
            return created;
        }
        @SuppressWarnings("unchecked") List<Map<String, Object>> result = (List<Map<String, Object>>) list;
        return result;
    }

    static Map<String, Object> pinNode(Map<String, Object> config, String routeId, String nodeId,
                                       String groupId, int order, String view) {
        return pinNode(config, routeId, nodeId, "", groupId, order, view);
    }

    static Map<String, Object> pinNode(Map<String, Object> config, String routeId, String nodeId,
                                       String instanceKey, String groupId, int order, String view) {
        return pinNode(config, routeId, nodeId, instanceKey, "", groupId, order, view);
    }

    static Map<String, Object> pinNode(Map<String, Object> config, String routeId, String nodeId,
                                       String instanceKey, String childId, String groupId, int order, String view) {
        String key = instanceKey == null ? "" : instanceKey;
        if (routeId == null || routeId.isBlank() || nodeId == null || nodeId.isBlank())
            throw new IllegalArgumentException("Pinned graph node needs a route and node id");
        if (QuickSettingsConfig.groups(config).stream().noneMatch(group -> groupId.equals(group.get("id"))))
            throw new IllegalArgumentException("Pinned node group does not exist");
        if (!List.of("DEFAULT", "COMPACT", "EXPANDED").contains(view))
            throw new IllegalArgumentException("Pinned node view is unsupported");
        if (order < 0) throw new IllegalArgumentException("Pinned node order must not be negative");
        if (childId != null && !childId.isBlank() && key.isBlank())
            throw new IllegalArgumentException("A generated child pin needs an instance key");
        Map<String, Object> existing = find(config, routeId, nodeId, key, childId);
        if (existing != null) {
            existing.put("group_id", groupId);
            existing.put("order", order);
            existing.put("view", view);
            existing.remove("pinned");
            return existing;
        }
        Map<String, Object> pin = DefaultConfig.map("id", id(), "route_id", routeId, "node_id", nodeId,
                "group_id", groupId, "order", order, "view", view, "control_ids", new ArrayList<>());
        if (!key.isBlank()) pin.put("instance_key", key);
        if (childId != null && !childId.isBlank()) pin.put("child_id", childId);
        pins(config).add(pin);
        return pin;
    }

    static void updatePin(Map<String, Object> config, String routeId, String nodeId, String instanceKey,
                          String groupId, int order, String view, String displayName, String icon,
                          Boolean showChildren, Boolean showWhenOffline) {
        Map<String, Object> pin = pinNode(config, routeId, nodeId, instanceKey, groupId, order, view);
        setOptional(pin, "display_name", displayName == null || displayName.isBlank() ? null : displayName.trim());
        setOptional(pin, "icon", icon == null || icon.isBlank() ? null : icon.trim());
        setOptional(pin, "show_children", showChildren);
        setOptional(pin, "show_when_offline", showWhenOffline);
    }

    static void removePin(Map<String, Object> config, String routeId, String nodeId, String instanceKey) {
        Map<String, Object> pin = find(config, routeId, nodeId, instanceKey, "");
        if (pin != null) pins(config).remove(pin);
    }

    private static void setOptional(Map<String, Object> pin, String key, Object value) {
        if (value == null) pin.remove(key);
        else pin.put(key, value);
    }

    static void attachControl(Map<String, Object> config, String routeId, String nodeId, String controlId) {
        Map<String, Object> pin = find(config, routeId, nodeId);
        if (pin == null) throw new IllegalArgumentException("Quick control has no pinned node");
        List<String> ids = strings(pin.get("control_ids"));
        if (!ids.contains(controlId)) ids.add(controlId);
        pin.put("control_ids", ids);
    }

    static void removeControl(Map<String, Object> config, String routeId, String nodeId, String controlId,
                              boolean keepEmptyPin) {
        maps(config.get("quick_controls")).removeIf(control -> controlId.equals(control.get("id")));
        Map<String, Object> pin = find(config, routeId, nodeId);
        if (pin == null) return;
        List<String> ids = strings(pin.get("control_ids"));
        ids.remove(controlId);
        pin.put("control_ids", ids);
        if (!keepEmptyPin && ids.isEmpty()) pins(config).remove(pin);
    }

    static void removeNode(Map<String, Object> config, String routeId, String nodeId) {
        maps(config.get("quick_controls")).removeIf(control -> routeId.equals(control.get("route_id"))
                && nodeId.equals(control.get("node_id")));
        pins(config).removeIf(pin -> routeId.equals(pin.get("route_id")) && nodeId.equals(pin.get("node_id")));
    }

    static void removeRoute(Map<String, Object> config, String routeId) {
        maps(config.get("quick_controls")).removeIf(control -> routeId.equals(control.get("route_id")));
        pins(config).removeIf(pin -> routeId.equals(pin.get("route_id")));
    }

    static void duplicateRoute(Map<String, Object> config, String sourceRoute, String targetRoute,
                               Map<String, String> nodeIds, Map<String, String> controlIds) {
        List<Map<String, Object>> copies = new ArrayList<>();
        for (Map<String, Object> original : List.copyOf(pins(config))) {
            if (!sourceRoute.equals(original.get("route_id"))) continue;
            String nodeId = nodeIds.get(String.valueOf(original.get("node_id")));
            if (nodeId == null) continue;
            Map<String, Object> copy = copyPin(original);
            copy.put("id", id());
            copy.put("route_id", targetRoute);
            copy.put("node_id", nodeId);
            copy.put("control_ids", strings(original.get("control_ids")).stream()
                    .map(controlIds::get).filter(java.util.Objects::nonNull).toList());
            copies.add(copy);
        }
        pins(config).addAll(copies);
    }

    static List<Map<String, Object>> copyPinsForRoute(Map<String, Object> config, String routeId) {
        return pins(config).stream().filter(pin -> routeId.equals(pin.get("route_id")))
                .map(QuickSettingsPinLibrary::copyPin).toList();
    }

    static Map<String, String> restorePinsFromCopy(Map<String, Object> config, Object savedPins,
                                                    String targetRoute, Map<String, String> controlIds) {
        Map<String, String> pinIds = new LinkedHashMap<>();
        if (!(savedPins instanceof List<?> list)) return pinIds;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> pin = object(raw);
            String oldId = String.valueOf(pin.getOrDefault("id", ""));
            String newId = id();
            pinIds.put(oldId, newId);
            pin.put("id", newId);
            pin.put("route_id", targetRoute);
            pin.put("control_ids", strings(pin.get("control_ids")).stream()
                    .map(controlIds::get).filter(java.util.Objects::nonNull).toList());
            pins(config).add(pin);
        }
        return pinIds;
    }

    static void migrateNodePins(Map<String, Object> config) {
        QuickSettingsPinMigration.migrate(config);
    }

    private static Map<String, Object> find(Map<String, Object> config, String routeId, String nodeId) {
        return find(config, routeId, nodeId, "");
    }

    private static Map<String, Object> find(Map<String, Object> config, String routeId, String nodeId,
                                            String instanceKey) {
        return find(config, routeId, nodeId, instanceKey, "");
    }

    static Map<String, Object> find(Map<String, Object> config, String routeId, String nodeId,
                                    String instanceKey, String childId) {
        String key = instanceKey == null ? "" : instanceKey;
        String child = childId == null ? "" : childId;
        return pins(config).stream()
                .filter(pin -> routeId.equals(pin.get("route_id")) && nodeId.equals(pin.get("node_id"))
                        && key.equals(String.valueOf(pin.getOrDefault("instance_key", "")))
                        && child.equals(String.valueOf(pin.getOrDefault("child_id", ""))))
                .findFirst().orElse(null);
    }

    private static Map<String, Object> copyPin(Map<String, Object> pin) {
        Map<String, Object> copy = new LinkedHashMap<>(pin);
        copy.put("control_ids", strings(pin.get("control_ids")));
        return copy;
    }

    private static String id() { return "pin-" + UUID.randomUUID(); }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        return new ArrayList<>(list.stream().map(String::valueOf).toList());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) return new LinkedHashMap<>();
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }
}
