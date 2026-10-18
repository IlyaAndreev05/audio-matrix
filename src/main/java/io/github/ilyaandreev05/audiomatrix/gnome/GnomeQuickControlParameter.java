package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GateNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.RouterNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves safe, typed graph parameters for GNOME quick controls. */
public final class GnomeQuickControlParameter {
    private GnomeQuickControlParameter() {}

    public record Definition(String id, String russianLabel, String englishLabel) {}

    public static List<Definition> definitions(GraphNode node) {
        if (node instanceof GainNode) return List.of(definition("gain", "Громкость", "Volume"));
        if (node instanceof GateNode) return List.of(definition("open", "Gate", "Gate"));
        if (node instanceof RouterNode) return List.of(definition("active_links", "Связи", "Connections"));
        return node instanceof FilterNode filter ? GnomeQuickFilterControls.definitions(filter) : List.of();
    }

    public static Definition definition(GraphNode node, String parameter) {
        return definitions(node).stream().filter(item -> item.id().equals(parameter)).findFirst().orElse(null);
    }

    public static Map<String, Object> describe(GraphNode node, String parameter, String language) {
        return describe(node.type(), node.parameters(), parameter, language);
    }

    static Map<String, Object> describe(String nodeType, Map<String, Object> parameters,
                                        String parameter, String language) {
        if ("filter".equals(nodeType)) return GnomeQuickFilterControls.describe(parameters, parameter, language);
        Object value = parameters.get(parameter);
        if (value == null) return null;
        if ("gain".equals(nodeType) && "gain".equals(parameter)) return simple("slider", value);
        if ("gain".equals(nodeType) && "muted".equals(parameter)
                || "gate".equals(nodeType) && "open".equals(parameter)) return simple("toggle", value);
        if (List.of("switch", "selector", "multi_selector", "matrix")
                .contains(nodeType) && "active_links".equals(parameter)) return simple("links", value);
        return null;
    }

    public static void apply(Map<String, Object> encodedNode, String parameter, String rawValue, boolean toggle) {
        Map<String, Object> parameters = mutableMap(encodedNode.get("parameters"));
        encodedNode.put("parameters", parameters);
        String nodeType = String.valueOf(encodedNode.getOrDefault("type", ""));
        if ("filter".equals(nodeType)) {
            GnomeQuickFilterControls.apply(encodedNode, parameters, parameter, rawValue, toggle);
            return;
        }
        if (toggle && "gain".equals(nodeType) && "gain".equals(parameter)) {
            Object muted = parameters.get("muted");
            if (!(muted instanceof Boolean flag)) throw new IllegalArgumentException("Gain mute state is unavailable");
            parameters.put("muted", !flag);
            return;
        }
        Map<String, Object> descriptor = describe(nodeType, parameters, parameter, "en");
        if (descriptor == null) throw new IllegalArgumentException("Quick parameter is not supported");
        if (toggle) {
            if (!"toggle".equals(descriptor.get("kind")) || !(descriptor.get("value") instanceof Boolean flag))
                throw new IllegalArgumentException("This parameter is not a toggle");
            parameters.put(parameter, !flag);
            return;
        }
        if (!"slider".equals(descriptor.get("kind"))) throw new IllegalArgumentException("This parameter is not a slider");
        double value;
        try { value = Double.parseDouble(rawValue); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Control value is invalid"); }
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("Control value is outside the supported range");
        parameters.put(parameter, value);
        if ("gain".equals(nodeType) && "gain".equals(parameter)) parameters.put("muted", false);
    }

    public static void choose(Map<String, Object> encodedNode, String parameter, String choice) {
        Map<String, Object> parameters = mutableMap(encodedNode.get("parameters"));
        encodedNode.put("parameters", parameters);
        if (!"filter".equals(String.valueOf(encodedNode.getOrDefault("type", ""))))
            throw new IllegalArgumentException("This parameter does not have choices");
        GnomeQuickFilterControls.choose(encodedNode, parameters, parameter, choice);
    }

    private static Definition definition(String id, String ru, String en) { return new Definition(id, ru, en); }

    private static Map<String, Object> simple(String kind, Object value) { return Map.of("kind", kind, "value", value); }

    private static Map<String, Object> mutableMap(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
