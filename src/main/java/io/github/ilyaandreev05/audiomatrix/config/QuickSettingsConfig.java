package io.github.ilyaandreev05.audiomatrix.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Configuration helpers for GNOME mixer groups and route-scoped node pins. */
public final class QuickSettingsConfig {
    private QuickSettingsConfig() {}

    public static Map<String, Object> defaults() {
        return DefaultConfig.map("enabled", true, "show_output", true, "show_mode", true,
                "show_mute", true, "open_full_settings", true, "groups", defaultGroups());
    }

    public static List<Map<String, Object>> defaultGroups() {
        return new ArrayList<>(List.of(
                group("sources", "Источники", 0),
                group("outputs", "Выходные устройства", 1),
                group("routing", "Коммутация и управление", 2),
                group("effects", "Обработка звука", 3)));
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

    public static List<Map<String, Object>> groups(Map<String, Object> config) {
        return maps(object(config.get("quick_settings")).get("groups"));
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
