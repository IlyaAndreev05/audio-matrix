package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A generated application instance shares the Merge and the selected MultiSwitch link. */
class GraphGeneratedApplicationRoutingTest {
    private static final List<String> STEREO = List.of("FL", "FR");
    private static final String MERGE = "octine-main-mixer";
    private static final String SWITCH = "octine-main-switch";
    private static final String GENERATOR = "octine-main-source";

    @Test void generatedInstanceFeedsTheSharedMergeAndTheSelectedLink() {
        AudioGraph graph = graph();
        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(graph,
                Map.of(GENERATOR, List.of(DynamicInstanceRegistry.application(stereoApp())),
                        "dynamic-outputs", List.of(DynamicInstanceRegistry.output(newDevice("usb")),
                                DynamicInstanceRegistry.output(newDevice("dac")))));

        assertEquals(List.of(), GraphValidator.validate(reconciled.effectiveGraph(), reconciled.instances()),
                "the materialized instances keep the effective graph valid");

        String source = DynamicInstanceRegistry.stableNodeId(GENERATOR, "app:org.example.Music", "source");
        String gain = DynamicInstanceRegistry.stableNodeId(GENERATOR, "app:org.example.Music", "gain");
        assertTrue(connected(reconciled.effectiveGraph(), source, "out", gain, "in"),
                "the generated instance owns its source and gain");
        assertTrue(reconciled.effectiveGraph().connections().stream()
                        .filter(edge -> edge.enabled() && edge.fromNode().equals(gain))
                        .anyMatch(edge -> edge.toNode().equals(MERGE)),
                "the generated instance enters the shared Merge");
        assertTrue(connected(reconciled.effectiveGraph(), MERGE, "out", SWITCH, "in"),
                "the Merge feeds the single MultiSwitch input");

        Map<String, Object> plan = GraphPipeWireCompiler.compile(reconciled, DefaultConfig.create());
        List<Map<String, Object>> buses = maps(plan.get("buses"));
        List<Map<String, Object>> routes = maps(plan.get("routes"));
        assertTrue(buses.stream().anyMatch(bus -> source.equals(bus.get("id"))),
                "the generated instance owns its own bus");
        assertFalse(buses.stream().anyMatch(bus -> GENERATOR.equals(bus.get("id"))),
                "the control-plane generator never becomes a bus");
        assertFalse(routes.stream().anyMatch(route -> GENERATOR.equals(route.get("from"))),
                "the control-plane generator never carries a route");

        Map<String, Object> selected = route(routes, source, "benq-hdmi");
        assertNotNull(selected, "the selected MultiSwitch link carries the instance to the monitor");
        assertEquals(false, selected.get("mute"), "the selected link is not muted");
        assertTrue(((Number) selected.get("volume")).doubleValue() > 0.0, "the selected link carries the audio");
        Map<String, Object> haptic = route(routes, source, "family17h-front-headphones");
        assertNotNull(haptic, "the selected vibro link carries the instance too");
        assertEquals(false, haptic.get("mute"), "the selected vibro link is not muted");
        for (Map<String, Object> route : routes)
            if (route.get("from").equals(source) && !route.get("to").equals("benq-hdmi")
                    && !route.get("to").equals("family17h-front-headphones"))
                assertEquals(true, route.get("mute"), "an unselected branch stays silent");
    }

    @Test void aMonoApplicationKeepsTheInstanceRouted() {
        AudioGraph graph = graph();
        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(graph,
                Map.of(GENERATOR, List.of(DynamicInstanceRegistry.application(monoApp()))));

        assertEquals(List.of(), GraphValidator.validate(reconciled.effectiveGraph(), reconciled.instances()),
                "a mono stream does not invalidate the generated boundary connection");
        Map<String, Object> plan = GraphPipeWireCompiler.compile(reconciled, DefaultConfig.create());
        String source = DynamicInstanceRegistry.stableNodeId(GENERATOR, "process:headset", "source");
        Map<String, Object> selected = route(maps(plan.get("routes")), source, "benq-hdmi");
        assertNotNull(selected, "the mono instance reaches the selected output");
        assertEquals(false, selected.get("mute"));
    }

