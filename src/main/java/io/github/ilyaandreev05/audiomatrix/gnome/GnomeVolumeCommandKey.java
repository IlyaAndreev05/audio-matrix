package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Only validated absolute setters from an identified client may replace an adjacent pending setter. */
record GnomeVolumeCommandKey(String client, String kind, List<String> target) {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    static GnomeVolumeCommandKey from(GnomeControlBridge.Command command) {
        String id = command.requestId();
        int separator = id.indexOf(':');
        if (command.apiVersion() != GnomeControlBridge.API_VERSION || separator <= 0
                || id.substring(0, separator).isBlank() || id.substring(separator + 1).isBlank()) return null;
        List<String> target;
        try {
            target = switch (command.name()) {
                case "set_app_volume", "set_stream_volume" -> {
                    String[] fields = command.value().split("\\t", -1);
                    if (fields.length != 2 || fields[0].isBlank()) yield null;
                    double level = Double.parseDouble(fields[1]);
                    if (!Double.isFinite(level)) yield null;
                    if ("set_stream_volume".equals(command.name())) Integer.parseInt(fields[0]);
                    yield List.of(fields[0]);
                }
                case "setNodeVolume" -> nodeTarget(command.value());
                default -> null;
            };
        } catch (Exception invalid) {
            return null; // Malformed requests remain FIFO barriers and reach their normal handler.
        }
        return target == null ? null : new GnomeVolumeCommandKey(
                GnomeCommandRevision.clientId(command), command.name(), target);
    }

    // Keep exactly the setNodeVolume schema accepted by GnomeNodeCommandHandler.parse.
    private static List<String> nodeTarget(String value) throws Exception {
        JsonNode root = JSON.readTree(value);
        if (!exactObject(root, Set.of("target", "value"))) return null;
        JsonNode target = root.get("target"), level = root.get("value");
        if (!exactObject(target, Set.of("routeId", "nodeId", "instanceKey")) || !level.isNumber()) return null;
        double number = level.doubleValue();
        if (!Double.isFinite(number) || number < 0 || number > 1) return null;
        JsonNode route = target.get("routeId"), node = target.get("nodeId"), instance = target.get("instanceKey");
        if (!route.isTextual() || route.textValue().isBlank() || !node.isTextual() || node.textValue().isBlank()
                || !instance.isTextual()) return null;
        return List.of(route.textValue(), node.textValue(), instance.textValue());
    }

    private static boolean exactObject(JsonNode value, Set<String> expected) {
        if (value == null || !value.isObject()) return false;
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        return expected.equals(actual);
    }
}
