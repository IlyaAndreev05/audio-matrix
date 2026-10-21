package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
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
        assertEquals(List.of("in"), multiSwitch.inputs().stream().map(AudioPort::id).toList());
        assertEquals(List.of(), multiSwitch.activeLinks(),
                "a fresh graph selects no output before a device is discovered");
        assertTrue(graph.nodes().stream().anyMatch(node -> node instanceof DynamicGeneratorNode generator
                && "application:unassigned".equals(generator.selector())));
        assertTrue(graph.nodes().stream().anyMatch(node -> UnifiedGraphConfig.EVENT_GAIN_ID.equals(node.id())));
        assertEquals(List.of(), ConfigValidator.validate(config));
    }

    @Test void musicSwitchAndEventPathAreIndependentAtTheVibroOutput() {
        Map<String, Object> config = DefaultConfig.create();
        AudioNode vibro = hapticDevice("alsa_output.vibro", "Vibro");
        AudioNode music = new AudioNode(64, "1234", "Music", "Stream/Output/Audio", "running",
                "Music player", "music", "org.example.music", 2, List.of(48000), 0.8, false, null);
        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(
                withHapticLink(graph(config)), Map.of(
                        GraphCommonVibro.MAIN_SOURCE_ID, List.of(DynamicInstanceRegistry.application(music)),
                        GraphCommonVibro.VIBRO_OUTPUT_ID, List.of(DynamicInstanceRegistry.output(vibro, false,
                                new DynamicInstanceRegistry.GainState(0.5, false), "haptic_output"))));
        AudioGraph effective = reconciled.effectiveGraph();

        assertTrue(connected(effective, UnifiedGraphConfig.SWITCH_ID, "haptic",
                UnifiedGraphConfig.MUSIC_GAIN_ID, "in"),
                "music reaches the vibro only through the selected MultiSwitch haptic port");
        assertTrue(connected(effective, UnifiedGraphConfig.MUSIC_GAIN_ID, "out",
                GraphCommonVibro.VIBRO_MIXER_ID, "music"));
        assertTrue(connected(effective, UnifiedGraphConfig.EVENT_SOURCE_ID, "out",
                UnifiedGraphConfig.EVENT_GAIN_ID, "in"));
        assertTrue(connected(effective, UnifiedGraphConfig.EVENT_GAIN_ID, "out",
                GraphCommonVibro.VIBRO_MIXER_ID, "events"));
        MixerNode merge = node(effective, GraphCommonVibro.VIBRO_MIXER_ID, MixerNode.class);
        assertEquals(Set.of("music", "events"), merge.inputGains().keySet(),
                "the music branch and the event branch keep separate merge inputs");
        assertFalse(connected(effective, UnifiedGraphConfig.MUSIC_GAIN_ID, "out",
                UnifiedGraphConfig.EVENT_GAIN_ID, "in"),
                "the event path never passes through the music level");
        GainNode musicGain = node(effective, UnifiedGraphConfig.MUSIC_GAIN_ID, GainNode.class);
        GainNode eventGain = node(effective, UnifiedGraphConfig.EVENT_GAIN_ID, GainNode.class);
        assertEquals(0.6, musicGain.gain(), 1e-12);
        assertEquals(0.65, eventGain.gain(), 1e-12);
        assertFalse(musicGain.muted());
        assertFalse(eventGain.muted());
        assertTrue(connected(effective, GraphCommonVibro.VIBRO_MIXER_ID, "out",
                GraphCommonVibro.VIBRO_GAIN_ID, "in"));
        assertTrue(connected(effective, GraphCommonVibro.VIBRO_GAIN_ID, "out",
                UnifiedGraphConfig.LIMITER_ID, "in"));
        assertTrue(connected(effective, UnifiedGraphConfig.LIMITER_ID, "out",
                DynamicInstanceRegistry.stableNodeId(GraphCommonVibro.VIBRO_OUTPUT_ID,
                        "device:" + vibro.identity().fingerprint(), "gain"), "in"),
                "the haptic device is fed by the isolated final limiter");

        Map<String, Object> compiled = GraphPipeWireCompiler.compile(reconciled, config);
        List<Map<String, Object>> routes = maps(compiled.get("routes"));
        Map<String, Object> musicRoute = routes.stream().filter(route ->
                !UnifiedGraphConfig.EVENT_SOURCE_ID.equals(route.get("from"))
                        && vibro.name().equals(route.get("to"))).findFirst().orElseThrow();
        Map<String, Object> event = routes.stream().filter(route ->
                UnifiedGraphConfig.EVENT_SOURCE_ID.equals(route.get("from"))).findFirst().orElseThrow();
        assertEquals(0.36, ((Number) musicRoute.get("volume")).doubleValue(), 1e-9);
        assertEquals(false, musicRoute.get("mute"));
        assertEquals("alsa_output.vibro", event.get("to"));
        assertEquals(0.39, ((Number) event.get("volume")).doubleValue(), 1e-9);
        assertFalse(event.containsKey("filters_ref"));
        assertTrue(maps(compiled.get("applications")).stream().anyMatch(rule ->
                String.valueOf(rule.get("bus")).contains("event-source")));
        assertEquals(List.of(), GraphValidator.validate(graph(config)));
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
            if (node instanceof MixerNode mixer && GraphCommonVibro.VIBRO_MIXER_ID.equals(mixer.id()))
                return new MixerNode(mixer.id(), mixer.name(), mixer.inputs().getFirst().channels(),
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
        DynamicGeneratorNode ordinary = node(migrated, UnifiedGraphConfig.MONITOR_OUTPUT_ID, DynamicGeneratorNode.class);
        DynamicGeneratorNode vibro = node(migrated, GraphCommonVibro.VIBRO_OUTPUT_ID, DynamicGeneratorNode.class);
        assertEquals("device:*", ordinary.selector());
        assertEquals("device:role:haptic_output", vibro.selector());
        assertEquals(Map.of(), vibro.instances(),
                "a legacy slot without a reliable device identity stays unassigned");
        assertEquals(0.125, node(migrated, GraphCommonVibro.VIBRO_GAIN_ID, GainNode.class).gain(), 1e-12);
        assertTrue(node(migrated, UnifiedGraphConfig.MUSIC_GAIN_ID, GainNode.class).muted());
        assertEquals(0.512, node(migrated, UnifiedGraphConfig.MUSIC_GAIN_ID, GainNode.class).gain(), 1e-12);
        assertEquals(0.064, node(migrated, UnifiedGraphConfig.EVENT_GAIN_ID, GainNode.class).gain(), 1e-12);
        SwitchNode route = node(migrated, UnifiedGraphConfig.SWITCH_ID, SwitchNode.class);
        assertFalse(route.activeLinks().contains("in->haptic"),
                "music must not auto-enable on the haptic output");
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

    private static AudioGraph withHapticLink(AudioGraph graph) {
        List<GraphNode> nodes = new ArrayList<>();
        for (GraphNode node : graph.nodes()) {
            if (node instanceof SwitchNode sw) {
                nodes.add(new SwitchNode(sw.id(), sw.name(), sw.inputs().getFirst().channels(),
                        sw.outputs().stream().map(AudioPort::id).toList(), List.of("in->haptic")));
            } else {
                nodes.add(node);
            }
        }
        return new AudioGraph(graph.id(), graph.name(), nodes, graph.connections());
    }

    private static boolean connected(AudioGraph graph, String from, String fromPort, String to, String toPort) {
        return graph.connections().stream().anyMatch(edge -> from.equals(edge.fromNode())
                && fromPort.equals(edge.fromPort()) && to.equals(edge.toNode()) && toPort.equals(edge.toPort()));
    }

    private static AudioNode hapticDevice(String name, String description) {
        DeviceIdentity identity = DeviceIdentity.from(Map.of("device.bus-path", "pci-0000:01:00.0",
                "device.serial", "serial-vibro", "device.profile.name", "analog-stereo",
                "device.port.name", "front-output", "audio.position", "FL,FR"), 2);
        return new AudioNode(63, name, description, "Audio/Sink", "running", "", "", "", 2, List.of(48000),
                0.6, false, null, identity);
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
