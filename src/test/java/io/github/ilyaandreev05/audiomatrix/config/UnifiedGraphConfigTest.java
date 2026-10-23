package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import io.github.ilyaandreev05.audiomatrix.graph.SwitchNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedGraphConfigTest {
    @Test void freshConfigHasSingleProfileAndGeneratedOutputTopology() {
        Map<String, Object> config = DefaultConfig.create();
        List<Map<String, Object>> profiles = maps(config.get("profiles"));
        assertEquals(1, profiles.size());
        assertEquals("main", profiles.getFirst().get("id"));
        assertEquals("Основной", profiles.getFirst().get("name"));
        assertEquals("", profiles.getFirst().get("default_output"));
        assertEquals("main", config.get("active_route"));
        assertEquals("main", config.get("active_profile"));
        assertFalse(config.containsKey("common_vibro_graph"));

        AudioGraph graph = activeGraph(config);
        assertEquals(List.of(), ConfigValidator.validate(config));
        assertEquals(1, graph.nodes().stream().filter(SwitchNode.class::isInstance).count());
        SwitchNode multiSwitch = graph.nodes().stream().filter(SwitchNode.class::isInstance)
                .map(SwitchNode.class::cast).findFirst().orElseThrow();
        assertEquals(List.of("in"), multiSwitch.inputs().stream().map(port -> port.id()).toList());
        assertEquals(List.of(UnifiedGraphFactory.HAPTIC_MUSIC_PORT_ID),
                multiSwitch.outputs().stream().map(port -> port.id()).toList());
        assertEquals(List.of(), multiSwitch.activeLinks());

        assertEquals(1, graph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).filter(node -> "applications".equals(node.registry())).count());
        DynamicGeneratorNode apps = generator(graph, GraphCommonVibro.MAIN_SOURCE_ID);
        assertEquals("applications", apps.registry());
        assertEquals("application:unassigned", apps.selector());
        assertEquals(Map.of("node_id", GraphCommonVibro.MAIN_MIXER_ID, "port_id", "applications"), apps.connectTo());
        MixerNode mainMerge = mixer(graph, GraphCommonVibro.MAIN_MIXER_ID);
        assertEquals(Set.of("applications", "additional"), mainMerge.inputGains().keySet());
        assertTrue(hasEdge(graph, GraphCommonVibro.MAIN_MIXER_ID, UnifiedGraphConfig.SWITCH_ID));
        assertFalse(hasEdge(graph, apps.id(), GraphCommonVibro.MAIN_MIXER_ID));
        assertFalse(graph.nodes().stream().anyMatch(node -> node instanceof SourceNode source
                && GraphCommonVibro.MAIN_SOURCE_ID.equals(source.id())));

        List<DynamicGeneratorNode> outputGenerators = graph.nodes().stream()
                .filter(DynamicGeneratorNode.class::isInstance).map(DynamicGeneratorNode.class::cast)
                .filter(node -> "outputs".equals(node.registry())).toList();
        assertEquals(2, outputGenerators.size());
        DynamicGeneratorNode normalOutputs = generator(graph, UnifiedGraphConfig.MONITOR_OUTPUT_ID);
        DynamicGeneratorNode hapticOutputs = generator(graph, GraphCommonVibro.VIBRO_OUTPUT_ID);
        assertEquals("device:*", normalOutputs.selector());
        assertEquals(List.of("device:role:haptic_output"), normalOutputs.excludedSelectors());
        assertEquals(Map.of("node_id", UnifiedGraphConfig.SWITCH_ID, "port_id", "in"), normalOutputs.connectTo());
        assertFalse(normalOutputs.autoSelectTrustedHdmiOnFirstDiscovery());
        assertTrue(normalOutputs.instances().isEmpty());
        assertEquals("device:role:haptic_output", hapticOutputs.selector());
        assertEquals(Map.of("node_id", UnifiedGraphConfig.LIMITER_ID, "port_id", "out"), hapticOutputs.connectTo());
        assertFalse(hapticOutputs.autoSelectTrustedHdmiOnFirstDiscovery());
        assertTrue(hapticOutputs.instances().isEmpty());
        assertEquals(0, outputGenerators.stream().filter(DynamicGeneratorNode::autoSelectTrustedHdmiOnFirstDiscovery).count());
        assertTrue(graph.nodes().stream().noneMatch(node -> node instanceof DeviceSlotNode || node instanceof OutputNode));
        assertTrue(maps(config.get("devices")).stream().noneMatch(device -> "haptic_output".equals(device.get("role"))));
        assertFalse(GraphCodec.toMap(graph).toString().contains("system_default"));
        assertEquals(2, graph.nodes().stream().filter(MixerNode.class::isInstance).count());

        SourceNode events = graph.nodes().stream().filter(SourceNode.class::isInstance)
                .map(SourceNode.class::cast).filter(source -> UnifiedGraphConfig.EVENT_SOURCE_ID.equals(source.id()))
                .findFirst().orElseThrow();
        assertEquals("application:io.github.ilyaandreev05.eventsoundstudio", events.selector());
        assertFalse(hasEdge(graph, events.id(), UnifiedGraphConfig.SWITCH_ID));
        MixerNode hapticMerge = mixer(graph, GraphCommonVibro.VIBRO_MIXER_ID);
        assertTrue(hasEdge(graph, UnifiedGraphConfig.EVENT_SOURCE_ID, UnifiedGraphConfig.EVENT_GAIN_ID));
        assertTrue(hasEdge(graph, UnifiedGraphConfig.EVENT_GAIN_ID, GraphCommonVibro.VIBRO_MIXER_ID));
        assertTrue(hasEdge(graph, UnifiedGraphConfig.MUSIC_GAIN_ID, GraphCommonVibro.VIBRO_MIXER_ID));
        assertTrue(hasEdge(graph, GraphCommonVibro.VIBRO_MIXER_ID, GraphCommonVibro.VIBRO_GAIN_ID));
        assertTrue(hasEdge(graph, GraphCommonVibro.VIBRO_GAIN_ID, UnifiedGraphConfig.LIMITER_ID));
        assertEquals(Set.of("music", "events"), hapticMerge.inputGains().keySet());
        assertEquals(0.65, gain(graph, UnifiedGraphConfig.EVENT_GAIN_ID).gain(), 1e-9);
        assertFalse(gain(graph, UnifiedGraphConfig.EVENT_GAIN_ID).muted());
        assertEquals(0.6, gain(graph, UnifiedGraphConfig.MUSIC_GAIN_ID).gain(), 1e-9);
        assertEquals(0.6, gain(graph, GraphCommonVibro.VIBRO_GAIN_ID).gain(), 1e-9);
        FilterNode safetyLimiter = filter(graph, UnifiedGraphConfig.LIMITER_ID);
        assertEquals("limiter", safetyLimiter.processor());
        assertTrue(safetyLimiter.enabled());
        assertEquals(-1.0, number(safetyLimiter.parameters().get("ceiling_db")), 1e-9);
    }

    static AudioGraph activeGraph(Map<String, Object> config) {
        return GraphCodec.fromMap(maps(config.get("route_library")).getFirst());
    }

    static DynamicGeneratorNode generator(AudioGraph graph, String id) {
        return graph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).filter(node -> id.equals(node.id())).findFirst().orElseThrow();
    }

    static MixerNode mixer(AudioGraph graph, String id) {
        return graph.nodes().stream().filter(MixerNode.class::isInstance).map(MixerNode.class::cast)
                .filter(node -> id.equals(node.id())).findFirst().orElseThrow();
    }

    static GainNode gain(AudioGraph graph, String id) {
        return graph.nodes().stream().filter(GainNode.class::isInstance).map(GainNode.class::cast)
                .filter(node -> id.equals(node.id())).findFirst().orElseThrow();
    }

    static FilterNode filter(AudioGraph graph, String id) {
        return graph.nodes().stream().filter(FilterNode.class::isInstance).map(FilterNode.class::cast)
                .filter(node -> id.equals(node.id())).findFirst().orElseThrow();
    }

    static boolean hasEdge(AudioGraph graph, String from, String to) {
        return graph.connections().stream().anyMatch(edge -> edge.fromNode().equals(from) && edge.toNode().equals(to));
    }

    static Map<String, Object> device(Map<String, Object> config, String id) {
        return maps(config.get("devices")).stream().filter(item -> id.equals(item.get("id"))).findFirst().orElseThrow();
    }

    static Map<String, Object> applicationRule(Map<String, Object> config, String applicationId) {
        return maps(config.get("applications")).stream()
                .filter(rule -> applicationId.equals(object(rule.get("match")).get("app_id")))
                .findFirst().orElseThrow();
    }

    static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object raw) {
        return raw instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    static Map<String, Object> object(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}

class UnifiedGraphLegacyMigrationTest {
    @Test void schemaNineMigrationPreservesIdentityLevelsRulesFiltersAndSelectedBranches() {
        Map<String, Object> config = LegacySchemaNineConfigFixture.create();
        DeviceIdentity monitorIdentity = LegacySchemaNineConfigFixture.identity("legacy-monitor", "monitor-port");
        DeviceIdentity headphonesIdentity = LegacySchemaNineConfigFixture.identity("legacy-headphones", "headphone-port");
        DeviceIdentity hapticIdentity = LegacySchemaNineConfigFixture.identity("legacy-haptic", "front-headphone-port");
        ConfigMigration.upgradeConfig(config);

        assertEquals(DefaultConfig.SCHEMA_VERSION, config.get("schema_version"));
        assertEquals("main", config.get("active_profile"));
        assertEquals("main", config.get("active_route"));
        assertEquals(1, UnifiedGraphConfigTest.maps(config.get("profiles")).size());
        assertEquals("main", UnifiedGraphConfigTest.maps(config.get("profiles")).getFirst().get("id"));
        assertEquals("", UnifiedGraphConfigTest.maps(config.get("profiles")).getFirst().get("default_output"));

        AudioGraph migrated = UnifiedGraphConfigTest.activeGraph(config);
        assertEquals(1, migrated.nodes().stream().filter(SwitchNode.class::isInstance).count());
        SwitchNode multiSwitch = migrated.nodes().stream().filter(SwitchNode.class::isInstance)
                .map(SwitchNode.class::cast).findFirst().orElseThrow();
        String headphonesPort = UnifiedGraphFactory.outputPortId(headphonesIdentity.fingerprint());
        assertEquals(Set.of("in->" + headphonesPort, "in->" + UnifiedGraphFactory.HAPTIC_MUSIC_PORT_ID),
                Set.copyOf(multiSwitch.activeLinks()));

        DynamicGeneratorNode outputs = UnifiedGraphConfigTest.generator(migrated, UnifiedGraphConfig.MONITOR_OUTPUT_ID);
        assertFalse(outputs.autoSelectTrustedHdmiOnFirstDiscovery());
        assertTrue(outputs.instances().containsKey("device:" + monitorIdentity.fingerprint()));
        assertTrue(outputs.instances().containsKey("device:" + headphonesIdentity.fingerprint()));
        assertFalse(outputs.instances().containsKey("device:" + hapticIdentity.fingerprint()));
        Map<String, Object> headphoneRecord = UnifiedGraphConfigTest.object(
                outputs.instances().get("device:" + headphonesIdentity.fingerprint()));
        Map<String, Object> childIds = UnifiedGraphConfigTest.object(headphoneRecord.get("node_ids"));
        assertEquals(DynamicInstanceRegistry.stableNodeId(UnifiedGraphConfig.MONITOR_OUTPUT_ID,
                "device:" + headphonesIdentity.fingerprint(), "gain"), childIds.get("gain"));
        assertEquals(headphonesPort, multiSwitch.outputs().stream().map(port -> port.id())
                .filter(headphonesPort::equals).findFirst().orElseThrow());

        DynamicGeneratorNode hapticOutputs =
                UnifiedGraphConfigTest.generator(migrated, GraphCommonVibro.VIBRO_OUTPUT_ID);
        assertEquals("device:role:haptic_output", hapticOutputs.selector());
        assertFalse(hapticOutputs.autoSelectTrustedHdmiOnFirstDiscovery());
        assertTrue(hapticOutputs.instances().isEmpty());
        assertTrue(migrated.nodes().stream().noneMatch(node -> node instanceof DeviceSlotNode || node instanceof OutputNode));
        assertEquals(monitorIdentity.toMap(), UnifiedGraphConfigTest.device(config, "monitor-device").get("identity"));
        assertEquals(headphonesIdentity.toMap(), UnifiedGraphConfigTest.device(config, "headphones-device").get("identity"));
        assertEquals(hapticIdentity.toMap(), UnifiedGraphConfigTest.device(config, "vibro-device").get("identity"));
        assertEquals(0.512, UnifiedGraphConfigTest.number(
                UnifiedGraphConfigTest.device(config, "monitor-device").get("volume")), 1e-9);
        assertEquals(0.343, UnifiedGraphConfigTest.number(
                UnifiedGraphConfigTest.device(config, "headphones-device").get("volume")), 1e-9);
        assertEquals(false, UnifiedGraphConfigTest.device(config, "headphones-device").get("mute"));
        assertEquals(0.216, UnifiedGraphConfigTest.number(
                UnifiedGraphConfigTest.device(config, "vibro-device").get("volume")), 1e-9);
        assertEquals(true, UnifiedGraphConfigTest.device(config, "vibro-device").get("mute"));

        Map<String, Object> playerRule = UnifiedGraphConfigTest.applicationRule(config, "org.example.player");
        assertEquals("saved-player-rule", playerRule.get("id"));
        assertEquals("legacy-music", playerRule.get("bus"));
        assertEquals(0.343, UnifiedGraphConfigTest.number(playerRule.get("volume")), 1e-9);
        assertEquals(true, playerRule.get("mute"));
        assertEquals("dsp.route_chains.legacy-app-chain", playerRule.get("filters_ref"));
        Map<String, Object> eventsRule =
                UnifiedGraphConfigTest.applicationRule(config, "io.github.ilyaandreev05.eventsoundstudio");
        assertEquals("saved-events-rule", eventsRule.get("id"));
        assertEquals(UnifiedGraphConfig.EVENT_SOURCE_ID, eventsRule.get("bus"));
        assertEquals(0.512, UnifiedGraphConfigTest.number(eventsRule.get("volume")), 1e-9);
        assertEquals(false, eventsRule.get("mute"));
        assertEquals("dsp.route_chains.legacy-app-chain", eventsRule.get("filters_ref"));
        assertTrue(UnifiedGraphConfigTest.maps(config.get("buses")).stream()
                .anyMatch(bus -> "legacy-music".equals(bus.get("id"))));
        assertFalse(((List<?>) UnifiedGraphConfigTest.maps(config.get("profiles")).getFirst()
                .get("active_buses")).contains("legacy-music"));

        assertEquals(0.064, UnifiedGraphConfigTest.gain(migrated, UnifiedGraphConfig.MUSIC_GAIN_ID).gain(), 1e-9);
        assertEquals(0.125, UnifiedGraphConfigTest.gain(migrated, UnifiedGraphConfig.EVENT_GAIN_ID).gain(), 1e-9);
        assertEquals(0.512, UnifiedGraphConfigTest.gain(migrated, GraphCommonVibro.VIBRO_GAIN_ID).gain(), 1e-9);
        assertTrue(UnifiedGraphConfigTest.gain(migrated, UnifiedGraphConfig.EVENT_GAIN_ID).muted());
        assertTrue(UnifiedGraphConfigTest.gain(migrated, GraphCommonVibro.VIBRO_GAIN_ID).muted());
        MixerNode mainMerge = UnifiedGraphConfigTest.mixer(migrated, GraphCommonVibro.MAIN_MIXER_ID);
        assertEquals(0.512, mainMerge.inputGains().get("applications"), 1e-9);
        assertTrue(mainMerge.inputMuted("applications"));
        assertEquals(0.343, mainMerge.inputGains().get("additional"), 1e-9);

        assertTrue(migrated.nodes().stream().noneMatch(node -> "octine-bass-low-pass".equals(node.id())));
        FilterNode userMainFilter = UnifiedGraphConfigTest.filter(migrated, "legacy-main-eq");
        assertEquals("eq", userMainFilter.processor());
        assertEquals(false, userMainFilter.enabled());
        FilterNode userHapticFilter = UnifiedGraphConfigTest.filter(migrated, "legacy-haptic-eq");
        assertEquals("eq", userHapticFilter.processor());
        assertEquals(List.of(Map.of("type", "peaking", "frequency_hz", 140.0,
                "gain_db", -2.0, "q", 1.0)), userHapticFilter.parameters().get("bands"));
        FilterNode sharedLimiter = UnifiedGraphConfigTest.filter(migrated, UnifiedGraphConfig.LIMITER_ID);
        assertEquals("Защита пиков вибродинамика", sharedLimiter.name());
        assertEquals(-1.0, UnifiedGraphConfigTest.number(sharedLimiter.parameters().get("ceiling_db")), 1e-9);

        Map<String, Object> archive = UnifiedGraphConfigTest.object(config.get("migration_archive"));
        assertEquals(9, archive.get("source_schema_version"));
        Map<String, Object> before = UnifiedGraphConfigTest.object(archive.get("prior_config"));
        assertEquals(2, UnifiedGraphConfigTest.maps(before.get("profiles")).size());
        assertTrue(before.containsKey("common_vibro_graph"));
        assertEquals(Set.of("octine-monitor", "octine-headphones"),
                UnifiedGraphConfigTest.maps(before.get("route_library")).stream().map(route -> route.get("id"))
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of(), ConfigValidator.validate(config));
    }
}

final class LegacySchemaNineConfigFixture {
    private static final List<String> STEREO = List.of("FL", "FR");
    private LegacySchemaNineConfigFixture() {}

    static Map<String, Object> create() {
        DeviceIdentity monitor = identity("legacy-monitor", "monitor-port");
        DeviceIdentity headphones = identity("legacy-headphones", "headphone-port");
        DeviceIdentity haptic = identity("legacy-haptic", "front-headphone-port");
        AudioGraph monitorRoute = legacyRoute("octine-monitor", "Монитор", "in->monitor",
                new FilterNode("legacy-main-eq", "User Main EQ", STEREO, "eq", false,
                        Map.of("bands", List.of(Map.of("type", "peaking", "frequency_hz", 100.0,
                                "gain_db", -1.0, "q", 0.8)))));
        AudioGraph headphonesRoute = legacyRoute("octine-headphones", "Наушники", "in->headphones",
                new FilterNode("legacy-main-eq", "User Main EQ", STEREO, "eq", false,
                        Map.of("bands", List.of(Map.of("type", "peaking", "frequency_hz", 100.0,
                                "gain_db", -1.0, "q", 0.8)))));
        AudioGraph shared = legacySharedGraph();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("app", "audio-matrix");
        config.put("schema_version", 9);
        config.put("active_profile", "headphones");
        config.put("active_route", "octine-headphones");
        config.put("profiles", new ArrayList<>(List.of(
                DefaultConfig.map("id", "monitor", "name", "Монитор", "mode", "multi-output",
                        "default_output", "monitor-device", "selected_route_id", "octine-monitor"),
                DefaultConfig.map("id", "headphones", "name", "Наушники", "mode", "multi-output",
                        "default_output", "headphones-device", "selected_route_id", "octine-headphones"))));
        config.put("route_library", new ArrayList<>(List.of(encoded(monitorRoute, "monitor"),
                encoded(headphonesRoute, "headphones"))));
        config.put("route_groups", new ArrayList<>());
        config.put("common_vibro_graph", GraphCodec.toMap(shared));
        config.put("quick_settings", QuickSettingsConfig.defaults());
        config.put("quick_controls", new ArrayList<>());
        config.put("devices", new ArrayList<>(List.of(
                device("monitor-device", "Monitor", "output", monitor, 0.8, false),
                device("headphones-device", "Headphones", "output", headphones, 0.7, false),
                device("vibro-device", "Haptic", "haptic_output", haptic, 0.6, true))));
        config.put("applications", new ArrayList<>(List.of(
                DefaultConfig.map("id", "saved-player-rule", "name", "Player",
                        "match", Map.of("app_id", "org.example.player"), "bus", "legacy-music",
                        "volume", 0.7, "mute", true, "filters_ref", "dsp.route_chains.legacy-app-chain"),
                DefaultConfig.map("id", "saved-events-rule", "name", "ESS",
                        "match", Map.of("app_id", "io.github.ilyaandreev05.eventsoundstudio"), "bus", "legacy-effects",
                        "volume", 0.8, "mute", false, "filters_ref", "dsp.route_chains.legacy-app-chain"))));
        config.put("buses", new ArrayList<>(List.of(
                DefaultConfig.map("id", "legacy-music", "name", "Legacy Music", "role", "music",
                        "channels", "stereo", "volume", 0.6, "mute", false),
                DefaultConfig.map("id", "legacy-effects", "name", "Legacy Effects", "role", "effects",
                        "channels", "stereo", "volume", 0.4, "mute", false))));
        config.put("routes", new ArrayList<>());
        config.put("mixer", DefaultConfig.map("global_volume", 1.0, "global_mute", false,
                "global_eq", DefaultConfig.map("enabled", false, "filters", new ArrayList<>())));
        config.put("dsp", DefaultConfig.map("order", List.of("stream", "bus", "route", "physical_output", "limiter"),
                "route_chains", DefaultConfig.map("legacy-app-chain", DefaultConfig.map("enabled", true,
                        "filters", new ArrayList<>(List.of(DefaultConfig.map("type", "low_pass",
                                "frequency_hz", 900.0, "slope_db_oct", 12.0))))),
                "output_limiters", DefaultConfig.map()));
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

    static DeviceIdentity identity(String serial, String port) {
        return DeviceIdentity.from(Map.of("device.serial", serial, "device.profile.name", "analog-stereo",
                "device.port.name", port, "audio.position", "[ FL, FR ]"), 2);
    }

    private static AudioGraph legacyRoute(String id, String name, String selectedLink, FilterNode filter) {
        SwitchNode selector = new SwitchNode("legacy-switch", "Legacy output selector", STEREO,
                List.of("monitor", "headphones"), List.of(selectedLink));
        List<GraphNode> nodes = List.of(GraphCommonVibro.mainSource(),
                new MixerNode(GraphCommonVibro.MAIN_MIXER_ID, "Legacy Main", STEREO,
                        Map.of("applications", 0.8, "additional", 0.7),
                        Map.of("applications", true, "additional", false)),
                filter, selector,
                new DeviceSlotNode(UnifiedGraphConfig.MONITOR_OUTPUT_ID, "Monitor", "monitor-device", STEREO),
                new DeviceSlotNode("headphones-output", "Headphones", "headphones-device", STEREO));
        return new AudioGraph(id, name, nodes, List.of(
                GraphCommonVibro.edge("old-main", GraphCommonVibro.MAIN_SOURCE_ID, "out",
                        GraphCommonVibro.MAIN_MIXER_ID, "applications"),
                GraphCommonVibro.edge("old-main-filter", GraphCommonVibro.MAIN_MIXER_ID, "out", filter.id(), "in"),
                GraphCommonVibro.edge("old-filter-switch", filter.id(), "out", selector.id(), "in"),
                GraphCommonVibro.edge("old-monitor", selector.id(), "monitor", UnifiedGraphConfig.MONITOR_OUTPUT_ID, "in"),
                GraphCommonVibro.edge("old-headphones", selector.id(), "headphones", "headphones-output", "in")));
    }

    private static AudioGraph legacySharedGraph() {
        AudioGraph shared = GraphCommonVibro.defaults();
        List<GraphNode> nodes = new ArrayList<>();
        for (GraphNode node : shared.nodes()) {
            if (node instanceof MixerNode mixer && GraphCommonVibro.VIBRO_MIXER_ID.equals(node.id()))
                nodes.add(new MixerNode(mixer.id(), mixer.name(), STEREO, Map.of("bass", 0.4, "events", 0.5),
                        Map.of("bass", false, "events", true)));
            else if (node instanceof GainNode gain && GraphCommonVibro.VIBRO_GAIN_ID.equals(node.id()))
                nodes.add(new GainNode(gain.id(), gain.name(), STEREO, 0.8, true));
            else if (node instanceof DeviceSlotNode slot && GraphCommonVibro.VIBRO_OUTPUT_ID.equals(node.id()))
                nodes.add(new DeviceSlotNode(slot.id(), slot.name(), "vibro-device", STEREO));
            else nodes.add(node);
        }
        FilterNode userFilter = new FilterNode("legacy-haptic-eq", "User Haptic EQ", STEREO, "eq", true,
                Map.of("bands", List.of(Map.of("type", "peaking", "frequency_hz", 140.0,
                        "gain_db", -2.0, "q", 1.0))));
        nodes.add(userFilter);
        List<GraphConnection> edges = new ArrayList<>();
        for (GraphConnection edge : shared.connections()) {
            if (GraphCommonVibro.VIBRO_MIXER_ID.equals(edge.fromNode())
                    && GraphCommonVibro.VIBRO_GAIN_ID.equals(edge.toNode())) {
                edges.add(GraphCommonVibro.edge("legacy-haptic-eq-in", edge.fromNode(), edge.fromPort(),
                        userFilter.id(), "in"));
                edges.add(GraphCommonVibro.edge("legacy-haptic-eq-out", userFilter.id(), "out",
                        edge.toNode(), edge.toPort()));
            } else edges.add(edge);
        }
        return new AudioGraph(shared.id(), shared.name(), nodes, edges);
    }

    private static Map<String, Object> encoded(AudioGraph graph, String profile) {
        Map<String, Object> result = new LinkedHashMap<>(GraphCodec.toMap(graph));
        result.put("profile_id", profile);
        result.put("group_id", "");
        return result;
    }

    private static Map<String, Object> device(String id, String name, String role, DeviceIdentity identity,
                                              double volume, boolean muted) {
        return DefaultConfig.map("id", id, "name", name, "kind", "device_slot", "role", role,
                "identity", identity.toMap(), "volume", volume, "mute", muted, "selected", false);
    }
}
