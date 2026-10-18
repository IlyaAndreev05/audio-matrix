package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Merges node endpoint properties with its physical card and active profile/route. */
final class DeviceProperties {
    private DeviceProperties() {}
    static Map<String, String> read(JsonNode root, JsonNode props) {
        Map<String, String> result = new LinkedHashMap<>();
        int deviceId = props.path("device.id").asInt(-1);
        for (JsonNode object : root) {
            if (deviceId < 0 || object.path("id").asInt(-2) != deviceId) continue;
            JsonNode info = object.path("info");
            copy(info.path("props"), result);
            JsonNode profiles = info.path("params").path("Profile");
            for (JsonNode profile : profiles)
                if (profile.hasNonNull("name") && (profiles.size() == 1 || profile.path("active").asBoolean(false)))
                    result.put("device.profile.name", profile.path("name").asText());
            int profileDevice = props.path("card.profile.device").asInt(-1);
            for (JsonNode route : info.path("params").path("Route")) {
                if (profileDevice >= 0 && route.path("device").asInt(-2) == profileDevice && route.hasNonNull("name")) {
                    result.put("device.port.name", route.path("name").asText());
                    break;
                }
            }
        }
        copy(props, result);
        return result;
    }
    private static void copy(JsonNode props, Map<String, String> result) {
        props.fields().forEachRemaining(entry -> result.put(entry.getKey(),
                entry.getValue().isArray() ? entry.getValue().toString() : entry.getValue().asText("")));
    }
}
