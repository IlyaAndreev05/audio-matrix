package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Filter-specific controls exposed in GNOME while keeping their values in the graph. */
final class GnomeQuickFilterControls {
    private GnomeQuickFilterControls() {}

    static List<GnomeQuickControlParameter.Definition> definitions(FilterNode node) {
        if (!supported(node.processor())) return List.of();
        List<GnomeQuickControlParameter.Definition> result = new ArrayList<>();
        result.add(definition("enabled", "Фильтр", "Filter"));
        switch (node.processor()) {
            case "low_pass", "high_pass" -> {
                result.add(definition("frequency_hz", "Частота среза", "Cutoff frequency"));
                result.add(definition("slope_db_oct", "Крутизна", "Slope"));
            }
            case "delay" -> result.add(definition("delay_ms", "Задержка", "Delay"));
            case "limiter" -> {
                result.add(definition("ceiling_db", "Порог", "Ceiling"));
                result.add(definition("lookahead_ms", "Упреждение", "Lookahead"));
                result.add(definition("release_ms", "Восстановление", "Release"));
            }
            case "eq" -> addBandDefinitions(result, node.parameters());
            default -> { }
        }
        return List.copyOf(result);
    }

    static Map<String, Object> describe(Map<String, Object> parameters, String parameter, String language) {
        Object value = read(parameters, parameter);
        if (value == null) return null;
        String processor = String.valueOf(parameters.getOrDefault("processor", ""));
        if (!supported(processor)) return null;
        if ("enabled".equals(parameter)) return simple("toggle", value);
        if (("low_pass".equals(processor) || "high_pass".equals(processor))
                && "frequency_hz".equals(parameter)) return range(value, 20, 20000, "log", "Hz", 0);
        if (("low_pass".equals(processor) || "high_pass".equals(processor))
                && "slope_db_oct".equals(parameter)) return choice(value, List.of(6, 12, 18, 24, 36, 48), language, "slope");
        if ("delay".equals(processor) && "delay_ms".equals(parameter)) return range(value, 0, 5000, "linear", "ms", 0);
        if ("limiter".equals(processor)) {
            if ("ceiling_db".equals(parameter)) return range(value, -12, 0, "linear", "dB", 1);
            if ("lookahead_ms".equals(parameter)) return range(value, 1, 10, "linear", "ms", 1);
            if ("release_ms".equals(parameter)) return range(value, 5, 500, "log", "ms", 0);
        }
        return bandControl(parameters, parameter, value, language);
    }

