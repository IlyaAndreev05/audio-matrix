package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

final class DynamicRegistryValues {
    private DynamicRegistryValues() {}

    static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    static String safe(String value) { return value == null ? "" : value; }
    static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }
    static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    static double finiteLevel(double value, double fallback) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : fallback;
    }
    static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
    static Map<String, Object> mutableObject(Object value) { return object(value); }
    static Map<String, Object> mutableNestedObject(Map<String, Object> parent, String key) {
        Map<String, Object> result = mutableObject(parent.get(key));
        parent.put(key, result);
        return result;
    }
    static Map<String, String> nodeIds(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map)
            map.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return result;
    }
    static Map<String, Object> immutableObject(Map<String, Object> value) {
        return Collections.unmodifiableMap(mutableObject(deep(value == null ? Map.of() : value)));
    }
    static Object deep(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), deep(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(DynamicRegistryValues::deep).toList();
        return value;
    }
    static boolean deepEquals(Object first, Object second) { return java.util.Objects.deepEquals(first, second); }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static List<String> channels(AudioNode node) {
        if (node.identity() != null && !node.identity().channelPositions().isBlank()) {
            List<String> positions = Arrays.stream(node.identity().channelPositions().split(","))
                    .map(String::trim).filter(value -> !value.isBlank()).toList();
            if (!positions.isEmpty()) return positions;
        }
        if (node.channels() == 1) return List.of("MONO");
        if (node.channels() == 2) return List.of("FL", "FR");
        List<String> channels = new ArrayList<>(node.channels());
        for (int i = 0; i < node.channels(); i++) channels.add("CH" + (i + 1));
        return List.copyOf(channels);
    }
    static List<String> channels(GraphNode node) {
        if (!node.outputs().isEmpty()) return node.outputs().getFirst().channels();
        if (!node.inputs().isEmpty()) return node.inputs().getFirst().channels();
        if (node instanceof DynamicGeneratorNode generator) return generator.channels();
        throw new IllegalArgumentException("Generated template node has no audio channels: " + node.id());
    }
}
