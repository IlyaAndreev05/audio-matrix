package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceDiscovery;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphProfileLibrary;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit migration/setup commands; no command guesses a device or activates a profile silently. */
public final class OctineSetupCli {
    private OctineSetupCli() {}

    public static int installProfiles(Path path) throws Exception {
        try (ConfigStore store = new ConfigStore(path)) {
            Map<String, Object> config = store.snapshot();
            GraphProfileLibrary.installOctineProfiles(config);
            store.save(config);
            System.out.println("Installed Monitor, Headphones and Free profiles plus one shared Vibro graph in " + store.path());
            return 0;
        }
    }

    public static int bindDevice(Path path, String slotId, String selector) throws Exception {
        try (ConfigStore store = new ConfigStore(path); PipeWireBackend backend = new PipeWireBackend()) {
            Map<String, Object> config = store.snapshot();
            AudioNode node = requireSink(backend, selector);
            DeviceDiscovery.bind(config, slotId, node);
            if (GraphCommonVibro.VIBRO_OUTPUT_ID.equals(slotId)) setRole(config, slotId, "haptic_output");
            assignSlot(config, slotId, slotId);
            store.save(config);
            System.out.println("Bound " + slotId + " -> " + node.name() + " (" + node.identity().fingerprint() + ")");
            return 0;
        }
    }

    /** Explicit cutover for the defective legacy desk route; the caller must keep a config backup. */
    public static int replaceProfiles(Path path) throws Exception {
        try (ConfigStore store = new ConfigStore(path)) {
            Map<String, Object> config = store.snapshot();
            if (!Boolean.TRUE.equals(config.get("octine_profiles_available"))) GraphProfileLibrary.installOctineProfiles(config);
            List<String> profileIds = List.of("monitor", "headphones", "free");
            List<String> routeIds = List.of("octine-monitor", "octine-headphones", "octine-free");
            config.put("profiles", maps(config.get("profiles")).stream().filter(item -> profileIds.contains(item.get("id"))).toList());
            config.put("route_library", maps(config.get("route_library")).stream().filter(item -> routeIds.contains(item.get("id"))).toList());
            config.put("route_groups", maps(config.get("route_groups")).stream().filter(item -> profileIds.contains(item.get("profile_id"))).toList());
            config.put("quick_controls", maps(config.get("quick_controls")).stream().filter(item -> routeIds.contains(item.get("route_id"))).toList());
            Object quick = config.get("quick_settings");
            if (quick instanceof Map<?, ?> raw) {
                Map<String, Object> settings = object(raw);
                settings.put("pins", maps(settings.get("pins")).stream().filter(item -> routeIds.contains(item.get("route_id"))).toList());
                config.put("quick_settings", settings);
            }
            for (String key : List.of("graph_ui_layout", "graph_ui_matrix_views", "graph_ui_subgraphs"))
                if (config.get(key) instanceof Map<?, ?> raw) {
                    Map<String, Object> values = object(raw); values.keySet().removeIf(route -> !routeIds.contains(route)); config.put(key, values);
                }
            config.put("active_profile", "monitor"); config.put("active_route", "octine-monitor");
            store.save(config);
            System.out.println("Replaced legacy profiles with Monitor, Headphones and Free in " + store.path());
            return 0;
        }
    }

    public static int upgradeDevice(Path path, String deviceId, String selector, String role) throws Exception {
        try (ConfigStore store = new ConfigStore(path); PipeWireBackend backend = new PipeWireBackend()) {
            Map<String, Object> config = store.snapshot();
            AudioNode node = requireSink(backend, selector);
            DeviceDiscovery.bind(config, deviceId, node);
            setRole(config, deviceId, role);
            store.save(config);
            System.out.println("Upgraded " + deviceId + " -> " + node.name() + " as " + role);
            return 0;
        }
    }

    public static int assignSlot(Path path, String slotId, String deviceId) throws Exception {
        try (ConfigStore store = new ConfigStore(path)) {
            Map<String, Object> config = store.snapshot();
            boolean exists = maps(config.get("devices")).stream()
                    .anyMatch(device -> deviceId.equals(device.get("id")) && "device_slot".equals(device.get("kind")));
            if (!exists) throw new IllegalArgumentException("Bound device slot does not exist: " + deviceId);
            assignSlot(config, slotId, deviceId);
            store.save(config);
            System.out.println("Assigned graph slot " + slotId + " -> " + deviceId);
            return 0;
        }
    }

    public static int activateProfile(Path path, String profileId) throws Exception {
        try (ConfigStore store = new ConfigStore(path)) {
            Map<String, Object> config = store.snapshot();
            List<Map<String, Object>> profiles = maps(config.get("profiles"));
            Map<String, Object> profile = profiles.stream().filter(item -> profileId.equals(item.get("id"))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown profile: " + profileId));
            String route = String.valueOf(profile.getOrDefault("selected_route_id", ""));
            boolean routeExists = maps(config.get("route_library")).stream().anyMatch(item -> route.equals(item.get("id")));
            if (!route.isBlank() && !routeExists) throw new IllegalArgumentException("Profile route is missing: " + route);
            config.put("active_profile", profileId);
            if (!route.isBlank()) config.put("active_route", route);
            store.save(config);
            System.out.println("Activated profile " + profileId + (route.isBlank() ? "" : " with " + route));
            return 0;
        }
    }

    private static AudioNode requireSink(PipeWireBackend backend, String selector) throws Exception {
        List<AudioNode> matches = backend.refresh().get(15, java.util.concurrent.TimeUnit.SECONDS).sinks().stream()
                .filter(node -> node.name().equals(selector) || node.description().equals(selector)).toList();
        if (matches.size() != 1)
            throw new IllegalArgumentException(matches.isEmpty()
                    ? "No current output matches exactly: " + selector
                    : "Ambiguous output selector; use the exact PipeWire node name: " + selector);
        AudioNode node = matches.getFirst();
        if (node.identity() == null || !node.identity().reliable())
            throw new IllegalArgumentException("The output does not expose a reliable physical identity: " + node.name());
        return node;
    }

    private static void assignSlot(Map<String, Object> config, String slotId, String deviceId) {
        boolean assigned = assignInGraph(object(config.get("common_vibro_graph")), slotId, deviceId);
        for (Map<String, Object> route : maps(config.get("route_library")))
            assigned |= assignInGraph(route, slotId, deviceId);
        if (!assigned) throw new IllegalArgumentException("No graph contains device slot: " + slotId);
    }

    private static boolean assignInGraph(Map<String, Object> graph, String slotId, String deviceId) {
        for (Map<String, Object> node : maps(graph.get("nodes")))
            if (slotId.equals(node.get("id")) && "device_slot".equals(node.get("type"))) {
                Map<String, Object> parameters = object(node.get("parameters"));
                parameters.put("device_id", deviceId);
                node.put("parameters", parameters);
                return true;
            }
        return false;
    }

    private static void setRole(Map<String, Object> config, String id, String role) {
        for (Map<String, Object> device : maps(config.get("devices")))
            if (id.equals(device.get("id"))) device.put("role", role);
    }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? new ArrayList<>((List<Map<String, Object>>) list) : new ArrayList<>();
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }
}
