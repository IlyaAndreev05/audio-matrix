package io.github.ilyaandreev05.audiomatrix.audio;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphPipeWireCompiler;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.SwitchNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The runtime plan carries a generated application on the selected MultiSwitch link. */
class PipeWireGeneratedApplicationRoutingTest {
    private static final List<String> STEREO = List.of("FL", "FR");
    private static final String GENERATOR = "octine-main-source";
    private static final String MERGE = "octine-main-mixer";
    private static final String SWITCH = "octine-main-switch";

    @Test void aGeneratedApplicationReachesTheSelectedOutputOnItsOwnBus() throws IOException {
        AudioGraph graph = graph();
        AudioNode application = new AudioNode(90, "pw-play", "Music", "Stream/Output/Audio", "running", "Player",
                "pw-play", "org.example.Music", 2, List.of(48000), 0.8, false, null);
        AudioNode monitor = sink(70, "benq-hdmi", "Monitor");
        AudioNode vibro = sink(71, "family17h-front-headphones", "Vibro");
        AudioNode discovered = sink(72, "alsa_output.usb", "USB DAC");
        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(graph,
                Map.of(GENERATOR, List.of(DynamicInstanceRegistry.application(application)),
                        "dynamic-outputs", List.of(DynamicInstanceRegistry.output(discovered))));
        Map<String, Object> compiled = GraphPipeWireCompiler.compile(reconciled, DefaultConfig.create());
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "",
                List.of(monitor, vibro, discovered), List.of(), List.of(application), List.of(), Map.of(),
                monitor.id(), System.currentTimeMillis());

        PipeWireRouteManager.Plan plan = new PipeWireRouteManager().plan(compiled, snapshot);
        String source = DynamicInstanceRegistry.stableNodeId(GENERATOR, "app:org.example.Music", "source");
        assertTrue(plan.buses().containsKey(source), "the generated instance owns its own bus");
        assertFalse(plan.buses().containsKey(GENERATOR),
                "the control-plane generator never becomes a runtime bus");
        assertFalse(plan.routes().stream().anyMatch(route -> GENERATOR.equals(route.fromBusId())),
                "the control-plane generator never carries a runtime route");

        PipeWireRouteManager.RoutePlan selected = plan.routes().stream()
                .filter(route -> route.fromBusId().equals(source) && route.sink() != null
                        && route.sink().name().equals("benq-hdmi"))
                .findFirst().orElseThrow();
        assertFalse(selected.muted(), "the selected output carries the application");
        assertTrue(selected.volume() > 0.0, "the selected output keeps the application level");
        assertTrue(plan.routes().stream()
                        .filter(route -> route.fromBusId().equals(source) && route.sink() != null
                                && route.sink().name().equals("alsa_output.usb"))
                        .allMatch(PipeWireRouteManager.RoutePlan::muted),
                "a newly discovered device stays silent until it is selected");
        assertNotNull(plan.streams().stream().filter(stream -> stream.busId().equals(source)).findFirst().orElse(null),
                "the application stream joins the bus of its generated instance");
    }

    @Test void theSystemMonitorFeedsOnlyTheSystemInputBus() throws IOException {
        AudioGraph graph = graph();
        AudioNode application = new AudioNode(90, "pw-play", "Music", "Stream/Output/Audio", "running", "Player",
                "pw-play", "org.example.Music", 2, List.of(48000), 0.8, false, null);
        AudioNode monitor = sink(70, "benq-hdmi", "Monitor");
        AudioNode systemInput = sink(73, PipeWireBackend.SYSTEM_MIXER_SINK_NAME, "System input");
        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(graph,
                Map.of(GENERATOR, List.of(DynamicInstanceRegistry.application(application),
                        DynamicInstanceRegistry.systemInput())));
        Map<String, Object> compiled = GraphPipeWireCompiler.compile(reconciled, DefaultConfig.create());
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "",
                List.of(monitor, systemInput), List.of(), List.of(application), List.of(), Map.of(),
                monitor.id(), System.currentTimeMillis());
        PipeWireRouteManager.Plan plan = new PipeWireRouteManager().plan(compiled, snapshot);

        String systemSource = DynamicInstanceRegistry.stableNodeId(GENERATOR, "app:audiomatrix-system-input", "source");
        String applicationSource = DynamicInstanceRegistry.stableNodeId(GENERATOR, "app:org.example.Music", "source");
        PipeWireRouteTypes.BusPlan systemBus = plan.buses().get(systemSource);
        PipeWireRouteTypes.BusPlan applicationBus = plan.buses().get(applicationSource);
        assertNotNull(systemBus, "the system input owns a bus of its own");
        assertNotNull(applicationBus, "the application owns a bus of its own");
        String busNode = PipeWireRouteStaticSupport01.busNodeName(systemSource);
        Map<String, List<String>> inputs = Map.of(busNode,
                List.of(busNode + ":playback_FL", busNode + ":playback_FR"));
        Map<String, List<String>> outputs = Map.of(PipeWireBackend.SYSTEM_MIXER_SINK_NAME,
                List.of(PipeWireBackend.SYSTEM_MIXER_SINK_NAME + ":monitor_FL",
                        PipeWireBackend.SYSTEM_MIXER_SINK_NAME + ":monitor_FR"));

        List<PipeWireRouteTypes.Link> monitorLinks = PipeWireRouteLinkBuilder.bus(systemBus, inputs, outputs);
        assertEquals(2, monitorLinks.size(), "the system monitor feeds the system input bus");
        assertTrue(monitorLinks.stream().allMatch(link ->
                link.output().startsWith(PipeWireBackend.SYSTEM_MIXER_SINK_NAME + ":")));
        assertEquals(List.of(), PipeWireRouteLinkBuilder.bus(applicationBus, inputs, outputs),
                "the system monitor is never routed into an application bus");
    }

    private static AudioGraph graph() {
        DynamicGeneratorNode applications = new DynamicGeneratorNode(GENERATOR, "Applications",
                "application:unassigned", STEREO, Map.of("registry", "applications",
                "connect_to", Map.of("node_id", MERGE, "port_id", "applications")));
        MixerNode merge = new MixerNode(MERGE, "Application Merge", STEREO,
                Map.of("applications", 1.0, "additional", 1.0));
        SwitchNode multi = new SwitchNode(SWITCH, "MultiSwitch", STEREO, List.of("monitor"),
                List.of("in->monitor"));
        DynamicGeneratorNode outputs = new DynamicGeneratorNode("dynamic-outputs", "Outputs", "device:*", STEREO,
                Map.of("registry", "outputs", "connect_to", Map.of("node_id", SWITCH, "port_id", "in")));
        List<GraphNode> nodes = new ArrayList<>(List.of(applications, merge, multi, outputs,
                new DeviceSlotNode("octine-monitor-output", "Monitor", "benq-hdmi", STEREO)));
        List<GraphConnection> edges = new ArrayList<>(List.of(
                new GraphConnection("merge-switch", MERGE, "out", SWITCH, "in", true, 1.0),
                new GraphConnection("switch-monitor", SWITCH, "monitor", "octine-monitor-output", "in", true, 1.0)));
        return new AudioGraph("route-generated", "Generated routing", nodes, edges);
    }

    private static AudioNode sink(int id, String name, String description) {
        return new AudioNode(id, name, description, "Audio/Sink", "running", "", "", "", 2,
                List.of(48000), 1.0, false, null);
    }
}
