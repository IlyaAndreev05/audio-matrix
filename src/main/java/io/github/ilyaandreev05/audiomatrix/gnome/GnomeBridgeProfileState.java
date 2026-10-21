package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal profile selector state; editor-only profile settings stay out of the shell payload. */
final class GnomeBridgeProfileState {
    private GnomeBridgeProfileState() {}

    static List<Map<String, Object>> profiles(Map<String, Object> config) {
        List<Map<String, Object>> result = new ArrayList<>();
        Object value = config.get("profiles");
        if (!(value instanceof List<?> profiles)) return List.of();
        for (Object item : profiles) {
            if (!(item instanceof Map<?, ?> profile)) continue;
            Object id = profile.get("id");
            Object name = profile.get("name");
            if (!(id instanceof String) || !(name instanceof String)) continue;
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("id", id);
            choice.put("name", name);
            result.add(choice);
        }
        return List.copyOf(result);
    }
}
