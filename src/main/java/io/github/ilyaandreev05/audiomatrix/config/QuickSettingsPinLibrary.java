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
        if (routeId == null || routeId.isBlank() || nodeId == null || nodeId.isBlank())
            throw new IllegalArgumentException("Pinned graph node needs a route and node id");
        if (QuickSettingsConfig.groups(config).stream().noneMatch(group -> groupId.equals(group.get("id"))))
            throw new IllegalArgumentException("Pinned node group does not exist");
        if (!List.of("DEFAULT", "COMPACT", "EXPANDED").contains(view))
            throw new IllegalArgumentException("Pinned node view is unsupported");
        Map<String, Object> existing = pins(config).stream()
                .filter(pin -> routeId.equals(pin.get("route_id")) && nodeId.equals(pin.get("node_id")))
                .findFirst().orElse(null);
        if (existing != null) {
            existing.put("group_id", groupId);
            existing.put("order", order);
            existing.put("view", view);
            return existing;
        }
        Map<String, Object> pin = DefaultConfig.map("id", id(), "route_id", routeId, "node_id", nodeId,
                "group_id", groupId, "order", order, "view", view, "control_ids", new ArrayList<>());
        pins(config).add(pin);
        return pin;
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
        if (!pins(config).isEmpty()) return;
        Map<String, Map<String, Object>> byNode = migrateQuickControls(config);
        pins(config).addAll(byNode.values());
        migrateSourceAndOutputNodes(config);
    }

    private static Map<String, Map<String, Object>> migrateQuickControls(Map<String, Object> config) {
        Map<String, Map<String, Object>> byNode = new LinkedHashMap<>();
        String active = String.valueOf(config.getOrDefault("active_route", ""));
        for (Map<String, Object> control : maps(config.get("quick_controls"))) {
            String routeId = String.valueOf(control.getOrDefault("route_id", active));
            String nodeId = String.valueOf(control.getOrDefault("node_id", ""));
            String key = routeId + "\u0000" + nodeId;
            Map<String, Object> pin = byNode.get(key);
            if (pin == null) {
                String groupId = resolveGroup(config, control);
                pin = DefaultConfig.map("id", id(), "route_id", routeId, "node_id", nodeId,
                        "group_id", groupId, "order", control.getOrDefault("order", 0), "view", "EXPANDED",
                        "control_ids", new ArrayList<String>());
                byNode.put(key, pin);
            }
            @SuppressWarnings("unchecked") List<String> ids = (List<String>) pin.get("control_ids");
            ids.add(String.valueOf(control.get("id")));
        }
        return byNode;
    }

    private static void migrateSourceAndOutputNodes(Map<String, Object> config) {
        for (Map<String, Object> route : maps(config.get("route_library"))) {
            String routeId = String.valueOf(route.getOrDefault("id", ""));
            AudioGraph graph;
            try { graph = GraphCodec.fromMap(route); }
            catch (RuntimeException invalid) { continue; }
            for (GraphNode node : graph.nodes()) {
                if (!(node instanceof SourceNode) && !(node instanceof OutputNode)) continue;
                if (find(config, routeId, node.id()) != null) continue;
                String name = node instanceof SourceNode ? "Источники" : "Выходные устройства";
                Map<String, Object> group = QuickSettingsConfig.groups(config).stream()
                        .filter(item -> name.equalsIgnoreCase(String.valueOf(item.get("name"))))
                        .findFirst().orElseGet(() -> QuickSettingsConfig.findOrCreateGroup(config, name));
                int order = pins(config).stream().filter(pin -> routeId.equals(pin.get("route_id"))
                                && group.get("id").equals(pin.get("group_id")))
                        .mapToInt(pin -> pin.get("order") instanceof Number number ? number.intValue() : -1).max().orElse(-1) + 1;
                pinNode(config, routeId, node.id(), String.valueOf(group.get("id")), order, "EXPANDED");
            }
        }
    }

    private static String resolveGroup(Map<String, Object> config, Map<String, Object> control) {
        String groupId = String.valueOf(control.getOrDefault("group_id", ""));
        if (QuickSettingsConfig.groups(config).stream().anyMatch(group -> groupId.equals(group.get("id")))) return groupId;
        return String.valueOf(QuickSettingsConfig.findOrCreateGroup(config,
                String.valueOf(control.getOrDefault("section", "Коммутация и управление"))).get("id"));
    }

    private static Map<String, Object> find(Map<String, Object> config, String routeId, String nodeId) {
        return pins(config).stream().filter(pin -> routeId.equals(pin.get("route_id")) && nodeId.equals(pin.get("node_id")))
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
