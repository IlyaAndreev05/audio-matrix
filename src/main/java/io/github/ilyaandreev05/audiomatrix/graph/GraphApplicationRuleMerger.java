package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Merges compiled bindings into canonical application rules without dropping saved controls. */
final class GraphApplicationRuleMerger {
    private GraphApplicationRuleMerger() {}

    static List<Map<String, Object>> copyConfiguredRules(Map<String, Object> config) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> rule : maps(config.get("applications"))) result.add(copyMap(rule));
        return result;
    }

    static void attach(List<Map<String, Object>> rules, Map<String, Object> rule, String fallbackId,
                       String busId, Map<String, Object> match) {
        Map<String, Object> updated = copyMap(rule);
        if (!(updated.get("id") instanceof String id) || id.isBlank()) updated.put("id", fallbackId);
        updated.put("bus", busId);
        updated.put("match", copyMap(match));
        for (int index = 0; index < rules.size(); index++) {
            if (object(rules.get(index).get("match")).equals(match)) {
                rules.set(index, updated);
                return;
            }
        }
        rules.add(updated);
    }

    static void preserveReferencedBuses(Map<String, Object> config, List<Map<String, Object>> rules,
                                        List<Map<String, Object>> buses) {
        Set<String> included = new LinkedHashSet<>();
        buses.forEach(bus -> included.add(text(bus.get("id"))));
        Map<String, Map<String, Object>> saved = new LinkedHashMap<>();
        for (Map<String, Object> bus : maps(config.get("buses"))) saved.put(text(bus.get("id")), bus);
        for (Map<String, Object> rule : rules) {
            String busId = text(rule.get("bus"));
            Map<String, Object> bus = saved.get(busId);
            if (busId.isBlank() || bus == null || !included.add(busId)) continue;
            buses.add(copyMap(bus));
        }
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) result.add(object(map));
        return result;
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        return object(map);
    }

    private static Map<String, Object> object(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static Map<String, Object> copyMap(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(key, copyValue(value)));
        return result;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) return object(map);
        if (value instanceof List<?> list) return list.stream().map(GraphApplicationRuleMerger::copyValue).toList();
        return value;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
