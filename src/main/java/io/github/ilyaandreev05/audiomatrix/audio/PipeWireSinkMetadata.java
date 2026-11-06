package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Reads actual endpoint metadata without inventing a layout from a channel count. */
final class PipeWireSinkMetadata {
    private PipeWireSinkMetadata() {}

    static int channelCount(JsonNode props, JsonNode root, int nodeId) {
        JsonNode position = props.path("audio.position");
        if (position.isArray() && !position.isEmpty()) return position.size();
        if (position.isTextual() && !position.asText().isBlank()) {
            String raw = position.asText().replaceAll("[\\[\\]\"]", "").trim();
            if (!raw.isEmpty()) return raw.split("[,\\s]+").length;
        }
        List<String> ports = portPositions(root, nodeId);
        return ports.isEmpty() ? 2 : ports.size();
    }

    static void addPortPositions(Map<String, String> properties, JsonNode root, int nodeId, int channels) {
        if (!properties.getOrDefault("audio.position", "").isBlank()) return;
        List<String> positions = portPositions(root, nodeId);
        if (positions.size() == channels && !positions.isEmpty())
            properties.put("audio.position", String.join(",", positions));
    }

    static List<Integer> sampleRates(JsonNode formats) {
        List<Integer> result = new ArrayList<>();
        if (formats.isArray()) for (JsonNode format : formats) addSampleRate(result, format.path("rate"));
        return result.stream().distinct().sorted().toList();
    }

    private static void addSampleRate(List<Integer> rates, JsonNode rate) {
        if (rate.isInt()) rates.add(rate.asInt());
        else if (rate.isObject()) for (String key : List.of("default", "min", "max"))
            if (rate.path(key).isInt()) rates.add(rate.path(key).asInt());
    }

    private static List<String> portPositions(JsonNode root, int nodeId) {
        TreeMap<Integer, String> positions = new TreeMap<>();
        for (JsonNode object : root) {
            if (!object.path("type").asText("").endsWith(":Port")) continue;
            JsonNode props = object.path("info").path("props");
            if (props.path("node.id").asInt(-1) != nodeId || !"in".equals(props.path("port.direction").asText(""))) continue;
            int index = props.path("port.id").asInt(-1);
            String channel = props.path("audio.channel").asText("").toUpperCase(java.util.Locale.ROOT);
            if (index < 0 || channel.isBlank() || positions.putIfAbsent(index, channel) != null) return List.of();
        }
        List<String> result = new ArrayList<>(positions.values());
        if (new HashSet<>(result).size() != result.size()) return List.of();
        int index = 0;
        for (int value : positions.keySet()) if (value != index++) return List.of();
        return result;
    }
}
