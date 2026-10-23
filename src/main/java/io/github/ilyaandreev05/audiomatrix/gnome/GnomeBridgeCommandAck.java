package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps API v5 commands to the target/property key used by pending client intents. */
final class GnomeBridgeCommandAck {
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {};

    private GnomeBridgeCommandAck() {}

    static Map<String, Object> create(GnomeControlBridge.Command command,
                                      GnomeControlBridge.CommandResult result, ObjectMapper json) {
        String name = command.name();
        String raw = command.value();
        String[] fields = raw.split("\\t", -1);
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("requestId", command.requestId());
        Map<String, Object> target = new LinkedHashMap<>();
        String property = name;
        Object value = null;
        try {
            switch (name) {
                case "setNodeVolume", "setNodeMuted" -> {
                    Map<String, Object> payload = json.readValue(raw, OBJECT);
                    target.putAll(object(payload.get("target")));
                    property = "setNodeVolume".equals(name) ? "volume" : "muted";
                    value = payload.get("setNodeVolume".equals(name) ? "value" : "muted");
                }
                case "setBrowserTabMuted" -> {
                    Map<String, Object> payload = json.readValue(raw, OBJECT);
                    target.putAll(object(payload.get("target")));
                    property = "muted";
                    value = payload.get("muted");
                }
                case "setRouteEnabled" -> {
                    Map<String, Object> payload = json.readValue(raw, OBJECT);
                    for (String field : List.of("routeId", "switchNodeId", "linkId"))
                        if (payload.containsKey(field)) target.put(field, payload.get(field));
                    property = "enabled";
                    value = payload.get("enabled");
                }
                case "select_profile" -> {
                    Map<String, Object> payload = json.readValue(raw, OBJECT);
                    target.put("profileId", payload.get("profileId"));
                    property = "activeProfileId";
                    value = payload.get("profileId");
                }
                case "select_route" -> {
                    target.put("routeId", raw);
                    property = "activeRouteId";
                    value = raw;
                }
                case "set_app_volume", "toggle_app_mute" -> {
                    target.put("applicationId", fields[0]);
                    property = name.endsWith("mute") ? "muted" : "volume";
                    if (fields.length > 1) value = parseValue(fields[1]);
                }
                case "set_stream_volume", "toggle_stream_mute" -> {
                    target.put("streamId", parseValue(fields[0]));
                    property = name.endsWith("mute") ? "muted" : "volume";
                    if (fields.length > 1) value = parseValue(fields[1]);
                }
                case "set_output_volume", "toggle_output_mute" -> {
                    target.put("outputId", fields[0]);
                    property = name.endsWith("mute") ? "muted" : "volume";
                    if (fields.length > 1) value = parseValue(fields[1]);
                }
                case "set_source_volume", "toggle_source_mute" -> {
                    if (fields.length >= 3) {
                        target.put("routeId", fields[0]);
                        target.put("nodeId", fields[2]);
                        target.put("instanceKey", "");
                    }
                    property = name.endsWith("mute") ? "muted" : "volume";
                    if (fields.length > 3) value = parseValue(fields[3]);
                }
                case "set_mixer_input_volume", "toggle_mixer_input_mute" -> {
                    target.put("routeId", fields[0]);
                    target.put("nodeId", fields[1]);
                    target.put("inputId", fields[2]);
                    property = name.endsWith("mute") ? "muted" : "volume";
                    if (fields.length > 3) value = parseValue(fields[3]);
                }
                case "set_quick_control", "toggle_quick_control", "set_quick_control_choice" -> {
                    target.put("quickControlId", fields[0]);
                    property = "set_quick_control_choice".equals(name) ? "choice" : "value";
                    if (fields.length > 1) value = parseValue(fields[1]);
                }
                case "set_quick_group_collapsed" -> {
                    target.put("groupId", fields[0]); property = "collapsed";
                    if (fields.length > 1) value = parseValue(fields[1]);
                }
                case "set_quick_pin_view" -> {
                    target.put("pinId", fields[0]); property = "view";
                    if (fields.length > 1) value = fields[1];
                }
                case "set_quick_matrix_view" -> {
                    target.put("quickControlId", fields[0]); property = "view";
                    if (fields.length > 1) value = fields[1];
                }
                default -> target.put("command", name);
            }
        } catch (Exception malformed) {
            target.clear();
            target.put("command", name);
            property = name;
            value = null;
        }
        ack.put("target", target);
        ack.put("property", property);
        ack.put("status", result.status());
        ack.put("value", "ok".equals(result.status()) ? value : null);
        if (!"ok".equals(result.status())) ack.put("error", result.message());
        return ack;
    }

    private static Object parseValue(String value) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        try { return Double.parseDouble(value); }
        catch (NumberFormatException ignored) { return value; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
