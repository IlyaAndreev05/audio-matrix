package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphPipeWireCompiler;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import io.github.ilyaandreev05.audiomatrix.graph.SwitchNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedGraphConfigTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test void freshConfigHasOneMonitorProfileAndSeparatesMusicFromEventOutput() {
        Map<String, Object> config = DefaultConfig.create();
        assertEquals(1, maps(config.get("profiles")).size());
        assertEquals("Основной", maps(config.get("profiles")).getFirst().get("name"));
        assertEquals("main", config.get("active_route"));
        assertFalse(config.containsKey("common_vibro_graph"));
        AudioGraph graph = activeGraph(config);
        assertTrue(graph.nodes().stream().noneMatch(node -> node instanceof FilterNode filter
                && "low_pass".equals(filter.processor())));
        SwitchNode route = graph.nodes().stream().filter(SwitchNode.class::isInstance)
                .map(SwitchNode.class::cast).findFirst().orElseThrow();
        assertEquals(List.of("in->monitor"), route.activeLinks());

        AudioGraph bound = UnifiedGraphFactory.create("monitor-test", "vibro-test",
                1.0, false, 1.0, false, 1.0, false, true);
        Map<String, Object> compiled = GraphPipeWireCompiler.compile(bound, config);
        List<Map<String, Object>> routes = maps(compiled.get("routes"));
        Map<String, Object> normalToMonitor = routes.stream().filter(item -> "octine-main-source".equals(item.get("from"))
                && "monitor-test".equals(item.get("to"))).findFirst().orElseThrow();
        Map<String, Object> normalToVibro = routes.stream().filter(item -> "octine-main-source".equals(item.get("from"))
                && "vibro-test".equals(item.get("to"))).findFirst().orElseThrow();
        Map<String, Object> eventToVibro = routes.stream().filter(item -> "octine-event-source".equals(item.get("from"))
                && "vibro-test".equals(item.get("to"))).findFirst().orElseThrow();
        assertEquals(false, normalToMonitor.get("mute"));
        assertEquals(true, normalToVibro.get("mute"));
        assertEquals(false, eventToVibro.get("mute"));
        assertEquals(1.0, normalToMonitor.get("volume"));
        assertEquals(1.0, eventToVibro.get("volume"));
    }

    @Test void schemaNineMigrationKeepsMonitorBindingVibroLevelsMuteAndLegacyArchive() {
        Map<String, Object> config = oldSchemaNineConfig();
        ConfigMigration.upgradeConfig(config);

        assertEquals(10, config.get("schema_version"));
        assertEquals("main", config.get("active_profile"));
        assertEquals("main", config.get("active_route"));
        assertEquals(1, maps(config.get("profiles")).size());
        AudioGraph migrated = activeGraph(config);
        DeviceSlotNode monitor = slot(migrated, UnifiedGraphConfig.MONITOR_OUTPUT_ID);
        DeviceSlotNode vibro = slot(migrated, GraphCommonVibro.VIBRO_OUTPUT_ID);
        assertEquals("monitor-device", monitor.deviceId());
        assertEquals("vibro-device", vibro.deviceId());
        assertTrue(migrated.nodes().stream().noneMatch(node -> "octine-bass-low-pass".equals(node.id())));
        assertEquals(0.064, gain(migrated, UnifiedGraphConfig.MUSIC_GAIN_ID).gain(), 1e-9);
        assertEquals(0.125, gain(migrated, UnifiedGraphConfig.EVENT_GAIN_ID).gain(), 1e-9);
        assertEquals(0.512, gain(migrated, GraphCommonVibro.VIBRO_GAIN_ID).gain(), 1e-9);
        assertTrue(gain(migrated, UnifiedGraphConfig.EVENT_GAIN_ID).muted());
        assertTrue(gain(migrated, GraphCommonVibro.VIBRO_GAIN_ID).muted());
        SwitchNode route = migrated.nodes().stream().filter(SwitchNode.class::isInstance)
                .map(SwitchNode.class::cast).findFirst().orElseThrow();
        assertEquals(List.of("in->monitor"), route.activeLinks());

        Map<String, Object> archive = object(config.get("migration_archive"));
        assertEquals(9, archive.get("source_schema_version"));
        Map<String, Object> before = object(archive.get("prior_config"));
        assertEquals(2, maps(before.get("profiles")).size());
        assertTrue(before.containsKey("common_vibro_graph"));
        assertEquals(List.of(), ConfigValidator.validate(config));
    }

    @Test void runtimeGraphGainsStayLinearAndMusicSwitchDoesNotChangeEventLevel() {
        AudioGraph graph = UnifiedGraphFactory.create("monitor-test", "vibro-test",
                0.5, false, 0.8, false, 1.0, false, true);
        graph = withBothMusicRoutes(graph);
        Map<String, Object> config = DefaultConfig.create();
        Map<String, Object> compiled = GraphPipeWireCompiler.compile(graph, config);
        List<Map<String, Object>> routes = maps(compiled.get("routes"));
        Map<String, Object> music = routes.stream().filter(item -> "octine-main-source".equals(item.get("from"))
                && "vibro-test".equals(item.get("to"))).findFirst().orElseThrow();
        assertEquals(0.5, music.get("volume"));
        Map<String, Object> event = routes.stream().filter(item -> "octine-event-source".equals(item.get("from")))
                .findFirst().orElseThrow();
        assertEquals(0.8, event.get("volume"));
        assertEquals(false, event.get("mute"));
    }

    private static Map<String, Object> oldSchemaNineConfig() {
        AudioGraph monitor = new AudioGraph("octine-monitor", "Монитор",
                List.of(GraphCommonVibro.mainSource(), GraphCommonVibro.mainMixer(),
                        new DeviceSlotNode(UnifiedGraphConfig.MONITOR_OUTPUT_ID, "Monitor", "monitor-device", STEREO)),
                List.of(GraphCommonVibro.edge("old-main", GraphCommonVibro.MAIN_SOURCE_ID, "out",
                                GraphCommonVibro.MAIN_MIXER_ID, "applications"),
                        GraphCommonVibro.edge("old-monitor", GraphCommonVibro.MAIN_MIXER_ID, "out",
                                UnifiedGraphConfig.MONITOR_OUTPUT_ID, "in")));
        AudioGraph headphones = new AudioGraph("octine-headphones", "Наушники",
                List.of(GraphCommonVibro.mainSource(), GraphCommonVibro.mainMixer(),
                        new DeviceSlotNode("headphones-output", "Наушники", "headphones-device", STEREO)),
                List.of(GraphCommonVibro.edge("old-headphones-main", GraphCommonVibro.MAIN_SOURCE_ID, "out",
                                GraphCommonVibro.MAIN_MIXER_ID, "applications"),
                        GraphCommonVibro.edge("old-headphones", GraphCommonVibro.MAIN_MIXER_ID, "out",
                                "headphones-output", "in")));
        AudioGraph shared = GraphCommonVibro.defaults();
        List<GraphNode> nodes = new ArrayList<>();
        for (GraphNode node : shared.nodes()) {
            if (node instanceof MixerNode mixer && node.id().equals(GraphCommonVibro.VIBRO_MIXER_ID))
                nodes.add(new MixerNode(mixer.id(), mixer.name(), STEREO, Map.of("bass", 0.4, "events", 0.5),
                        Map.of("bass", false, "events", true)));
            else if (node instanceof GainNode gain && node.id().equals(GraphCommonVibro.VIBRO_GAIN_ID))
                nodes.add(new GainNode(gain.id(), gain.name(), STEREO, 0.8, true));
            else if (node instanceof DeviceSlotNode slot && slot.id().equals(GraphCommonVibro.VIBRO_OUTPUT_ID))
                nodes.add(new DeviceSlotNode(slot.id(), slot.name(), "vibro-device", STEREO));
            else nodes.add(node);
        }
        shared = new AudioGraph(shared.id(), shared.name(), nodes, shared.connections());
        List<Map<String, Object>> profiles = List.of(
                DefaultConfig.map("id", "monitor", "name", "Монитор", "mode", "multi-output",
                        "default_output", "monitor-device", "selected_route_id", "octine-monitor"),
                DefaultConfig.map("id", "headphones", "name", "Наушники", "mode", "multi-output",
                        "default_output", "headphones-device", "selected_route_id", "octine-headphones"));
        List<Map<String, Object>> routes = new ArrayList<>();
        routes.add(encoded(monitor, "monitor"));
        routes.add(encoded(headphones, "headphones"));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("app", "audio-matrix");
        config.put("schema_version", 9);
        config.put("active_profile", "headphones");
        config.put("active_route", "octine-headphones");
        config.put("profiles", new ArrayList<>(profiles));
        config.put("route_library", routes);
        config.put("route_groups", new ArrayList<>());
        config.put("common_vibro_graph", GraphCodec.toMap(shared));
        config.put("quick_settings", QuickSettingsConfig.defaults());
        config.put("quick_controls", new ArrayList<>());
        config.put("devices", new ArrayList<>(List.of(
                DefaultConfig.map("id", "monitor-device", "name", "Monitor", "selector",
                        DefaultConfig.map("kind", "name_equals", "value", "Monitor"), "volume", 1.0, "mute", false),
                DefaultConfig.map("id", "vibro-device", "name", "Vibro", "selector",
                        DefaultConfig.map("kind", "name_equals", "value", "Vibro"), "volume", 1.0, "mute", false))));
        config.put("applications", new ArrayList<>());
        config.put("buses", new ArrayList<>());
        config.put("routes", new ArrayList<>());
        config.put("mixer", DefaultConfig.map("global_volume", 1.0, "global_mute", false,
                "global_eq", DefaultConfig.map("enabled", false, "filters", new ArrayList<>())));
        config.put("dsp", DefaultConfig.map("order", List.of("stream", "bus", "route", "physical_output", "limiter"),
                "route_chains", DefaultConfig.map(), "output_limiters", DefaultConfig.map()));
        config.put("engine", DefaultConfig.map("backend", "pipewire", "apply_on_start", false));
        config.put("ui", DefaultConfig.map("language", "ru", "theme", "system"));
        config.put("calibrations", new ArrayList<>());
        config.put("saved_copies", new ArrayList<>());
        config.put("graph_ui_layout", new LinkedHashMap<>());
        config.put("graph_ui_scale", new LinkedHashMap<>());
        config.put("graph_ui_matrix_views", new LinkedHashMap<>());
        config.put("graph_ui_subgraphs", new LinkedHashMap<>());
        config.put("octine_profiles_available", true);
        config.put("export", DefaultConfig.map("include_measurements", true, "include_audio_assets", false));
        return config;
    }

    private static Map<String, Object> encoded(AudioGraph graph, String profile) {
        Map<String, Object> result = new LinkedHashMap<>(GraphCodec.toMap(graph));
        result.put("profile_id", profile);
        result.put("group_id", "");
        return result;
    }
    private static AudioGraph withBothMusicRoutes(AudioGraph graph) {
        List<GraphNode> nodes = new ArrayList<>(graph.nodes());
        SwitchNode current = nodes.stream().filter(SwitchNode.class::isInstance).map(SwitchNode.class::cast).findFirst().orElseThrow();
        nodes.set(nodes.indexOf(current), new SwitchNode(current.id(), current.name(), STEREO,
                current.outputs().stream().map(port -> port.id()).toList(), List.of("in->monitor", "in->vibro")));
        return new AudioGraph(graph.id(), graph.name(), nodes, graph.connections());
    }
    private static AudioGraph activeGraph(Map<String, Object> config) {
        return GraphCodec.fromMap(maps(config.get("route_library")).getFirst());
    }
    private static DeviceSlotNode slot(AudioGraph graph, String id) {
        return graph.nodes().stream().filter(node -> node instanceof DeviceSlotNode && id.equals(node.id()))
                .map(DeviceSlotNode.class::cast).findFirst().orElseThrow();
    }
    private static GainNode gain(AudioGraph graph, String id) {
        return graph.nodes().stream().filter(node -> node instanceof GainNode && id.equals(node.id()))
                .map(GainNode.class::cast).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object raw) {
        return raw instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
    private static Map<String, Object> object(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
