package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit, additive installation; never replaces a user's workspace or active selection. */
public final class OctineProfileConfig {
    private OctineProfileConfig() {}

    public static void install(Map<String, Object> config) {
        List<Map<String, Object>> profiles = maps(config.get("profiles"));
        List<Map<String, Object>> routes = maps(config.get("route_library"));
        for (String id : List.of("monitor", "headphones", "free")) {
            boolean existing = profiles.stream().anyMatch(profile -> id.equals(profile.get("id")));
            if (existing || routes.stream().anyMatch(route -> ("octine-" + id).equals(route.get("id"))))
                throw new IllegalArgumentException("Octine preset identifier already exists: " + id);
        }
        if (config.containsKey("common_vibro_graph")) throw new IllegalArgumentException("Common Vibro graph already exists");
        for (String id : List.of("monitor", "headphones", "free")) {
            String name = switch (id) { case "monitor" -> "Монитор"; case "headphones" -> "Наушники"; default -> "Свободный"; };
            String routeId = "octine-" + id;
            profiles.add(DefaultConfig.map("id", id, "name", name, "mode", "multi-output", "selected_route_id", routeId,
                    "default_output", "", "active_buses", new ArrayList<>(), "active_routes", new ArrayList<>(),
                    "device_overrides", DefaultConfig.map()));
            Map<String, Object> encoded = new LinkedHashMap<>(GraphCodec.toMap(route(id, name)));
            encoded.put("profile_id", id); encoded.put("group_id", ""); routes.add(encoded);
        }
        config.put("profiles", profiles); config.put("route_library", routes);
        QuickSettingsConfig.migrate(config);
        GraphCommonVibro.save(config, GraphCommonVibro.defaults());
        Map<String, Object> tactile = QuickSettingsConfig.findOrCreateGroup(config, "Закреплённый тактильный тракт");
        QuickSettingsConfig.pinNode(config, GraphCommonVibro.COMMON_ROUTE_ID, "octine-event-source",
                "sources", 0, "DEFAULT");
        int order = 0;
        for (String node : List.of(GraphCommonVibro.VIBRO_MIXER_ID, GraphCommonVibro.VIBRO_GAIN_ID,
                "octine-vibro-limiter", GraphCommonVibro.VIBRO_OUTPUT_ID))
            QuickSettingsConfig.pinNode(config, GraphCommonVibro.COMMON_ROUTE_ID, node,
                    String.valueOf(tactile.get("id")), order++, "DEFAULT");
        QuickSettingsConfig.pinNode(config, "octine-free", "octine-free-switch", "routing", 0, "DEFAULT");
        QuickSettingsConfig.pinNode(config, "octine-monitor", "octine-monitor-output", "outputs", 0, "DEFAULT");
        QuickSettingsConfig.pinNode(config, "octine-headphones", "octine-headphones-output", "outputs", 1, "DEFAULT");
        int slotOrder = 2;
        for (String slot : List.of("octine-free-monitor", "octine-free-headphones", "octine-free-new-device"))
            QuickSettingsConfig.pinNode(config, "octine-free", slot, "outputs", slotOrder++, "DEFAULT");
        config.put("octine_profiles_available", true);
    }

    private static AudioGraph route(String id, String name) {
        List<GraphNode> nodes = new ArrayList<>(List.of(GraphCommonVibro.mainSource(), GraphCommonVibro.mainMixer()));
        List<GraphConnection> edges = new ArrayList<>(List.of(GraphCommonVibro.edge("main-input",
                GraphCommonVibro.MAIN_SOURCE_ID, "out", GraphCommonVibro.MAIN_MIXER_ID, "applications")));
        if (id.equals("free")) {
            nodes.add(new SwitchNode("octine-free-switch", "Switch · Свободный", GraphCommonVibro.CHANNELS,
                    List.of("monitor", "headphones", "new-device"), List.of()));
            edges.add(GraphCommonVibro.edge("free-input", GraphCommonVibro.MAIN_MIXER_ID, "out", "octine-free-switch", "in"));
            for (String output : List.of("monitor", "headphones", "new-device")) {
                String slot = "octine-free-" + output;
                String label = switch (output) {
                    case "monitor" -> "Монитор HDMI";
                    case "headphones" -> "Наушники";
                    default -> "Новое устройство";
                };
                nodes.add(new DeviceSlotNode(slot, label, "", GraphCommonVibro.CHANNELS));
                edges.add(GraphCommonVibro.edge("free-" + output, "octine-free-switch", output, slot, "in"));
            }
        } else {
            String slot = "octine-" + id + "-output";
            nodes.add(new DeviceSlotNode(slot, name + " · назначьте устройство", "", GraphCommonVibro.CHANNELS));
            edges.add(GraphCommonVibro.edge(id + "-output", GraphCommonVibro.MAIN_MIXER_ID, "out", slot, "in"));
        }
        return new AudioGraph("octine-" + id, name, nodes, edges);
    }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object raw) {
        return raw instanceof List<?> list ? new ArrayList<>((List<Map<String, Object>>) list) : new ArrayList<>();
    }
}
