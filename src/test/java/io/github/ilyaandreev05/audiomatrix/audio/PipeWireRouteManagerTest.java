package io.github.ilyaandreev05.audiomatrix.audio;
import org.junit.jupiter.api.Test;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphPipeWireCompiler;
import io.github.ilyaandreev05.audiomatrix.graph.SwitchNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireRouteManagerTest extends PipeWireRouteManagerTestSupport {
@Test
    void graphPeakLimiterRunsInOutputStageAfterTheRouteFilterChain() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", "haptic-output", "selector", DefaultConfig.map("kind", "name_equals", "value", "test-output"),
                "role", "haptic_output", "volume", 1.0, "mute", false));
        List<String> stereo = List.of("FL", "FR");
        SourceNode source = new SourceNode("source", "Applications", "application:unassigned", stereo);
        FilterNode lowPass = new FilterNode("low-pass", "Bass filter", stereo, "low_pass", true,
                Map.of("frequency_hz", 100.0, "slope_db_oct", 24.0));
        FilterNode limiter = new FilterNode("limiter", "Vibration limiter", stereo, "limiter", true,
                Map.of("ceiling_db", -2.0, "lookahead_ms", 5.0, "release_ms", 80.0));
        OutputNode output = new OutputNode("output", "Vibrodynamic", "haptic-output", stereo);
        AudioGraph graph = new AudioGraph("route-haptic", "Haptic branch", List.of(source, lowPass, limiter, output), List.of(
                new GraphConnection("source-low-pass", "source", "out", "low-pass", "in", true, 1.0),
                new GraphConnection("low-pass-limiter", "low-pass", "out", "limiter", "in", true, 1.0),
                new GraphConnection("limiter-output", "limiter", "out", "output", "in", true, 1.0)));
        Map<String, Object> runtime = GraphPipeWireCompiler.compile(graph, config);
        AudioNode outputSink = sink(61, "test-output", "Test output");
        PipeWireRouteManager manager = new PipeWireRouteManager();

        PipeWireRouteManager.Plan plan = manager.plan(runtime, snapshot(List.of(outputSink), outputSink.id()));
        List<Map<String, Object>> outputLimiter = manager.outputLimiterFilters(runtime, "haptic-output");

        assertEquals(1, plan.routes().size());
        assertTrue(plan.outputs().containsKey("test-output"));
        assertEquals(-2.0, outputLimiter.getFirst().get("ceiling_db"));
        assertEquals(5.0, outputLimiter.getFirst().get("lookahead_ms"));
        assertEquals(80.0, outputLimiter.getFirst().get("release_ms"));

        FilterNode updatedLimiter = new FilterNode("limiter", "Vibration limiter", stereo, "limiter", true,
                Map.of("ceiling_db", -3.0, "lookahead_ms", 5.0, "release_ms", 80.0));
        AudioGraph updatedGraph = new AudioGraph("route-haptic", "Haptic branch", List.of(source, lowPass, updatedLimiter, output), graph.connections());
        PipeWireRouteManager.Plan updatedPlan = manager.plan(GraphPipeWireCompiler.compile(updatedGraph, config),
                snapshot(List.of(outputSink), outputSink.id()));
        assertFalse(PipeWireRouteManager.sameRoutingTopology(plan, updatedPlan));
    }

@Test
    void masterLevelIsAppliedOnceAtEachPhysicalEndpointAcrossChainedBuses() throws IOException {
        Map<String, Object> config = chainedBusConfig(false);
        AudioNode output = sink(39, "test-output", "Test output");
        PipeWireBackend.Snapshot snapshot = snapshot(List.of(output), output.id());
        PipeWireRouteManager manager = new PipeWireRouteManager();

        PipeWireRouteManager.Plan plan = manager.plan(config, snapshot);
        PipeWireRouteManager.RoutePlan internal = plan.routes().stream().filter(route -> route.sink() == null).findFirst().orElseThrow();
        PipeWireRouteManager.RoutePlan endpoint = plan.routes().stream().filter(route -> route.sink() != null).findFirst().orElseThrow();
        PipeWireRouteManager.HeadroomEstimate headroom = manager.estimateHeadroom(config, snapshot, output);

        assertEquals(1.0, PipeWireRouteManager.effectiveRoutePlaybackVolume(plan, internal), 1e-9);
        assertEquals(0.5, PipeWireRouteManager.effectiveRoutePlaybackVolume(plan, endpoint), 1e-9);
        assertFalse(PipeWireRouteManager.effectiveRoutePlaybackMuted(plan, internal));
        assertTrue(headroom.available());
        assertEquals(20 * Math.log10(0.8 * 0.6 * 0.5), headroom.preLimiterPeakDbfs(), 1e-6);
    }

