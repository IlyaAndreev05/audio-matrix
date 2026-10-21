package io.github.ilyaandreev05.audiomatrix.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;

/** Configuration helpers for GNOME mixer groups and route-scoped node pins. */
public final class QuickSettingsConfig {
    private QuickSettingsConfig() {}

    public static Map<String, Object> defaults() {
        return DefaultConfig.map("enabled", true, "show_output", true, "show_mode", true,
                "show_mute", true, "open_full_settings", true, "groups", defaultGroups(),
                "pins", new ArrayList<>());
    }

    public static List<Map<String, Object>> defaultGroups() {
        return new ArrayList<>(List.of(
                group("applications", "Приложения", 0),
                group("outputs", "Выходные устройства", 1),
                group("routing", "Маршрутизация", 2),
                group("events", "Звуки событий", 3)));
    }

    public static void migrate(Map<String, Object> config) {
        Map<String, Object> settings = object(config.get("quick_settings"));
        config.put("quick_settings", settings);
        Map<String, Object> defaults = defaults();
        defaults.forEach(settings::putIfAbsent);
        List<Map<String, Object>> groups = maps(settings.get("groups"));
        settings.put("groups", groups);
        List<Map<String, Object>> controls = maps(config.get("quick_controls"));
        for (Map<String, Object> control : controls)
            ensureControlGroup(control, groups);
    }

    /** Adds safe, editable bass-filter controls to the existing tactile details group once per schema upgrade. */
    public static void migrateVibroBassControls(Map<String, Object> config) {
        if (GraphCommonVibro.graph(config) == null) return;
        String routeId = GraphCommonVibro.COMMON_ROUTE_ID;
        String filterId = "octine-bass-low-pass";
        Map<String, Object> mixerPin = pins(config).stream()
                .filter(pin -> routeId.equals(pin.get("route_id"))
                        && GraphCommonVibro.VIBRO_MIXER_ID.equals(pin.get("node_id")))
                .findFirst().orElse(null);
        if (mixerPin == null) return;
        String groupId = String.valueOf(mixerPin.get("group_id"));
        Map<String, Object> filterPin = pins(config).stream()
                .filter(pin -> routeId.equals(pin.get("route_id")) && filterId.equals(pin.get("node_id")))
                .findFirst().orElse(null);
        if (filterPin == null) {
            int order = pins(config).stream().filter(pin -> groupId.equals(pin.get("group_id")))
                    .mapToInt(pin -> pin.get("order") instanceof Number number ? number.intValue() : 0)
                    .max().orElse(0) + 1;
            filterPin = pinNode(config, routeId, filterId, groupId, order, "COMPACT");
        }
        List<Map<String, Object>> controls = new ArrayList<>(maps(config.get("quick_controls")));
        config.put("quick_controls", controls);
        addBassControl(controls, filterPin, routeId, filterId, "octine-bass-frequency",
                "frequency_hz", "Частота басов", 0);
        addBassControl(controls, filterPin, routeId, filterId, "octine-bass-slope",
                "slope_db_oct", "Крутизна басового фильтра", 1);
    }

    private static void addBassControl(List<Map<String, Object>> controls, Map<String, Object> pin,
                                       String routeId, String nodeId, String id, String parameter,
                                       String label, int order) {
        Map<String, Object> control = controls.stream().filter(item -> id.equals(item.get("id"))).findFirst().orElse(null);
        if (control == null) {
            control = DefaultConfig.map("id", id, "route_id", routeId, "node_id", nodeId,
                    "parameter_id", parameter, "label", label, "section", "Подробности вибродинамика",
                    "order", order, "view", "MATRIX", "group_id", pin.get("group_id"));
            controls.add(control);
        }
        List<String> ids = pin.get("control_ids") instanceof List<?> raw
                ? raw.stream().map(String::valueOf).collect(java.util.stream.Collectors.toCollection(ArrayList::new))
                : new ArrayList<>();
        pin.put("control_ids", ids);
        if (!ids.contains(id)) ids.add(id);
    }

    public static List<Map<String, Object>> groups(Map<String, Object> config) {
        return maps(object(config.get("quick_settings")).get("groups"));
    }

    public static List<Map<String, Object>> pins(Map<String, Object> config) {
        return QuickSettingsPinLibrary.pins(config);
    }

    public static Map<String, Object> pinNode(Map<String, Object> config, String routeId, String nodeId,
                                               String groupId, int order, String view) {
        return QuickSettingsPinLibrary.pinNode(config, routeId, nodeId, groupId, order, view);
    }

    public static Map<String, Object> pinNode(Map<String, Object> config, String routeId, String nodeId,
                                               String instanceKey, String groupId, int order, String view) {
        return QuickSettingsPinLibrary.pinNode(config, routeId, nodeId, instanceKey, groupId, order, view);
    }