    @Test void aNewDeviceBecomesAnUnselectedSwitchPort() {
        AudioGraph graph = graph();
        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(graph,
                Map.of(GENERATOR, List.of(DynamicInstanceRegistry.application(stereoApp())),
                        "dynamic-outputs", List.of(DynamicInstanceRegistry.output(newDevice("usb")))));
        SwitchNode switchNode = reconciled.effectiveGraph().nodes().stream().filter(SwitchNode.class::isInstance)
                .map(SwitchNode.class::cast).findFirst().orElseThrow();
        String port = DynamicInstanceRegistry.stableOutputPortId("dynamic-outputs",
                newDevice("usb").identity().fingerprint());
        assertTrue(switchNode.outputs().stream().anyMatch(output -> output.id().equals(port)),
                "the discovered device becomes a stable MultiSwitch output port");
        assertFalse(switchNode.activeLinks().contains("in->" + port),
                "a newly discovered device is not selected by default");
        assertTrue(switchNode.activeLinks().containsAll(List.of("in->monitor", "in->vibro")),
                "the selected outputs keep their selection");
    }

    @Test void theSystemInputIsServedByOneInstanceWithoutAnApplicationRule() {
        AudioGraph graph = graph();
        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(graph,
                Map.of(GENERATOR, List.of(DynamicInstanceRegistry.application(stereoApp()),
                        DynamicInstanceRegistry.systemInput())));
        assertEquals(List.of(), GraphValidator.validate(reconciled.effectiveGraph(), reconciled.instances()),
                "the system input keeps the effective graph valid");

        Map<String, Object> plan = GraphPipeWireCompiler.compile(reconciled, DefaultConfig.create());
        List<Map<String, Object>> buses = maps(plan.get("buses"));
        List<Map<String, Object>> rules = maps(plan.get("applications"));
        String systemSource = DynamicInstanceRegistry.stableNodeId(GENERATOR, "app:audiomatrix-system-input", "source");
        List<Map<String, Object>> systemBuses = buses.stream()
                .filter(bus -> systemSource.equals(bus.get("id"))).toList();
        assertEquals(1, systemBuses.size(), "the system input is served by exactly one instance");
        assertEquals("system_input", systemBuses.getFirst().get("role"),
                "the system input bus is marked so the runtime feeds the system monitor into it");
        assertFalse(rules.stream().anyMatch(rule -> systemSource.equals(rule.get("bus"))),
                "the system input owns no application rule");
        assertFalse(rules.stream().anyMatch(rule -> "audiomatrix-system-input"
                        .equals(String.valueOf(rule.get("match")))),
                "the system input never appears as an application rule");
        assertFalse(buses.stream().anyMatch(bus -> GENERATOR.equals(bus.get("id"))),
                "the control-plane generator never becomes a bus");

        Map<String, Object> selected = route(maps(plan.get("routes")), systemSource, "benq-hdmi");
        assertNotNull(selected, "the system input reaches the selected output");
        assertEquals(false, selected.get("mute"), "the system input follows the selected link");
        assertTrue(((Number) selected.get("volume")).doubleValue() > 0.0, "the system input carries audio");
    }

