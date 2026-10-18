package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts graph filter parameters into the route-filter format used by PipeWire. */
final class GraphFilterCompiler {
    private static final Set<String> BAND_TYPES = Set.of("peaking", "notch", "low_shelf", "high_shelf");
    private static final double MAX_DELAY_MS = 5000.0;

    private GraphFilterCompiler() {}

    static List<Map<String, Object>> routeFilters(FilterNode node) {
        if (!node.enabled()) return List.of();
        if ("eq".equals(node.processor())) return equalizerBands(node.parameters());
        if ("delay".equals(node.processor())) return List.of();
        Map<String, Object> filter = new LinkedHashMap<>(node.parameters());
        filter.remove("processor");
        filter.remove("enabled");
        filter.put("type", node.processor());
        return List.of(Map.copyOf(filter));
    }

    static double delayMs(FilterNode node) {
        if (!node.enabled() || !"delay".equals(node.processor())) return 0.0;
        Object value = node.parameters().getOrDefault("delay_ms", node.parameters().get("delayMs"));
        double delay = value instanceof Number number ? number.doubleValue() : Double.NaN;
        if (!Double.isFinite(delay) || delay < 0 || delay > MAX_DELAY_MS)
            throw new IllegalArgumentException("Graph delay must be between 0 and 5000 ms");
        return delay;
    }

    private static List<Map<String, Object>> equalizerBands(Map<String, Object> parameters) {
        Object raw = parameters.get("bands");
        if (raw == null && parameters.containsKey("frequency_hz")) raw = List.of(parameters);
        if (!(raw instanceof List<?> bands)) return List.of();
        if (bands.size() > 20) throw new IllegalArgumentException("A graph EQ supports at most 20 bands");
        List<Map<String, Object>> filters = new ArrayList<>();
        for (Object value : bands) {
            if (!(value instanceof Map<?, ?> band))
                throw new IllegalArgumentException("Every graph EQ band must be an object");
            if (Boolean.FALSE.equals(band.get("enabled"))) continue;
            filters.add(encodeBand(band));
        }
        return List.copyOf(filters);
    }

    private static Map<String, Object> encodeBand(Map<?, ?> band) {
        String type = bandType(first(band, "type", "filter_kind", "filterKind"));
        if (!BAND_TYPES.contains(type)) throw new IllegalArgumentException("Unsupported graph EQ band type: " + type);
        double frequency = number(first(band, "frequency_hz", "frequencyHz"));
        double gain = number(first(band, "gain_db", "gainDb"));
        double q = number(first(band, "q", "Q"));
        if (!Double.isFinite(frequency) || frequency < 20 || frequency > 20000)
            throw new IllegalArgumentException("EQ frequency must be between 20 and 20000 Hz");
        if (!Double.isFinite(gain) || gain < -24 || gain > 24)
            throw new IllegalArgumentException("EQ gain must be between -24 and +24 dB");
        if (!Double.isFinite(q) || q <= 0 || q > 20)
            throw new IllegalArgumentException("EQ Q must be above 0 and at most 20");
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("type", type);
        encoded.put("frequency_hz", frequency);
        encoded.put("gain_db", gain);
        encoded.put("q", q);
        Object channels = band.get("channels");
        if (channels instanceof List<?> list) encoded.put("channels", List.copyOf(list));
        return Map.copyOf(encoded);
    }

    private static String bandType(Object value) {
        String type = value == null ? "peaking" : value.toString().toLowerCase(java.util.Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        return switch (type) {
            case "peak", "bell" -> "peaking";
            case "lowshelf" -> "low_shelf";
            case "highshelf" -> "high_shelf";
            default -> type;
        };
    }

    private static Object first(Map<?, ?> map, String... keys) {
        for (String key : keys) if (map.containsKey(key)) return map.get(key);
        return null;
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.NaN;
    }
}
