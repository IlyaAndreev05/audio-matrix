package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OctineProfileConfigTest {
    @Test void freshConfigurationHasOneUnifiedProfileAndNoBassBranch() {
        Map<String, Object> config = DefaultConfig.create();
        assertEquals(List.of("main"), maps(config.get("profiles")).stream()
                .map(profile -> String.valueOf(profile.get("id"))).toList());
        assertEquals("main", config.get("active_route"));
        assertFalse(config.containsKey("common_vibro_graph"));
        AudioGraph graph = graph(config);
        assertTrue(graph.nodes().stream().noneMatch(node -> "low_pass".equals(
                node instanceof FilterNode filter ? filter.processor() : "")));
        SwitchNode multiSwitch = graph.nodes().stream().filter(SwitchNode.class::isInstance)
                .map(SwitchNode.class::cast).findFirst().orElseThrow();
        assertTrue(multiSwitch.multiple());
        assertEquals(List.of("in->monitor"), multiSwitch.activeLinks());
        assertTrue(graph.nodes().stream().anyMatch(node -> node instanceof DynamicGeneratorNode
                && "application:unassigned".equals(((SourceNode) node).selector())));
        assertTrue(graph.nodes().stream().anyMatch(node -> UnifiedGraphConfig.EVENT_GAIN_ID.equals(node.id())));
        assertEquals(List.of(), ConfigValidator.validate(config));
    }

    @Test void musicSwitchAndEventPathAreIndependentAtTheVibroOutput() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph graph = withDevices(graph(config), "monitor-device", "vibro-device");
        Map<String, Object> compiled = GraphPipeWireCompiler.compile(graph, config);
        List<Map<String, Object>> routes = maps(compiled.get("routes"));
        Map<String, Object> music = routes.stream().filter(route ->
                GraphCommonVibro.MAIN_SOURCE_ID.equals(route.get("from"))
                        && "vibro-device".equals(route.get("to"))).findFirst().orElseThrow();
        Map<String, Object> event = routes.stream().filter(route ->
                UnifiedGraphConfig.EVENT_SOURCE_ID.equals(route.get("from"))).findFirst().orElseThrow();
        assertEquals(0.0, music.get("volume"));
        assertTrue(Boolean.TRUE.equals(music.get("mute")));
        assertEquals("vibro-device", event.get("to"));
        assertEquals(0.39, event.get("volume"));
        assertFalse(event.containsKey("filters_ref"));
        assertTrue(maps(compiled.get("applications")).stream().anyMatch(rule ->
                String.valueOf(rule.get("bus")).contains("event-source")));
        assertEquals(List.of(), GraphValidator.validate(graph));
    }

    @Test void migrationArchivesOldProfilesAndKeepsSeparateLevelsAndBindings() {
        Map<String, Object> config = DefaultConfig.create();
        Map<String, Object> oldMonitor = profile("monitor", "octine-monitor", "monitor-device");
        Map<String, Object> oldHeadphones = profile("headphones", "octine-headphones", "headphones-device");
        config.put("profiles", new ArrayList<>(List.of(oldMonitor, oldHeadphones)));
        config.put("active_profile", "headphones");
        config.put("active_route", "octine-headphones");
        config.put("devices", new ArrayList<>(List.of(
                device("monitor-device", "Monitor output"), device("headphones-device", "Headphones output"),
                device("vibro-device", "Vibro output"))));
        config.put("route_library", new ArrayList<>(List.of(
                route("octine-monitor", "monitor", "octine-monitor-output", "monitor-device"),
                route("octine-headphones", "headphones", "octine-monitor-output", "headphones-device"))));

        AudioGraph oldVibro = GraphCommonVibro.defaults();
        List<GraphNode> oldNodes = oldVibro.nodes().stream().map(node -> {
            if (GraphCommonVibro.VIBRO_OUTPUT_ID.equals(node.id()))
                return new DeviceSlotNode(node.id(), node.name(), "vibro-device", node.inputs().getFirst().channels());
            if (GraphCommonVibro.VIBRO_GAIN_ID.equals(node.id()))
                return new GainNode(node.id(), node.name(), node.inputs().getFirst().channels(), 0.5, false);
            if (node instanceof MixerNode mixer && GraphCommonVibro.VIBRO_MIXER_ID.equals(node.id()))
                return new MixerNode(node.id(), node.name(), node.inputs().getFirst().channels(),
                        Map.of("bass", 0.8, "events", 0.4), Map.of("bass", true));
            return node;
        }).toList();
        GraphCommonVibro.save(config, new AudioGraph(oldVibro.id(), oldVibro.name(), oldNodes, oldVibro.connections()));
        config.put("schema_version", 9);

        ConfigMigration.upgradeConfig(config);

        assertEquals(List.of("main"), maps(config.get("profiles")).stream().map(p -> p.get("id")).toList());
        assertEquals("main", config.get("active_route"));
        Map<String, Object> archive = object(config.get("migration_archive"));
        Map<String, Object> prior = object(archive.get("prior_config"));
        assertEquals(2, maps(prior.get("profiles")).size());
        AudioGraph migrated = graph(config);
        assertEquals("monitor-device", node(migrated, UnifiedGraphConfig.MONITOR_OUTPUT_ID, DeviceSlotNode.class).deviceId());
        assertEquals("vibro-device", node(migrated, GraphCommonVibro.VIBRO_OUTPUT_ID, DeviceSlotNode.class).deviceId());
        assertEquals(0.125, node(migrated, GraphCommonVibro.VIBRO_GAIN_ID, GainNode.class).gain(), 1e-12);
        assertTrue(node(migrated, UnifiedGraphConfig.MUSIC_GAIN_ID, GainNode.class).muted());
        assertEquals(0.512, node(migrated, UnifiedGraphConfig.MUSIC_GAIN_ID, GainNode.class).gain(), 1e-12);
        assertEquals(0.064, node(migrated, UnifiedGraphConfig.EVENT_GAIN_ID, GainNode.class).gain(), 1e-12);
        SwitchNode route = node(migrated, UnifiedGraphConfig.SWITCH_ID, SwitchNode.class);
        assertEquals(List.of("in->monitor"), route.activeLinks(), "music must not auto-enable on the haptic output");
        assertTrue(migrated.nodes().stream().noneMatch(n -> "octine-bass-low-pass".equals(n.id())));
        assertEquals(List.of(), ConfigValidator.validate(config));
    }

    private static Map<String, Object> profile(String id, String route, String output) {
        return DefaultConfig.map("id", id, "name", id, "mode", "multi-output", "selected_route_id", route,
                "default_output", output, "active_buses", List.of(), "active_routes", List.of(), "device_overrides", Map.of());
    }

    private static Map<String, Object> device(String id, String name) {
        return DefaultConfig.map("id", id, "name", name,
                "selector", DefaultConfig.map("kind", "name_equals", "value", name), "volume", 1.0, "mute", false);
    }

    private static Map<String, Object> route(String id, String profile, String slotId, String device) {
        AudioGraph graph = new AudioGraph(id, id, List.of(
                new SourceNode(id + "-source", "Applications", "application:unassigned", List.of("FL", "FR")),
                new DeviceSlotNode(slotId, "Monitor", device, List.of("FL", "FR"))),
                List.of(new GraphConnection(id + "-link", id + "-source", "out", slotId, "in", true, 1.0)));
        Map<String, Object> encoded = new LinkedHashMap<>(GraphCodec.toMap(graph));
        encoded.put("profile_id", profile); encoded.put("group_id", profile);
        return encoded;
    }

    private static AudioGraph withDevices(AudioGraph graph, String monitor, String vibro) {
        List<GraphNode> nodes = graph.nodes().stream().map(node -> {
            if (node instanceof DeviceSlotNode slot && UnifiedGraphConfig.MONITOR_OUTPUT_ID.equals(slot.id()))
                return new DeviceSlotNode(slot.id(), slot.name(), monitor, slot.inputs().getFirst().channels());
            if (node instanceof DeviceSlotNode slot && GraphCommonVibro.VIBRO_OUTPUT_ID.equals(slot.id()))
                return new DeviceSlotNode(slot.id(), slot.name(), vibro, slot.inputs().getFirst().channels());
            return node;
        }).toList();
        return new AudioGraph(graph.id(), graph.name(), nodes, graph.connections());
    }

    private static AudioGraph graph(Map<String, Object> config) {
        return GraphCodec.fromMap(maps(config.get("route_library")).stream()
                .filter(item -> "main".equals(item.get("id"))).findFirst().orElseThrow());
    }

    private static <T> T node(AudioGraph graph, String id, Class<T> type) {
        return type.cast(graph.nodes().stream().filter(candidate -> id.equals(candidate.id())).findFirst().orElseThrow());
    }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