    static void apply(Map<String, Object> encodedNode, Map<String, Object> source,
                      String parameter, String rawValue, boolean toggle) {
        Map<String, Object> parameters = copyMap(source);
        encodedNode.put("parameters", parameters);
        Map<String, Object> descriptor = describe(parameters, parameter, "en");
        if (descriptor == null) throw new IllegalArgumentException("Filter parameter is not supported");
        String kind = String.valueOf(descriptor.get("kind"));
        if (toggle) {
            if (!"toggle".equals(kind) || !(descriptor.get("value") instanceof Boolean flag))
                throw new IllegalArgumentException("This parameter is not a toggle");
            write(parameters, parameter, !flag);
            return;
        }
        if (!"range".equals(kind)) throw new IllegalArgumentException("This parameter is not a slider");
        double value;
        try { value = Double.parseDouble(rawValue); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Control value is invalid"); }
        if (!Double.isFinite(value) || value < number(descriptor.get("minimum")) || value > number(descriptor.get("maximum")))
            throw new IllegalArgumentException("Control value is outside the supported range");
        write(parameters, parameter, value);
    }

    static void choose(Map<String, Object> encodedNode, Map<String, Object> source, String parameter, String choice) {
        Map<String, Object> parameters = copyMap(source);
        encodedNode.put("parameters", parameters);
        Map<String, Object> descriptor = describe(parameters, parameter, "en");
        if (descriptor == null || !"choice".equals(descriptor.get("kind")))
            throw new IllegalArgumentException("This parameter does not have choices");
        if (maps(descriptor.get("options")).stream().noneMatch(option -> choice.equals(String.valueOf(option.get("id")))))
            throw new IllegalArgumentException("Control choice is invalid");
        Object previous = read(parameters, parameter);
        Object value = previous instanceof Integer ? Integer.valueOf(choice)
                : previous instanceof Number ? Double.valueOf(choice) : choice;
        write(parameters, parameter, value);
    }

    private static Map<String, Object> bandControl(Map<String, Object> parameters, String parameter,
                                                    Object value, String language) {
        if (!"eq".equals(parameters.get("processor"))) return null;
        String[] parts = parameter.split("\\.", -1);
        if (parts.length != 3 || !"bands".equals(parts[0])) return null;
        int index;
        try { index = Integer.parseInt(parts[1]); }
        catch (NumberFormatException invalid) { return null; }
        List<?> bands = parameters.get("bands") instanceof List<?> list ? list : List.of();
        if (index < 0 || index >= bands.size() || !(bands.get(index) instanceof Map<?, ?>)) return null;
        return switch (parts[2]) {
            case "frequency_hz" -> range(value, 20, 20000, "log", "Hz", 0);
            case "gain_db" -> range(value, -24, 24, "linear", "dB", 1);
            case "q" -> range(value, .1, 20, "log", "Q", 2);
            case "type" -> choice(value, List.of("peaking", "low_shelf", "high_shelf", "notch"), language, "eq-type");
            case "enabled" -> simple("toggle", value);
            default -> null;
        };
    }

    private static void addBandDefinitions(List<GnomeQuickControlParameter.Definition> result, Map<String, Object> parameters) {
        Object raw = parameters.get("bands");
        if (!(raw instanceof List<?> bands)) return;
        for (int index = 0; index < Math.min(20, bands.size()); index++) {
            String prefix = "bands." + index + ".";
            String ru = "Полоса " + (index + 1), en = "Band " + (index + 1);
            result.add(definition(prefix + "frequency_hz", ru + " · частота", en + " · frequency"));
            result.add(definition(prefix + "gain_db", ru + " · усиление", en + " · gain"));
            result.add(definition(prefix + "q", ru + " · Q", en + " · Q"));
            result.add(definition(prefix + "type", ru + " · тип", en + " · type"));
            if (bands.get(index) instanceof Map<?, ?> band && band.containsKey("enabled"))
                result.add(definition(prefix + "enabled", ru + " · состояние", en + " · enabled"));
        }
    }

    private static Map<String, Object> range(Object value, double min, double max, String scale, String unit, int precision) {
        if (!(value instanceof Number)) return null;
        return Map.of("kind", "range", "value", value, "minimum", min, "maximum", max,
                "scale", scale, "unit", unit, "precision", precision);
    }

    private static Map<String, Object> choice(Object value, List<?> options, String language, String mode) {
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (Object option : options) {
            String id = String.valueOf(option);
            encoded.add(Map.of("id", id, "label", optionLabel(id, language, mode)));
        }
        return Map.of("kind", "choice", "value", value, "multiple", false, "options", encoded);
    }

    private static String optionLabel(String id, String language, String mode) {
        if ("slope".equals(mode)) return id + " dB/oct";
        if ("en".equals(language)) return switch (id) {
            case "peaking" -> "Peak / bell";
            case "low_shelf" -> "Low shelf";
            case "high_shelf" -> "High shelf";
            default -> "Notch";
        };
        return switch (id) {
            case "peaking" -> "Пик / колокол";
            case "low_shelf" -> "Полка НЧ";
            case "high_shelf" -> "Полка ВЧ";
            default -> "Режекторный";
        };
    }

    private static boolean supported(String processor) {
        return List.of("eq", "low_pass", "high_pass", "delay", "limiter").contains(processor);
    }

    private static Map<String, Object> simple(String kind, Object value) { return Map.of("kind", kind, "value", value); }

    private static GnomeQuickControlParameter.Definition definition(String id, String ru, String en) {
        return new GnomeQuickControlParameter.Definition(id, ru, en);
    }

    private static Object read(Map<String, Object> parameters, String path) {
        String[] parts = path.split("\\.", -1);
        if (parts.length == 1) return parameters.get(path);
        if (parts.length != 3 || !"bands".equals(parts[0])) return null;
        int index;
        try { index = Integer.parseInt(parts[1]); }
        catch (NumberFormatException invalid) { return null; }
        List<?> bands = parameters.get("bands") instanceof List<?> list ? list : List.of();
        if (index < 0 || index >= bands.size() || !(bands.get(index) instanceof Map<?, ?> band)) return null;
        return band.get(parts[2]);
    }

    private static void write(Map<String, Object> parameters, String path, Object value) {
        String[] parts = path.split("\\.", -1);
        if (parts.length == 1) { parameters.put(path, value); return; }
        Object raw = parameters.get("bands");
        int index;
        try { index = Integer.parseInt(parts[1]); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Quick parameter path is invalid"); }
        if (parts.length != 3 || !"bands".equals(parts[0]) || !(raw instanceof List<?> bands)
                || index < 0 || index >= bands.size() || !(bands.get(index) instanceof Map<?, ?> band))
            throw new IllegalArgumentException("EQ band is unavailable");
        @SuppressWarnings("unchecked") Map<String, Object> mutable = (Map<String, Object>) band;
        mutable.put(parts[2], value);
    }

    private static double number(Object value) { return value instanceof Number number ? number.doubleValue() : Double.NaN; }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, copy(value)));
        return result;
    }

    private static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), copy(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(GnomeQuickFilterControls::copy)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