@Test
    void masterMuteTargetsOnlyFinalOutputRoutesAndLevelChangesKeepGraphTopology() throws IOException {
        Map<String, Object> config = chainedBusConfig(true);
        AudioNode output = sink(40, "test-output", "Test output");
        PipeWireBackend.Snapshot snapshot = snapshot(List.of(output), output.id());
        PipeWireRouteManager manager = new PipeWireRouteManager();
        PipeWireRouteManager.Plan muted = manager.plan(config, snapshot);
        PipeWireRouteManager.RoutePlan internal = muted.routes().stream().filter(route -> route.sink() == null).findFirst().orElseThrow();
        PipeWireRouteManager.RoutePlan endpoint = muted.routes().stream().filter(route -> route.sink() != null).findFirst().orElseThrow();

        assertFalse(PipeWireRouteManager.effectiveRoutePlaybackMuted(muted, internal));
        assertTrue(PipeWireRouteManager.effectiveRoutePlaybackMuted(muted, endpoint));
        assertFalse(manager.estimateHeadroom(config, snapshot, output).available());

        ((Map<String, Object>) config.get("mixer")).put("global_volume", 0.25);
        PipeWireRouteManager.Plan withNewMaster = manager.plan(config, snapshot);
        assertTrue(PipeWireRouteManager.sameRoutingTopology(muted, withNewMaster));
        assertEquals(0.25, PipeWireRouteManager.effectiveRoutePlaybackVolume(withNewMaster,
                withNewMaster.routes().stream().filter(route -> route.sink() != null).findFirst().orElseThrow()), 1e-9);
    }

    @Test
    void routesOnlyTheExactCapturedNodeThroughItsExplicitGraphSourceIdentity() throws IOException {
        Map<String, Object> config = chainedBusConfig(false);
        String parentApplicationId = "org.example.Chrome";
        String captureName = CapturedStreamBinding.NODE_PREFIX + "tab-session";
        @SuppressWarnings("unchecked") List<Map<String, Object>> applications =
                (List<Map<String, Object>>) config.get("applications");
        applications.add(DefaultConfig.map("id", "chrome-parent-rule",
                "match", DefaultConfig.map("app_id", parentApplicationId, "process", "chrome"),
                "bus", "main", "volume", 0.8, "mute", false));
        AudioNode parent = audioStream(501, "chrome-parent", "Chrome", "chrome", parentApplicationId);
        AudioNode captured = audioStream(502, captureName, "Captured tab", "audio-matrix-chrome", "capture-host");
        AudioNode nearMatch = audioStream(503, captureName + "-other", "Other capture", "audio-matrix-chrome", "capture-host");
        CapturedStreamBinding binding = new CapturedStreamBinding(captureName, "generated-tab-source",
                "chrome-parent-rule", parentApplicationId, "chrome", "browser:session", 0.5, true);
        AudioNode output = sink(500, "test-output", "Test output");
        PipeWireBackend.Snapshot snapshot = snapshotWithStreams(List.of(output), List.of(parent, captured, nearMatch), output.id());

        PipeWireRouteManager.Plan plan = new PipeWireRouteManager().plan(config, snapshot, Map.of(captureName, binding));
        PipeWireRouteManager.StreamPlan capturedPlan = plan.streams().stream()
                .filter(stream -> captureName.equals(stream.node().name())).findFirst().orElseThrow();

        assertEquals("generated-tab-source", capturedPlan.sourceNodeId());
        assertEquals("browser:session", capturedPlan.instanceKey());
        assertFalse(plan.streams().stream().anyMatch(stream -> nearMatch.name().equals(stream.node().name())));
        PipeWireRouteManager.RoutePlan endpoint = plan.routes().stream()
                .filter(route -> route.sink() != null).findFirst().orElseThrow();
        assertEquals(0.5, PipeWireRouteManager.effectiveRoutePlaybackVolume(plan, endpoint), 1e-9,
                "Application and tab gain belong only to the captured stream control");
    }

    private static AudioNode audioStream(int id, String name, String appName, String processName, String appId) {
        return new AudioNode(id, name, appName, "Stream/Output/Audio", "running", appName, processName,
                appId, 2, List.of(48000), 0.0, false, null);
    }

