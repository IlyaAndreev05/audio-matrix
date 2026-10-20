package io.github.ilyaandreev05.audiomatrix.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts values written through wpctl's cubic volume scale into equivalent linear gains. */
final class LegacyLinearScaleMigration {
    private LegacyLinearScaleMigration() {}

    static void cube(Map<String, Object> config) {
        for (String key : List.of("devices", "applications", "buses", "routes"))
            for (Map<String, Object> item : maps(config.get(key))) cubeField(item, "volume");
        Map<String, Object> mixer = object(config.get("mixer"));
        cubeField(mixer, "global_volume");
        if (!mixer.isEmpty()) config.put("mixer", mixer);
        for (Map<String, Object> route : maps(config.get("route_library"))) cubeGraph(route);
        if (config.get("common_vibro_graph") instanceof Map<?, ?> raw) {
            Map<String, Object> graph = object(raw);
            cubeGraph(graph);
            config.put("common_vibro_graph", graph);
        }
    }

    private static void cubeGraph(Map<String, Object> graph) {
        for (Map<String, Object> node : maps(graph.get("nodes"))) {
            Map<String, Object> params = object(node.get("parameters"));
            if ("gain".equals(node.get("type"))) cubeField(params, "gain");
            if ("mixer".equals(node.get("type")) && params.get("input_gains") instanceof Map<?, ?> raw) {
                Map<String, Object> gains = object(raw);
                gains.replaceAll((key, value) -> cubed(value));
                params.put("input_gains", gains);
            }
            node.put("parameters", params);
        }
    }

    private static void cubeField(Map<String, Object> item, String field) {
        if (item.containsKey(field)) item.put(field, cubed(item.get(field)));
    }

    private static Object cubed(Object value) {
        if (!(value instanceof Number number)) return value;
        double level = number.doubleValue();
        if (!Double.isFinite(level) || level < 0) return value;
        if (level > 1) return 1.0;
        return level * level * level;
    }

    private static Map<String, Object> object(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object raw) {
        return raw instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
