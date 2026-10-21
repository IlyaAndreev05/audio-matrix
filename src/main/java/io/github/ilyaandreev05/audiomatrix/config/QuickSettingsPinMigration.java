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

final class QuickSettingsPinMigration {
    private QuickSettingsPinMigration() {}

    static void migrate(Map<String, Object> config) {
        if (!QuickSettingsConfig.pins(config).isEmpty()) return;
        Map<String, Map<String, Object>> byNode = migrateQuickControls(config);
        QuickSettingsConfig.pins(config).addAll(byNode.values());
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
                pin = DefaultConfig.map("id", "pin-" + java.util.UUID.randomUUID(),
                        "route_id", routeId, "node_id", nodeId, "group_id", groupId,
                        "order", control.getOrDefault("order", 0), "view", "EXPANDED",
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
                int order = QuickSettingsConfig.pins(config).stream()
                        .filter(pin -> routeId.equals(pin.get("route_id")) && group.get("id").equals(pin.get("group_id")))
                        .mapToInt(pin -> pin.get("order") instanceof Number number ? number.intValue() : -1)
                        .max().orElse(-1) + 1;
                QuickSettingsConfig.pinNode(config, routeId, node.id(), String.valueOf(group.get("id")), order, "EXPANDED");
            }
        }
    }

    private static String resolveGroup(Map<String, Object> config, Map<String, Object> control) {
        String groupId = String.valueOf(control.getOrDefault("group_id", ""));
        if (QuickSettingsConfig.groups(config).stream().anyMatch(group -> groupId.equals(group.get("id"))))
            return groupId;
        return String.valueOf(QuickSettingsConfig.findOrCreateGroup(config,
                String.valueOf(control.getOrDefault("section", "Коммутация и управление"))).get("id"));
    }

    private static Map<String, Object> find(Map<String, Object> config, String routeId, String nodeId) {
        return QuickSettingsConfig.pins(config).stream()
                .filter(pin -> routeId.equals(pin.get("route_id")) && nodeId.equals(pin.get("node_id")))
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