    public static Map<String, Object> updatePin(Map<String, Object> config, String routeId, String nodeId,
                                                String instanceKey, String groupId, int order, String view,
                                                String displayName, String icon, Boolean showChildren,
                                                Boolean showWhenOffline) {
        return QuickSettingsPinLibrary.updatePin(config, routeId, nodeId, instanceKey, groupId, order, view,
                displayName, icon, showChildren, showWhenOffline);
    }

    public static boolean unpinNode(Map<String, Object> config, String routeId, String nodeId, String instanceKey) {
        return QuickSettingsPinLibrary.unpinNode(config, routeId, nodeId, instanceKey);
    }

    public static void attachControl(Map<String, Object> config, String routeId, String nodeId, String controlId) {
        QuickSettingsPinLibrary.attachControl(config, routeId, nodeId, controlId);
    }

    public static void removeControl(Map<String, Object> config, String routeId, String nodeId, String controlId,
                                     boolean keepEmptyPin) {
        QuickSettingsPinLibrary.removeControl(config, routeId, nodeId, controlId, keepEmptyPin);
    }

    public static void removeNode(Map<String, Object> config, String routeId, String nodeId) {
        QuickSettingsPinLibrary.removeNode(config, routeId, nodeId);
    }

    public static void removeRoute(Map<String, Object> config, String routeId) {
        QuickSettingsPinLibrary.removeRoute(config, routeId);
    }

    public static void duplicateRoute(Map<String, Object> config, String sourceRoute, String targetRoute,
                                      Map<String, String> nodeIds, Map<String, String> controlIds) {
        QuickSettingsPinLibrary.duplicateRoute(config, sourceRoute, targetRoute, nodeIds, controlIds);
    }

    public static List<Map<String, Object>> copyPinsForRoute(Map<String, Object> config, String routeId) {
        return QuickSettingsPinLibrary.copyPinsForRoute(config, routeId);
    }

    public static Map<String, String> restorePinsFromCopy(Map<String, Object> config, Object savedPins,
                                                           String targetRoute, Map<String, String> controlIds) {
        return QuickSettingsPinLibrary.restorePinsFromCopy(config, savedPins, targetRoute, controlIds);
    }

    public static void migrateNodePins(Map<String, Object> config) {
        QuickSettingsPinLibrary.migrateNodePins(config);
    }

    public static Map<String, Object> findOrCreateGroup(Map<String, Object> config, String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Group name must not be blank");
        List<Map<String, Object>> groups = groups(config);
        Map<String, Object> found = groups.stream()
                .filter(group -> name.equalsIgnoreCase(String.valueOf(group.get("name"))))
                .findFirst().orElse(null);
        if (found != null) return found;
        Map<String, Object> created = group("quick-" + UUID.randomUUID(), name.trim(), groups.size());
        groups.add(created);
        return created;
    }
    public static Map<String, Object> createGroup(Map<String, Object> config, String name, int order) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Group name must not be blank");
        if (order < 0) throw new IllegalArgumentException("Group order must not be negative");
        Map<String, Object> created = group("quick-" + UUID.randomUUID(), name.trim(), order);
        groups(config).add(created);
        return created;
    }

    public static Map<String, Object> updateGroup(Map<String, Object> config, String groupId, String name,
                                                  int order, Boolean collapsed) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Group name must not be blank");
        if (order < 0) throw new IllegalArgumentException("Group order must not be negative");
        Map<String, Object> group = groups(config).stream()
                .filter(item -> groupId.equals(String.valueOf(item.get("id")))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Quick settings group does not exist"));
        group.put("name", name.trim());
        group.put("order", order);
        if (collapsed != null) group.put("collapsed", collapsed);
        return group;
    }

    public static void removeGroup(Map<String, Object> config, String groupId) {
        QuickSettingsPinLibrary.removeGroup(config, groupId);
    }

    private static void ensureControlGroup(Map<String, Object> control, List<Map<String, Object>> groups) {
        String existingId = String.valueOf(control.getOrDefault("group_id", ""));
        if (groups.stream().anyMatch(group -> existingId.equals(group.get("id")))) return;
        String name = String.valueOf(control.getOrDefault("section", "Коммутация и управление"));
        Map<String, Object> match = groups.stream()
                .filter(group -> name.equalsIgnoreCase(String.valueOf(group.get("name"))))
                .findFirst().orElse(null);
        if (match == null) {
            match = group("quick-" + UUID.randomUUID(), name, groups.size());
            groups.add(match);
        }
        control.put("group_id", match.get("id"));
    }

    private static Map<String, Object> group(String id, String name, int order) {
        return DefaultConfig.map("id", id, "name", name, "order", order, "collapsed", false);
    }

    private static Map<String, Object> object(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        if (value instanceof List<?> list) return (List<Map<String, Object>>) list;
        return new ArrayList<>();
    }
}
