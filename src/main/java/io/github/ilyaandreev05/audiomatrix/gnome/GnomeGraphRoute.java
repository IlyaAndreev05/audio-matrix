package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GnomeGraphRoute {
    private GnomeGraphRoute() {}
    static Map<String, Object> find(Map<String, Object> config, String routeId) {
        return maps(config.get("route_library")).stream().filter(item -> routeId.equals(item.get("id")))
                .findFirst().orElse(null);
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> {
            Map<String, Object> result = new LinkedHashMap<>();
            ((Map<?, ?>) item).forEach((key, field) -> result.put(String.valueOf(key), field));
            return result;
        }).toList();
    }
}
