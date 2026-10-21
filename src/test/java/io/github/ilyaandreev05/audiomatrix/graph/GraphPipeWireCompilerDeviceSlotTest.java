package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.UnifiedGraphConfig;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GraphPipeWireCompilerDeviceSlotTest {
    @Test void defaultGraphUsesOneApplicationBusAndOnlyRoutesMusicToTheMonitor() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph graph = route(config);
        AudioNode monitor = monitorDevice();
        AudioNode music = musicStream();
        DynamicInstanceRegistry.ReconcileResult reconciled = reconcile(graph, monitor, music);

        Map<String, Object> compiled = GraphPipeWireCompiler.compile(reconciled, config);
        List<Map<String, Object>> buses = maps(compiled.get("buses"));
        List<Map<String, Object>> routes = maps(compiled.get("routes"));

        String applicationSource = DynamicInstanceRegistry.stableNodeId(GraphCommonVibro.MAIN_SOURCE_ID,
                DynamicInstanceRegistry.application(music).stableKey(), "source");
        assertEquals(Set.of(UnifiedGraphConfig.EVENT_SOURCE_ID, applicationSource),
                buses.stream().map(bus -> String.valueOf(bus.get("id"))).collect(java.util.stream.Collectors.toSet()));
        assertEquals(0, buses.stream().filter(bus -> GraphCommonVibro.MAIN_SOURCE_ID.equals(bus.get("id"))).count(),
                "the applications generator is control-plane and must not become a PCM bus");
        assertEquals(1, routes.size());
        assertEquals(monitor.name(), routes.getFirst().get("to"));
        assertEquals(applicationSource, routes.getFirst().get("from"));
        assertEquals(List.of(), GraphValidator.validate(reconciled.effectiveGraph()));
    }

    @Test void unboundVibroStaysSilentWithoutASecondDefaultSinkRoute() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph graph = route(config);
        AudioNode monitor = monitorDevice();
        AudioNode music = musicStream();
        DynamicInstanceRegistry.ReconcileResult reconciled = reconcile(graph, monitor, music);
        List<Map<String, Object>> routes = maps(GraphPipeWireCompiler.compile(reconciled, config).get("routes"));

        assertEquals(1, routes.size());
        assertEquals(monitor.name(), routes.getFirst().get("to"));
        assertTrue(routes.stream().noneMatch(route -> "vibro".equals(route.get("role"))));
        assertTrue(reconciled.instances().keySet().stream().noneMatch(address ->
                GraphCommonVibro.VIBRO_OUTPUT_ID.equals(address.generatorId())),
                "an unassigned haptic device must not produce a route");
    }

    @Test void enablingMusicOnVibroKeepsTheIndependentEventLevelSafe() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph graph = withHapticLink(route(config));
        AudioNode vibro = hapticDevice();
        AudioNode music = musicStream();
        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(graph, Map.of(
                GraphCommonVibro.MAIN_SOURCE_ID, List.of(DynamicInstanceRegistry.application(music)),
                GraphCommonVibro.VIBRO_OUTPUT_ID, List.of(DynamicInstanceRegistry.output(vibro, false,
                        new DynamicInstanceRegistry.GainState(0.5, false), "haptic_output"))));
        AudioGraph effective = reconciled.effectiveGraph();

        assertTrue(connected(effective, "octine-main-switch", "haptic", UnifiedGraphConfig.MUSIC_GAIN_ID, "in"),
                "music reaches the vibro only through the selected MultiSwitch haptic port");
        assertTrue(connected(effective, UnifiedGraphConfig.MUSIC_GAIN_ID, "out",
                GraphCommonVibro.VIBRO_MIXER_ID, "music"));
        assertTrue(connected(effective, UnifiedGraphConfig.EVENT_SOURCE_ID, "out",
                UnifiedGraphConfig.EVENT_GAIN_ID, "in"));
        assertTrue(connected(effective, UnifiedGraphConfig.EVENT_GAIN_ID, "out",
                GraphCommonVibro.VIBRO_MIXER_ID, "events"));
        MixerNode vibroMerge = mixer(effective, GraphCommonVibro.VIBRO_MIXER_ID);
        assertEquals(Set.of("music", "events"), vibroMerge.inputGains().keySet(),
                "the music and the event branch keep separate merge inputs");
        GainNode musicGain = gain(effective, UnifiedGraphConfig.MUSIC_GAIN_ID);
        GainNode eventGain = gain(effective, UnifiedGraphConfig.EVENT_GAIN_ID);
        assertEquals(0.6, musicGain.gain(), 1e-12);
        assertEquals(0.65, eventGain.gain(), 1e-12);
        assertFalse(musicGain.muted());
        assertFalse(eventGain.muted());
        assertTrue(connected(effective, GraphCommonVibro.VIBRO_MIXER_ID, "out", GraphCommonVibro.VIBRO_GAIN_ID, "in"));
        assertTrue(connected(effective, GraphCommonVibro.VIBRO_GAIN_ID, "out", UnifiedGraphConfig.LIMITER_ID, "in"));
        assertTrue(connected(effective, UnifiedGraphConfig.LIMITER_ID, "out",
                DynamicInstanceRegistry.stableNodeId(GraphCommonVibro.VIBRO_OUTPUT_ID,
                        "device:" + vibro.identity().fingerprint(), "gain"), "in"),
                "the haptic device is fed by the isolated final limiter");

        Map<String, Object> compiled = GraphPipeWireCompiler.compile(reconciled, config);
        List<Map<String, Object>> routes = maps(compiled.get("routes"));
        Map<String, Object> musicRoute = routes.stream().filter(route ->
                !UnifiedGraphConfig.EVENT_SOURCE_ID.equals(route.get("from"))
                        && vibro.name().equals(route.get("to"))).findFirst().orElseThrow();
        Map<String, Object> events = routes.stream().filter(route ->
                UnifiedGraphConfig.EVENT_SOURCE_ID.equals(route.get("from"))).findFirst().orElseThrow();
        assertEquals(0.36, ((Number) musicRoute.get("volume")).doubleValue(), 1e-9);
        assertEquals(false, musicRoute.get("mute"));
        assertEquals(vibro.name(), events.get("to"));
        assertEquals(0.39, ((Number) events.get("volume")).doubleValue(), 1e-9);
        assertFalse(events.containsKey("filters_ref"));
    }

    @Test void rejectsPhysicalOutputSharedByMonitorAndVibroSlots() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph graph = withSelector(route(config), UnifiedGraphConfig.MONITOR_OUTPUT_ID, "device:*", List.of());

        List<String> errors = GraphValidator.validate(graph);
        assertTrue(errors.stream().anyMatch(message -> message.contains("octine-monitor-output")
                && message.contains("octine-vibro-output")
                && message.contains("device:role:haptic_output")), errors.toString());
        assertThrows(IllegalArgumentException.class, () -> GraphPipeWireCompiler.compile(graph, config));
    }

    private static DynamicInstanceRegistry.ReconcileResult reconcile(AudioGraph graph, AudioNode monitor,
                                                                     AudioNode music) {
        String port = DynamicInstanceRegistry.stableOutputPortId(UnifiedGraphConfig.MONITOR_OUTPUT_ID,
                monitor.identity().fingerprint());
        return DynamicInstanceRegistry.reconcile(withPort(graph, port), Map.of(
                GraphCommonVibro.MAIN_SOURCE_ID, List.of(DynamicInstanceRegistry.application(music)),
                UnifiedGraphConfig.MONITOR_OUTPUT_ID, List.of(DynamicInstanceRegistry.output(monitor))));
    }

    private static AudioGraph withPort(AudioGraph graph, String port) {
        List<GraphNode> nodes = new ArrayList<>();
        for (GraphNode node : graph.nodes()) {
            if (node instanceof SwitchNode sw) {
                nodes.add(new SwitchNode(sw.id(), sw.name(), sw.inputs().getFirst().channels(),
                        List.of("haptic", port), List.of("in->" + port)));
            } else {
                nodes.add(node);
            }
        }
        return new AudioGraph(graph.id(), graph.name(), nodes, graph.connections());
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

    private static AudioGraph withSelector(AudioGraph graph, String generatorId, String selector,
                                           List<String> excludedSelectors) {
        List<GraphNode> nodes = new ArrayList<>();
        for (GraphNode node : graph.nodes()) {
            if (!(node instanceof DynamicGeneratorNode generator) || !generator.id().equals(generatorId)) {
                nodes.add(node);
                continue;
            }
            Map<String, Object> parameters = new LinkedHashMap<>(generator.parameters());
            parameters.put("selector", selector);
            parameters.put("excluded_selectors", excludedSelectors);
            nodes.add(new DynamicGeneratorNode(generator.id(), generator.name(), selector,
                    generator.channels(), parameters));
        }
        return new AudioGraph(graph.id(), graph.name(), nodes, graph.connections());
    }

    private static boolean connected(AudioGraph graph, String from, String fromPort, String to, String toPort) {
        return graph.connections().stream().anyMatch(edge -> from.equals(edge.fromNode())
                && fromPort.equals(edge.fromPort()) && to.equals(edge.toNode()) && toPort.equals(edge.toPort()));
    }

    private static MixerNode mixer(AudioGraph graph, String id) {
        return graph.nodes().stream().filter(MixerNode.class::isInstance).map(MixerNode.class::cast)
                .filter(node -> id.equals(node.id())).findFirst().orElseThrow();
    }

    private static GainNode gain(AudioGraph graph, String id) {
        return graph.nodes().stream().filter(GainNode.class::isInstance).map(GainNode.class::cast)
                .filter(node -> id.equals(node.id())).findFirst().orElseThrow();
    }

    private static AudioGraph route(Map<String, Object> config) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes =
                (List<Map<String, Object>>) config.get("route_library");
        return GraphCodec.fromMap(routes.stream().filter(item -> "main".equals(item.get("id")))
                .findFirst().orElseThrow());
    }

    private static AudioNode monitorDevice() {
        return new AudioNode(71, "alsa_output.monitor", "Monitor", "Audio/Sink", "running",
                "", "", "", 2, List.of(48000), 0.7, false, null, identity("monitor"));
    }

    private static AudioNode hapticDevice() {
        return new AudioNode(72, "alsa_output.vibro", "Vibro", "Audio/Sink", "running",
                "", "", "", 2, List.of(48000), 0.6, false, null, identity("vibro"));
    }

    private static AudioNode musicStream() {
        return new AudioNode(73, "1234", "Music", "Stream/Output/Audio", "running",
                "Music player", "music", "org.example.music", 2, List.of(48000), 0.8, false, null);
    }

    private static DeviceIdentity identity(String serial) {
        return DeviceIdentity.from(Map.of("device.bus-path", "pci-0000:01:00.0", "device.serial", "serial-" + serial,
                "device.profile.name", "hdmi-stereo", "device.port.name", "hdmi-output", "audio.position", "FL,FR"), 2);
    }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