@Test
    void switchingGraphOutputUpdatesLiveRouteLevelsWithoutChangingPipeWireTopology() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", "monitor", "selector", DefaultConfig.map("kind", "name_equals", "value", "test-monitor"),
                "role", "speaker", "volume", 1.0, "mute", false));
        devices.add(DefaultConfig.map("id", "aux", "selector", DefaultConfig.map("kind", "name_equals", "value", "test-aux"),
                "role", "headphones", "volume", 1.0, "mute", false));
        List<String> stereo = List.of("FL", "FR");
        SourceNode source = new SourceNode("source", "Applications", "application:unassigned", stereo);
        SwitchNode toMonitor = new SwitchNode("switch", "Output selector", stereo,
                List.of("to-monitor", "to-aux"), List.of("in->to-monitor"));
        OutputNode monitor = new OutputNode("monitor", "Monitor", "monitor", stereo);
        OutputNode aux = new OutputNode("aux", "AUX", "aux", stereo);
        List<GraphConnection> connections = List.of(
                new GraphConnection("source-switch", "source", "out", "switch", "in", true, 1.0),
                new GraphConnection("switch-monitor", "switch", "to-monitor", "monitor", "in", true, 1.0),
                new GraphConnection("switch-aux", "switch", "to-aux", "aux", "in", true, 1.0));
        AudioGraph monitorGraph = new AudioGraph("switching-route", "Switching route",
                List.of(source, toMonitor, monitor, aux), connections);
        SwitchNode toAux = new SwitchNode("switch", "Output selector", stereo,
                List.of("to-monitor", "to-aux"), List.of("in->to-aux"));
        AudioGraph auxGraph = new AudioGraph("switching-route", "Switching route",
                List.of(source, toAux, monitor, aux), connections);

        AudioNode monitorSink = sink(71, "test-monitor", "Monitor");
        AudioNode auxSink = sink(72, "test-aux", "AUX");
        PipeWireBackend.Snapshot snapshot = snapshot(List.of(monitorSink, auxSink), monitorSink.id());
        PipeWireRouteManager manager = new PipeWireRouteManager();
        PipeWireRouteManager.Plan monitorPlan = manager.plan(GraphPipeWireCompiler.compile(monitorGraph, config), snapshot);
        PipeWireRouteManager.Plan auxPlan = manager.plan(GraphPipeWireCompiler.compile(auxGraph, config), snapshot);

        assertTrue(PipeWireRouteManager.sameRoutingTopology(monitorPlan, auxPlan));
        assertEquals(monitorPlan.routes().stream().map(PipeWireRouteManager.RoutePlan::id).toList(),
                auxPlan.routes().stream().map(PipeWireRouteManager.RoutePlan::id).toList());
        assertEquals(2, monitorPlan.routes().size());
        assertEquals(1.0, monitorPlan.routes().stream().filter(route -> route.sink() != null && route.sink().id() == monitorSink.id())
                .findFirst().orElseThrow().volume());
        assertEquals(0.0, monitorPlan.routes().stream().filter(route -> route.sink() != null && route.sink().id() == auxSink.id())
                .findFirst().orElseThrow().volume());
        assertEquals(0.0, auxPlan.routes().stream().filter(route -> route.sink() != null && route.sink().id() == monitorSink.id())
                .findFirst().orElseThrow().volume());
        assertEquals(1.0, auxPlan.routes().stream().filter(route -> route.sink() != null && route.sink().id() == auxSink.id())
                .findFirst().orElseThrow().volume());
        assertEquals(List.of(auxSink.name(), monitorSink.name()), PipeWireRouteManager.routeLevelUpdateOrder(auxPlan, monitorPlan)
                .stream().map(route -> route.sink().name()).toList(),
                "The old AUX path must be lowered before the new monitor path is raised");
        assertEquals(List.of(monitorSink.name(), auxSink.name()), PipeWireRouteManager.routeLevelUpdateOrder(monitorPlan, auxPlan)
                .stream().map(route -> route.sink().name()).toList());
    }

@Test
    void internalVirtualSystemInputCannotBeUsedAsAGraphPhysicalOutput() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "music-system-default", "from", "music", "to", "system_default", "enabled", true));
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("active_buses", List.of("music"));
        profiles.getFirst().put("active_routes", List.of("music-system-default"));
        AudioNode physical = sink(41, "alsa_output.usb-speakers", "USB speakers");
        AudioNode systemInput = sink(42, PipeWireBackend.SYSTEM_MIXER_SINK_NAME, "Audio Matrix System Input");

        PipeWireRouteManager.Plan plan = new PipeWireRouteManager().plan(config,
                snapshot(List.of(physical, systemInput), systemInput.id()));

        assertTrue(plan.routes().isEmpty());
        assertTrue(plan.warnings().stream().anyMatch(warning -> warning.contains("no available output")));
    }
}
