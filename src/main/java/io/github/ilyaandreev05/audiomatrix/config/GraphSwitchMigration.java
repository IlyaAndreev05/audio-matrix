package io.github.ilyaandreev05.audiomatrix.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lossless cutover of every stored graph, including nested saved copies and subgraphs. */
final class GraphSwitchMigration {
    private GraphSwitchMigration() {}

    static void upgrade(Map<String, Object> config) {
        for (String key : List.copyOf(config.keySet())) config.put(key, migrate(config.get(key)));
    }

    private static Object migrate(Object value) {
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(migrate(item));
            return result;
        }
        if (!(value instanceof Map<?, ?> raw)) return value;
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), migrate(item)));
        if (result.containsKey("id") && result.get("parameters") instanceof Map<?, ?> parameters
                && ("switch".equals(result.get("type")) || "multi_switch".equals(result.get("type")))) {
            result.put("type", "switch");
            Map<String, Object> updated = new LinkedHashMap<>();
            parameters.forEach((key, item) -> updated.put(String.valueOf(key), item));
            // Keep the exact selected ports: old exclusive selections stay exclusive until edited.
            updated.put("multiple", true);
            result.put("parameters", updated);
        }
        return result;
    }
}
