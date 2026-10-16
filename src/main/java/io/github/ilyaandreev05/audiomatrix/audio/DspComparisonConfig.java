package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creates an ephemeral EQ-bypass reference config with conservative gain matching. */
public final class DspComparisonConfig {
    private DspComparisonConfig() {}

    public static Map<String, Object> reference(Map<String, Object> source, double positiveEqGainBoundDb) {
        Map<String, Object> config = deepMap(source);
        double gain = Double.isFinite(positiveEqGainBoundDb) ? Math.max(0, positiveEqGainBoundDb) : 0;
        double compensation = Math.pow(10.0, -gain / 20.0);
        for (Map<String, Object> bus : maps(config.get("buses"))) {
            double volume = number(bus.get("volume"), 1.0);
            bus.put("volume", Math.max(0, Math.min(1, volume * compensation)));
        }
        Map<String, Object> dsp = map(config.get("dsp"));
        disableEq(map(dsp.get("global_eq")));
        for (Object value : map(dsp.get("devices")).values()) disableEq(map(value));
        for (Object value : map(dsp.get("route_chains")).values()) disableEq(map(value));
        disableEq(map(map(config.get("mixer")).get("global_eq")));
        return config;
    }

    private static void disableEq(Map<String, Object> eq) {
        if (!eq.isEmpty()) eq.put("enabled", false);
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) result.add(cast(map));
        return result;
    }

    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? cast(map) : new LinkedHashMap<>();
    }

    private static Map<String, Object> cast(Map<?, ?> source) {
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) source;
        return result;
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number && Double.isFinite(number.doubleValue()) ? number.doubleValue() : fallback;
    }

    private static Map<String, Object> deepMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, deepCopy(value)));
        return result;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), deepCopy(nested)));
            return copy;
        }
        if (value instanceof List<?> list) return list.stream().map(DspComparisonConfig::deepCopy).toList();
        return value;
    }
}
