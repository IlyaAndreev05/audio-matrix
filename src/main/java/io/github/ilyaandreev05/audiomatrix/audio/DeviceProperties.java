package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Merges node endpoint properties with its physical card and active profile/route. */
final class DeviceProperties {
    static final String PORT_AVAILABILITY = "device.port.availability";

    private DeviceProperties() {}

    static Map<String, String> read(JsonNode root, JsonNode props) {
        Map<String, String> result = new LinkedHashMap<>();
        int deviceId = props.path("device.id").asInt(-1);
        int profileDevice = props.path("card.profile.device").asInt(-1);
        PortAvailability availability = PortAvailability.UNKNOWN;
        for (JsonNode object : root) {
            if (deviceId < 0 || object.path("id").asInt(-2) != deviceId) continue;
            JsonNode info = object.path("info");
            copy(info.path("props"), result);
            JsonNode profiles = info.path("params").path("Profile");
            for (JsonNode profile : profiles)
                if (profile.hasNonNull("name") && (profiles.size() == 1 || profile.path("active").asBoolean(false)))
                    result.put("device.profile.name", profile.path("name").asText());
            JsonNode route = activeRoute(info.path("params").path("Route"), profileDevice);
            if (route != null) {
                result.put("device.port.name", route.path("name").asText());
                availability = portAvailability(route, info.path("params").path("EnumRoute"), profileDevice);
            }
        }
        copy(props, result);
        result.put(PORT_AVAILABILITY, availability.propertyValue());
        return result;
    }

    private static JsonNode activeRoute(JsonNode routes, int profileDevice) {
        if (profileDevice < 0 || !routes.isArray()) return null;
        java.util.List<JsonNode> candidates = new java.util.ArrayList<>();
        java.util.List<JsonNode> active = new java.util.ArrayList<>();
        for (JsonNode route : routes) {
            if (route.path("device").asInt(-2) != profileDevice || !route.hasNonNull("name")) continue;
            candidates.add(route);
            if (route.path("active").asBoolean(false)) active.add(route);
        }
        if (active.size() == 1) return active.getFirst();
        if (!active.isEmpty()) return null;
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private static PortAvailability portAvailability(JsonNode route, JsonNode enumRoutes, int profileDevice) {
        if (route.hasNonNull("available")) return PortAvailability.parse(route.path("available").asText(""));
        String name = route.path("name").asText("");
        if (name.isBlank() || profileDevice < 0 || !enumRoutes.isArray()) return PortAvailability.UNKNOWN;
        java.util.List<JsonNode> matches = new java.util.ArrayList<>();
        for (JsonNode candidate : enumRoutes)
            if (candidate.path("device").asInt(-2) == profileDevice
                    && name.equals(candidate.path("name").asText(""))) matches.add(candidate);
        if (matches.size() != 1 || !matches.getFirst().hasNonNull("available")) return PortAvailability.UNKNOWN;
        return PortAvailability.parse(matches.getFirst().path("available").asText(""));
    }

    private static void copy(JsonNode props, Map<String, String> result) {
        props.fields().forEachRemaining(entry -> result.put(entry.getKey(),
                entry.getValue().isArray() ? entry.getValue().toString() : entry.getValue().asText("")));
    }
}
