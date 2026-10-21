package io.github.ilyaandreev05.audiomatrix.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;

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
        QuickSettingsVibroPins.migrateBassControls(config);
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

    public static void updatePin(Map<String, Object> config, String routeId, String nodeId, String instanceKey,
                                 String groupId, int order, String view, String displayName, String icon,
                                 Boolean showChildren, Boolean showWhenOffline) {
        QuickSettingsPinLibrary.updatePin(config, routeId, nodeId, instanceKey, groupId, order, view,
                displayName, icon, showChildren, showWhenOffline);
    }

    public static void unpinNode(Map<String, Object> config, String routeId, String nodeId, String instanceKey) {
        if (instanceKey != null && !instanceKey.isBlank()) {
            try {
                DynamicInstanceRegistry.setInstanceOverride(config, routeId, nodeId, instanceKey, "pinned", false);
            } catch (IllegalArgumentException missing) {
                // A vanished generated instance must still lose its presentation pin.
            }
        }
        QuickSettingsPinLibrary.removePin(config, routeId, nodeId, instanceKey);
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
        return QuickSettingsGroupLibrary.create(config, name, order);
    }

    public static Map<String, Object> updateGroup(Map<String, Object> config, String groupId, String name,
                                                  int order, boolean collapsed) {
        return QuickSettingsGroupLibrary.update(config, groupId, name, order, collapsed);
    }

    public static void removeGroup(Map<String, Object> config, String groupId) {
        QuickSettingsGroupLibrary.remove(config, groupId);
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
