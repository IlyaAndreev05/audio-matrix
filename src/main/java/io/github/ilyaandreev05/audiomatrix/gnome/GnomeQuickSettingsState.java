package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the route and group presentation sent to GNOME Quick Settings. */
final class GnomeQuickSettingsState {
    private GnomeQuickSettingsState() {}

    static List<Map<String, Object>> groups(Map<String, Object> config) {
        Map<String, Object> settings = object(config.get("quick_settings"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> group : maps(settings.get("groups"))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", group.get("id"));
            item.put("name", group.get("name"));
            item.put("order", group.getOrDefault("order", 0));
            item.put("collapsed", Boolean.TRUE.equals(group.get("collapsed")));
            result.add(item);
        }
        result.sort(java.util.Comparator.comparingInt(item -> number(item.get("order"))));
        return List.copyOf(result);
    }

    static List<Map<String, Object>> routes(Map<String, Object> config) {
        String profileId = String.valueOf(config.getOrDefault("active_profile", ""));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> route : maps(config.get("route_library"))) {
            if (!profileId.equals(route.get("profile_id"))) continue;
            result.add(Map.of("id", route.get("id"), "name", route.get("name")));
        }
        return List.copyOf(result);
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Map<String, Object> object(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
            result.add(copy);
        }
        return result;
    }
}
