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
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphValidator;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SwitchNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedGraphConfigTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test void freshConfigHasOneProfileAndGeneratorMergeSwitchOutputTopology() {
        Map<String, Object> config = DefaultConfig.create();
        List<Map<String, Object>> profiles = maps(config.get("profiles"));
        assertEquals(1, profiles.size());
        assertEquals("main", profiles.getFirst().get("id"));
        assertEquals("Основной", profiles.getFirst().get("name"));
        assertEquals("main", config.get("active_profile"));
        assertEquals("main", config.get("active_route"));
        assertEquals(1, maps(config.get("route_library")).size());
        assertEquals("", profiles.getFirst().get("default_output"));
        assertFalse(config.containsKey("common_vibro_graph"));
        assertTrue(maps(config.get("devices")).stream()
                .noneMatch(device -> "haptic_output".equals(device.get("role"))));

        AudioGraph graph = activeGraph(config);
        DynamicGeneratorNode applications = generator(graph, GraphCommonVibro.MAIN_SOURCE_ID);
        assertEquals("applications", applications.registry());
        assertEquals("default-application", applications.template());
        assertEquals(Map.of("node_id", GraphCommonVibro.MAIN_MIXER_ID, "port_id", "applications"),
                applications.connectTo());
        assertEquals(List.of("application:" +
                        io.github.ilyaandreev05.audiomatrix.graph.EventSoundStudioSource.APPLICATION_ID),
                applications.excludedSelectors());
        assertEquals(List.of("source", "gain"), applications.templateNodes().stream()
                .map(node -> String.valueOf(node.get("type"))).toList());
        assertEquals(1, applications.templateConnections().size());

        MixerNode mainMerge = mixer(graph, GraphCommonVibro.MAIN_MIXER_ID);
        assertEquals(2, mainMerge.inputs().size());
        assertTrue(mainMerge.inputs().stream().anyMatch(port -> "applications".equals(port.id())));
        assertTrue(mainMerge.inputs().stream().anyMatch(port -> "additional".equals(port.id())));
        assertEquals(1, graph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).filter(node -> "applications".equals(node.registry())).count());
        assertEquals(1, graph.nodes().stream().filter(node -> node instanceof SwitchNode).count());
        SwitchNode multiSwitch = switchNode(graph);
        assertEquals(1, multiSwitch.inputs().size());
        assertTrue(multiSwitch.multiple());
        assertTrue(multiSwitch.activeLinks().isEmpty());
        assertEquals(List.of(UnifiedGraphConfig.HAPTIC_MUSIC_PORT_ID),
                multiSwitch.outputs().stream().map(port -> port.id()).toList());

        DynamicGeneratorNode outputs = generator(graph, UnifiedGraphConfig.OUTPUTS_GENERATOR_ID);
        assertEquals("outputs", outputs.registry());
        assertEquals("default-output", outputs.template());
        assertEquals("device:*", outputs.selector());
        assertEquals(Map.of("node_id", UnifiedGraphConfig.SWITCH_ID, "port_id", "in"), outputs.connectTo());
        assertTrue(outputs.instances().isEmpty());
        assertEquals(List.of("device:haptic-assigned"), outputs.excludedSelectors());
        assertEquals(Boolean.TRUE, outputs.parameters().get("auto_select_trusted_hdmi_on_first_discovery"));
        assertEquals(1.0, outputs.initialGain());
        assertEquals(List.of("gain", "output"), outputs.templateNodes().stream()
                .map(node -> String.valueOf(node.get("type"))).toList());
        assertEquals(1, outputs.templateConnections().size());

        DynamicGeneratorNode hapticOutputs = generator(graph, UnifiedGraphConfig.HAPTIC_OUTPUTS_GENERATOR_ID);
        assertEquals("outputs", hapticOutputs.registry());
        assertEquals("default-output", hapticOutputs.template());
        assertEquals("device:haptic-assigned", hapticOutputs.selector());
        assertEquals(Map.of("node_id", UnifiedGraphConfig.LIMITER_ID, "port_id", "out"), hapticOutputs.connectTo());
        assertTrue(hapticOutputs.instances().isEmpty());
        assertEquals(Boolean.FALSE, hapticOutputs.parameters().get("auto_select_trusted_hdmi_on_first_discovery"));
        assertEquals(List.of("gain", "output"), hapticOutputs.templateNodes().stream()
                .map(node -> String.valueOf(node.get("type"))).toList());
        assertEquals(1, hapticOutputs.templateConnections().size());

        assertTrue(graph.nodes().stream().noneMatch(node -> node instanceof DeviceSlotNode || node instanceof OutputNode));
        assertFalse(multiSwitch.activeLinks().contains("in->" + UnifiedGraphConfig.HAPTIC_MUSIC_PORT_ID));
        assertEdge(graph, UnifiedGraphConfig.SWITCH_ID, UnifiedGraphConfig.HAPTIC_MUSIC_PORT_ID,
                UnifiedGraphConfig.MUSIC_GAIN_ID, "in");
        assertEdge(graph, UnifiedGraphConfig.MUSIC_GAIN_ID, "out", GraphCommonVibro.VIBRO_MIXER_ID, "music");
        assertEdge(graph, UnifiedGraphConfig.EVENT_SOURCE_ID, "out", UnifiedGraphConfig.EVENT_GAIN_ID, "in");
        assertEdge(graph, UnifiedGraphConfig.EVENT_GAIN_ID, "out", GraphCommonVibro.VIBRO_MIXER_ID, "events");
        assertEdge(graph, GraphCommonVibro.VIBRO_MIXER_ID, "out", GraphCommonVibro.VIBRO_GAIN_ID, "in");
        assertEdge(graph, GraphCommonVibro.VIBRO_GAIN_ID, "out", UnifiedGraphConfig.LIMITER_ID, "in");
        assertEquals(List.of(), ConfigValidator.validate(config));
        assertEquals(List.of(), GraphValidator.validate(graph));
        assertEquals(2, graph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).filter(node -> "outputs".equals(node.registry())).count());
    }

    @Test void schemaNineMigrationPreservesArchiveLevelsFiltersAndExactDeviceIdentities() {
        Map<String, Object> config = oldSchemaNineConfig(true);
        ConfigMigration.upgradeConfig(config);

        assertEquals(DefaultConfig.SCHEMA_VERSION, config.get("schema_version"));
        assertEquals("main", config.get("active_profile"));
        assertEquals("main", config.get("active_route"));
        assertEquals(1, maps(config.get("profiles")).size());
        AudioGraph migrated = activeGraph(config);
        assertTrue(migrated.nodes().stream().noneMatch(node -> node instanceof DeviceSlotNode || node instanceof OutputNode));
        assertEquals(0.064, gain(migrated, UnifiedGraphConfig.MUSIC_GAIN_ID).gain(), 1e-9);
        assertEquals(0.125, gain(migrated, UnifiedGraphConfig.EVENT_GAIN_ID).gain(), 1e-9);
        assertEquals(0.512, gain(migrated, GraphCommonVibro.VIBRO_GAIN_ID).gain(), 1e-9);
        assertTrue(gain(migrated, UnifiedGraphConfig.EVENT_GAIN_ID).muted());
        assertFalse(gain(migrated, UnifiedGraphConfig.MUSIC_GAIN_ID).muted());
        assertTrue(gain(migrated, GraphCommonVibro.VIBRO_GAIN_ID).muted());

        FilterNode legacyLowPass = migrated.nodes().stream().filter(FilterNode.class::isInstance)
                .map(FilterNode.class::cast).filter(filter -> "octine-bass-low-pass".equals(filter.id()))
                .findFirst().orElseThrow();
        assertEquals("low_pass", legacyLowPass.processor());
        assertEquals(60.0, legacyLowPass.parameters().get("frequency_hz"));
        FilterNode limiter = migrated.nodes().stream().filter(FilterNode.class::isInstance)
                .map(FilterNode.class::cast).filter(filter -> UnifiedGraphConfig.LIMITER_ID.equals(filter.id()))
                .findFirst().orElseThrow();
        assertEquals(-1.0, limiter.parameters().get("ceiling_db"));

        DeviceIdentity headphonesIdentity = deviceIdentity("headphones-path", "headphones-serial",
                "analog-stereo", "headphones");
        DeviceIdentity hapticIdentity = deviceIdentity("haptic-path", "haptic-serial",
                "analog-stereo", "haptic-output");
        DynamicGeneratorNode outputs = generator(migrated, UnifiedGraphConfig.OUTPUTS_GENERATOR_ID);
        DynamicGeneratorNode hapticOutputs = generator(migrated, UnifiedGraphConfig.HAPTIC_OUTPUTS_GENERATOR_ID);
        String headphonesKey = "device:" + headphonesIdentity.fingerprint();
        String hapticKey = "device:" + hapticIdentity.fingerprint();
        assertTrue(outputs.instances().containsKey(headphonesKey));
        assertFalse(outputs.instances().containsKey(hapticKey));
        assertTrue(outputs.excludedSelectors().contains(hapticKey));
        assertTrue(hapticOutputs.instances().containsKey(hapticKey));
        assertEquals(hapticKey, hapticOutputs.selector());
        assertEquals(Boolean.FALSE, outputs.parameters().get("auto_select_trusted_hdmi_on_first_discovery"));

        String headphonesPort = DynamicInstanceRegistry.stableOutputPortId(
                UnifiedGraphConfig.OUTPUTS_GENERATOR_ID, headphonesIdentity.fingerprint());
        SwitchNode route = switchNode(migrated);
        assertTrue(route.activeLinks().contains("in->" + headphonesPort));
        assertTrue(route.activeLinks().contains("in->" + UnifiedGraphConfig.HAPTIC_MUSIC_PORT_ID));
        assertTrue(route.outputs().stream().anyMatch(port -> headphonesPort.equals(port.id())));

        Map<String, Object> hapticDevice = maps(config.get("devices")).stream()
                .filter(device -> "vibro-device".equals(device.get("id"))).findFirst().orElseThrow();
        assertEquals("haptic_output", hapticDevice.get("role"));
        assertEquals(hapticIdentity.toMap(), object(hapticDevice.get("identity")));
        assertEquals(0.343, ((Number) hapticDevice.get("volume")).doubleValue(), 1e-9);
        assertTrue(Boolean.TRUE.equals(hapticDevice.get("mute")));

        Map<String, Object> archive = object(config.get("migration_archive"));
        assertEquals(9, archive.get("source_schema_version"));
        Map<String, Object> before = object(archive.get("prior_config"));
        assertEquals(2, maps(before.get("profiles")).size());
        assertTrue(before.containsKey("common_vibro_graph"));
        assertEquals(List.of(), ConfigValidator.validate(config));
    }

    @Test void legacyDeviceNamesWithoutReliableIdentityNeverBecomeOutputBranches() {
        Map<String, Object> config = oldSchemaNineConfig(false);
        ConfigMigration.upgradeConfig(config);

        AudioGraph migrated = activeGraph(config);
        DynamicGeneratorNode outputs = generator(migrated, UnifiedGraphConfig.OUTPUTS_GENERATOR_ID);
        DynamicGeneratorNode hapticOutputs = generator(migrated, UnifiedGraphConfig.HAPTIC_OUTPUTS_GENERATOR_ID);
        assertTrue(outputs.instances().isEmpty());
        assertTrue(hapticOutputs.instances().isEmpty());
        assertEquals(List.of(), switchNode(migrated).activeLinks());
        assertTrue(migrated.nodes().stream().noneMatch(node -> node instanceof DeviceSlotNode || node instanceof OutputNode));
        assertEquals(Boolean.FALSE, outputs.parameters().get("auto_select_trusted_hdmi_on_first_discovery"));
        assertEquals(List.of(), ConfigValidator.validate(config));
    }

    private static Map<String, Object> oldSchemaNineConfig(boolean reliableIdentities) {
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
        DeviceIdentity monitorIdentity = deviceIdentity("monitor-path", "monitor-serial",
                "hdmi-stereo", "hdmi-output");
        DeviceIdentity headphonesIdentity = deviceIdentity("headphones-path", "headphones-serial",
                "analog-stereo", "headphones");
        DeviceIdentity hapticIdentity = deviceIdentity("haptic-path", "haptic-serial",
                "analog-stereo", "haptic-output");
        Map<String, Object> hapticBinding = device("vibro-device", "Vibro",
                reliableIdentities ? hapticIdentity : null, "haptic_output");
        hapticBinding.put("volume", 0.7);
        hapticBinding.put("mute", true);
        config.put("devices", new ArrayList<>(List.of(
                device("monitor-device", "Monitor", reliableIdentities ? monitorIdentity : null, "output"),
                device("headphones-device", "Headphones",
                        reliableIdentities ? headphonesIdentity : null, "output"),
                hapticBinding)));
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

    private static Map<String, Object> device(String id, String name, DeviceIdentity identity, String role) {
        Map<String, Object> result = DefaultConfig.map("id", id, "name", name, "role", role,
                "volume", 1.0, "mute", false);
        if (identity != null) result.put("identity", identity.toMap());
        else result.put("selector", DefaultConfig.map("kind", "name_equals", "value", name));
        return result;
    }

    private static DeviceIdentity deviceIdentity(String path, String serial, String profile, String port) {
        return DeviceIdentity.from(Map.of("device.bus-path", path, "device.serial", serial,
                "device.profile.name", profile, "device.port.name", port, "audio.position", "FL,FR"), 2);
    }

    private static Map<String, Object> encoded(AudioGraph graph, String profile) {
        Map<String, Object> result = new LinkedHashMap<>(GraphCodec.toMap(graph));
        result.put("profile_id", profile);
        result.put("group_id", "");
        return result;
    }

    private static AudioGraph activeGraph(Map<String, Object> config) {
        return GraphCodec.fromMap(maps(config.get("route_library")).getFirst());
    }

    private static DynamicGeneratorNode generator(AudioGraph graph, String id) {
        return graph.nodes().stream().filter(node -> id.equals(node.id()) && node instanceof DynamicGeneratorNode)
                .map(DynamicGeneratorNode.class::cast).findFirst().orElseThrow();
    }

    private static MixerNode mixer(AudioGraph graph, String id) {
        return graph.nodes().stream().filter(node -> id.equals(node.id()) && node instanceof MixerNode)
                .map(MixerNode.class::cast).findFirst().orElseThrow();
    }

    private static SwitchNode switchNode(AudioGraph graph) {
        return graph.nodes().stream().filter(SwitchNode.class::isInstance)
                .map(SwitchNode.class::cast).findFirst().orElseThrow();
    }

    private static void assertEdge(AudioGraph graph, String from, String fromPort, String to, String toPort) {
        assertTrue(graph.connections().stream().anyMatch(edge -> from.equals(edge.fromNode())
                && fromPort.equals(edge.fromPort()) && to.equals(edge.toNode()) && toPort.equals(edge.toPort())));
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
