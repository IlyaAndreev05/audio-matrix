package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts retained quick-settings preferences to the new one-route tree. */
final class UnifiedGraphPins {
    private UnifiedGraphPins() {}

    static void migrate(Map<String, Object> config, AudioGraph graph) {
        List<Map<String, Object>> controls = new ArrayList<>();
        for (Map<String, Object> control : maps(config.get("quick_controls"))) {
            String nodeId = String.valueOf(control.getOrDefault("node_id", ""));
            if (graph.nodes().stream().noneMatch(node -> node.id().equals(nodeId))) continue;
            Map<String, Object> copy = new LinkedHashMap<>(control);
            copy.put("route_id", UnifiedGraphConfig.ROUTE_ID);
            copy.put("group_id", group(nodeId));
            controls.add(copy);
        }
        List<Map<String, Object>> migrated = new ArrayList<>();
        for (Map<String, Object> pin : new ArrayList<>(QuickSettingsConfig.pins(config))) {
            String nodeId = String.valueOf(pin.getOrDefault("node_id", ""));
            if (graph.nodes().stream().noneMatch(node -> node.id().equals(nodeId))) continue;
            Map<String, Object> copy = new LinkedHashMap<>(pin);
            copy.put("route_id", UnifiedGraphConfig.ROUTE_ID);
            copy.put("group_id", group(nodeId));
            copy.put("order", migrated.size());
            migrated.add(copy);
        }
        QuickSettingsConfig.pins(config).clear();
        List<String> retainedControls = controls.stream().map(control -> String.valueOf(control.get("id"))).toList();
        for (Map<String, Object> pin : migrated) {
            List<String> ids = strings(pin.get("control_ids"));
            ids.retainAll(retainedControls);
            pin.put("control_ids", ids);
        }
        QuickSettingsConfig.pins(config).addAll(migrated);
        config.put("quick_controls", controls);
        attachRetainedControls(config, graph, controls);
        Map<String, Object> settings = object(config.get("quick_settings"));
        settings.put("groups", groups());
        config.put("quick_settings", settings);
        pin(config, graph, GraphCommonVibro.MAIN_SOURCE_ID, "applications", 0);
        pin(config, graph, UnifiedGraphConfig.MONITOR_OUTPUT_ID, "outputs", 0);
        pin(config, graph, GraphCommonVibro.VIBRO_OUTPUT_ID, "outputs", 1);
        pin(config, graph, UnifiedGraphConfig.SWITCH_ID, "routing", 0);
        pin(config, graph, GraphCommonVibro.VIBRO_GAIN_ID, "routing", 1);
        pin(config, graph, UnifiedGraphConfig.MUSIC_GAIN_ID, "routing", 2);
        pin(config, graph, UnifiedGraphConfig.EVENT_SOURCE_ID, "events", 0);
        pin(config, graph, UnifiedGraphConfig.EVENT_GAIN_ID, "events", 1);
    }

    static void installDefaults(Map<String, Object> config, AudioGraph graph) {
        QuickSettingsConfig.pins(config).clear();
        pin(config, graph, GraphCommonVibro.MAIN_SOURCE_ID, "applications", 0);
        pin(config, graph, UnifiedGraphConfig.MONITOR_OUTPUT_ID, "outputs", 0);
        pin(config, graph, GraphCommonVibro.VIBRO_OUTPUT_ID, "outputs", 1);
        pin(config, graph, UnifiedGraphConfig.SWITCH_ID, "routing", 0);
        pin(config, graph, GraphCommonVibro.VIBRO_GAIN_ID, "routing", 1);
        pin(config, graph, UnifiedGraphConfig.MUSIC_GAIN_ID, "routing", 2);
        pin(config, graph, UnifiedGraphConfig.EVENT_SOURCE_ID, "events", 0);
        pin(config, graph, UnifiedGraphConfig.EVENT_GAIN_ID, "events", 1);
    }

    private static void pin(Map<String, Object> config, AudioGraph graph, String nodeId, String group, int order) {
        if (graph.nodes().stream().noneMatch(node -> node.id().equals(nodeId))) return;
        if (QuickSettingsConfig.pins(config).stream().anyMatch(item -> UnifiedGraphConfig.ROUTE_ID.equals(item.get("route_id"))
                && nodeId.equals(item.get("node_id")))) return;
        QuickSettingsConfig.pinNode(config, UnifiedGraphConfig.ROUTE_ID, nodeId, group, order, "DEFAULT");
    }

    private static void attachRetainedControls(Map<String, Object> config, AudioGraph graph,
                                               List<Map<String, Object>> controls) {
        for (Map<String, Object> control : controls) {
            String nodeId = String.valueOf(control.getOrDefault("node_id", ""));
            String controlId = String.valueOf(control.getOrDefault("id", ""));
            Map<String, Object> pin = QuickSettingsConfig.pins(config).stream()
                    .filter(item -> UnifiedGraphConfig.ROUTE_ID.equals(item.get("route_id"))
                            && nodeId.equals(item.get("node_id"))).findFirst().orElse(null);
            if (pin == null) {
                pin(config, graph, nodeId, group(nodeId), QuickSettingsConfig.pins(config).size());
                pin = QuickSettingsConfig.pins(config).stream()
                        .filter(item -> UnifiedGraphConfig.ROUTE_ID.equals(item.get("route_id"))
                                && nodeId.equals(item.get("node_id"))).findFirst().orElse(null);
            }
            if (pin == null) continue;
            List<String> ids = strings(pin.get("control_ids"));
            if (!ids.contains(controlId)) ids.add(controlId);
            pin.put("control_ids", ids);
        }
    }

    private static String group(String id) {
        if (id.equals(UnifiedGraphConfig.EVENT_SOURCE_ID) || id.equals(UnifiedGraphConfig.EVENT_GAIN_ID)) return "events";
        if (id.equals(GraphCommonVibro.MAIN_SOURCE_ID)) return "applications";
        if (id.endsWith("output")) return "outputs";
        return "routing";
    }

    private static List<Map<String, Object>> groups() {
        return new ArrayList<>(List.of(
                DefaultConfig.map("id", "applications", "name", "Приложения", "order", 0, "collapsed", false),
                DefaultConfig.map("id", "outputs", "name", "Выходные устройства", "order", 1, "collapsed", false),
                DefaultConfig.map("id", "routing", "name", "Маршрутизация", "order", 2, "collapsed", false),
                DefaultConfig.map("id", "events", "name", "Звуки событий", "order", 3, "collapsed", false)));
    }

    private static Map<String, Object> object(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
        return new ArrayList<>(list.stream().map(String::valueOf).toList());
    }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object raw) {
        return raw instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
