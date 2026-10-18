package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphEndpointGainFactory;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;

/** Migrates saved route endpoints to explicit unity Gain nodes. */
final class GraphEndpointGainMigration {
    private GraphEndpointGainMigration() {}

    static void migrate(Map<String, Object> config) {
        List<Map<String, Object>> routes = mutableList(config.get("route_library"));
        List<Map<String, Object>> copies = mutableList(config.get("saved_copies"));
        List<Map<String, Object>> controls = mutableList(config.get("quick_controls"));
        config.put("route_library", routes);
        config.put("saved_copies", copies);
        config.put("quick_controls", controls);
        for (Map<String, Object> route : routes) migrateRoute(config, route);
        for (Map<String, Object> copy : copies) migrateCopy(config, copy);
    }

    private static void migrateRoute(Map<String, Object> config, Map<String, Object> route) {
        AudioGraph graph = GraphCodec.fromMap(route);
        GraphRouteLibrary.saveRoute(config, graph, text(route.getOrDefault("group_id", "")),
                text(route.get("profile_id")));
    }

    private static void migrateCopy(Map<String, Object> config, Map<String, Object> copy) {
        if (!(copy.get("route") instanceof Map<?, ?> raw)) return;
        Map<String, Object> route = object(raw);
        AudioGraph graph = GraphEndpointGainFactory.ensure(GraphCodec.fromMap(route));
        Map<String, Object> updated = new LinkedHashMap<>(GraphCodec.toMap(graph));
        route.forEach((key, value) -> {
            if (!"nodes".equals(key) && !"connections".equals(key)) updated.put(key, value);
        });
        copy.put("route", updated);
        ensureCopySourceControls(config, copy, route, graph);
    }

    private static void ensureCopySourceControls(Map<String, Object> config, Map<String, Object> copy,
                                                 Map<String, Object> route, AudioGraph graph) {
        String routeId = text(route.get("id"));
        List<Map<String, Object>> pins = mutableCopyList(copy.get("quick_pins"));
        List<Map<String, Object>> controls = mutableCopyList(copy.get("quick_controls"));
        for (SourceNode source : graph.nodes().stream().filter(SourceNode.class::isInstance)
                .map(SourceNode.class::cast).toList()) {
            GainNode gain = GraphEndpointGainFactory.gainFor(graph, source);
            Map<String, Object> sourcePin = findPin(pins, routeId, source.id());
            if (gain == null || sourcePin == null || findPin(pins, routeId, gain.id()) != null) continue;
            String controlId = "quick-endpoint-copy-" + UUID.randomUUID();
            int order = nextOrder(pins, String.valueOf(sourcePin.get("group_id")));
            Map<String, Object> control = DefaultConfig.map("id", controlId, "route_id", routeId,
                    "node_id", gain.id(), "parameter_id", "gain", "label", source.name() + " · уровень",
                    "section", groupName(config, sourcePin), "group_id", sourcePin.get("group_id"), "order", order);
            controls.add(control);
            pins.add(DefaultConfig.map("id", "pin-" + UUID.randomUUID(), "route_id", routeId,
                    "node_id", gain.id(), "group_id", sourcePin.get("group_id"), "order", order,
                    "view", "EXPANDED", "control_ids", List.of(controlId)));
        }
        if (!pins.isEmpty()) copy.put("quick_pins", pins);
        if (!controls.isEmpty()) copy.put("quick_controls", controls);
    }

    private static Map<String, Object> findPin(List<Map<String, Object>> pins, String routeId, String nodeId) {
        return pins.stream().filter(pin -> routeId.equals(pin.get("route_id"))
                && nodeId.equals(pin.get("node_id"))).findFirst().orElse(null);
    }

    private static int nextOrder(List<Map<String, Object>> pins, String groupId) {
        return pins.stream().filter(pin -> groupId.equals(pin.get("group_id")))
                .mapToInt(pin -> pin.get("order") instanceof Number value ? value.intValue() : -1)
                .max().orElse(-1) + 1;
    }

    private static String groupName(Map<String, Object> config, Map<String, Object> pin) {
        return QuickSettingsConfig.groups(config).stream()
                .filter(group -> pin.get("group_id").equals(group.get("id")))
                .map(group -> String.valueOf(group.get("name"))).findFirst().orElse("Источники");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mutableCopyList(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) for (Object item : list)
            if (item instanceof Map<?, ?> raw) result.add(object(raw));
        return result;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private static Map<String, Object> object(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static List<Map<String, Object>> mutableList(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) for (Object item : list)
            if (item instanceof Map<?, ?> raw) result.add(object(raw));
        return result;
    }
}
