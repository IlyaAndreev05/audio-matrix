package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Normalizes and converts the parameter payload carried by a dynamic generator node. */
final class DynamicGeneratorParameters {
    private DynamicGeneratorParameters() {}

    static Map<String, Object> normalizeParameters(String selector, List<String> channels,
                                                   Map<String, Object> parameters) {
        Map<String, Object> normalized = mutableMap(parameters);
        normalized.put("selector", selector);
        putDefault(normalized, "registry", "applications");
        String registry = text(normalized.get("registry"), "applications");
        putDefault(normalized, "template", DynamicGeneratorTemplateLibrary.defaultTemplate(registry));
        putDefault(normalized, "initial_gain", 1.0);
        putDefault(normalized, "auto_pin", Boolean.TRUE);
        putDefault(normalized, "group_id", "");
        putDefault(normalized, "retain_on_disappear", Boolean.TRUE);
        putDefault(normalized, "restore_on_reappear", Boolean.TRUE);
        putDefault(normalized, "excluded_selectors", List.of());
        putDefault(normalized, "instances", Map.of());
        if (!normalized.containsKey("template_nodes") || !normalized.containsKey("template_connections")) {
            DynamicGeneratorTemplateLibrary.DefaultTemplate defaults =
                    DynamicGeneratorTemplateLibrary.defaultTemplateGraph(registry, selector, channels,
                            number(normalized.get("initial_gain"), 1.0));
            putDefault(normalized, "template_nodes", defaults.nodes());
            putDefault(normalized, "template_connections", defaults.connections());
        }
        return normalized;
    }

    private static void putDefault(Map<String, Object> parameters, String key, Object value) {
        if (!parameters.containsKey(key)) parameters.put(key, value);
    }

    static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) result.add(stringMap(map));
        return List.copyOf(result);
    }

    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? stringMap(map) : Map.of();
    }

    private static Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return Collections.unmodifiableMap(result);
    }

    static Map<String, Object> mutableMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source != null) source.forEach((key, value) -> result.put(String.valueOf(key), mutableValue(value)));
        return result;
    }

    private static Object mutableValue(Object value) {
        if (value instanceof Map<?, ?> map) return mutableMap(map);
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(mutableValue(item)));
            return result;
        }
        return value;
    }

    static Map<String, Object> immutableMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(result);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), immutableValue(item)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(immutableValue(item)));
            return Collections.unmodifiableList(result);
        }
        return value;
    }

    static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean result ? result : fallback;
    }
}