    private static AudioGraph graph() {
        DynamicGeneratorNode applications = new DynamicGeneratorNode(GENERATOR, "Applications",
                "application:unassigned", STEREO, Map.of("registry", "applications",
                "connect_to", Map.of("node_id", MERGE, "port_id", "applications")));
        MixerNode merge = new MixerNode(MERGE, "Application Merge", STEREO,
                Map.of("applications", 1.0, "additional", 1.0));
        SwitchNode multi = new SwitchNode(SWITCH, "MultiSwitch", STEREO, List.of("monitor", "vibro"),
                List.of("in->monitor", "in->vibro"));
        DynamicGeneratorNode outputs = new DynamicGeneratorNode("dynamic-outputs", "Outputs", "device:*", STEREO,
                Map.of("registry", "outputs", "connect_to", Map.of("node_id", SWITCH, "port_id", "in"),
                        "excluded_selectors", List.of("device:role:haptic_output")));
        List<GraphNode> nodes = new ArrayList<>(List.of(applications, merge, multi, outputs,
                new DeviceSlotNode("octine-monitor-output", "Monitor", "benq-hdmi", STEREO),
                new DeviceSlotNode("octine-vibro-output", "Vibro", "family17h-front-headphones", STEREO),
                new GainNode("octine-vibro-music-volume", "Music to the vibro", STEREO, 0.974, false),
                new SourceNode("octine-event-source", "Event sounds",
                        EventSoundStudioSource.SELECTOR, STEREO),
                new GainNode("octine-vibro-events-volume", "Event level", STEREO, 0.865, false),
                new MixerNode("octine-vibro-mixer", "Vibro Merge", STEREO, Map.of("music", 1.0, "events", 1.0)),
                new GainNode("octine-vibro-gain", "Vibro level", STEREO, 0.92, false),
                new FilterNode("octine-vibro-limiter", "Limiter", STEREO, "limiter", true,
                        Map.of("ceiling_db", -1.0, "lookahead_ms", 5.0, "release_ms", 80.0))));
        List<GraphConnection> edges = new ArrayList<>(List.of(
                new GraphConnection("merge-switch", MERGE, "out", SWITCH, "in", true, 1.0),
                new GraphConnection("switch-monitor", SWITCH, "monitor", "octine-monitor-output", "in", true, 1.0),
                new GraphConnection("switch-vibro", SWITCH, "vibro", "octine-vibro-music-volume", "in", true, 1.0),
                new GraphConnection("music-merge", "octine-vibro-music-volume", "out", "octine-vibro-mixer", "music", true, 1.0),
                new GraphConnection("event-volume", "octine-event-source", "out", "octine-vibro-events-volume", "in", true, 1.0),
                new GraphConnection("event-merge", "octine-vibro-events-volume", "out", "octine-vibro-mixer", "events", true, 1.0),
                new GraphConnection("vibro-total", "octine-vibro-mixer", "out", "octine-vibro-gain", "in", true, 1.0),
                new GraphConnection("vibro-limiter", "octine-vibro-gain", "out", "octine-vibro-limiter", "in", true, 1.0),
                new GraphConnection("vibro-output", "octine-vibro-limiter", "out", "octine-vibro-output", "in", true, 1.0)));
        return new AudioGraph("route-generated", "Generated routing", nodes, edges);
    }

    private static AudioNode stereoApp() {
        return new AudioNode(90, "pw-play", "Music", "Stream/Output/Audio", "running", "Player", "pw-play",
                "org.example.Music", 2, List.of(48000), 0.8, false, null);
    }

    private static AudioNode monoApp() {
        return new AudioNode(91, "headset", "Headset", "Stream/Output/Audio", "running", "", "headset", "",
                1, List.of(16000), 1.0, false, null);
    }

    private static AudioNode newDevice(String serial) {
        DeviceIdentity identity = DeviceIdentity.from(Map.of("device.bus-path", "pci-0000:01:00.0",
                "device.serial", "serial-" + serial, "device.profile.name", "stereo",
                "device.port.name", "port-" + serial, "audio.position", "FL,FR"), 2);
        return new AudioNode(92, "alsa_output." + serial, serial, "Audio/Sink", "running", "", "", "", 2,
                List.of(48000), 1.0, false, null, identity);
    }

    private static boolean connected(AudioGraph graph, String from, String fromPort, String to, String toPort) {
        return graph.connections().stream().anyMatch(edge -> edge.enabled()
                && edge.fromNode().equals(from) && edge.fromPort().equals(fromPort)
                && edge.toNode().equals(to) && edge.toPort().equals(toPort));
    }

    private static Map<String, Object> route(List<Map<String, Object>> routes, String from, String to) {
        return routes.stream().filter(route -> from.equals(route.get("from")) && to.equals(route.get("to")))
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
