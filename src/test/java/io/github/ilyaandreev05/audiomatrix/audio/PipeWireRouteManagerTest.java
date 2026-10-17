package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphPipeWireCompiler;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireRouteManagerTest {
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

    private static Map<String, Object> chainedBusConfig(boolean masterMuted) {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> buses = (List<Map<String, Object>>) config.get("buses");
        buses.stream().filter(bus -> "main".equals(bus.get("id"))).findFirst().orElseThrow().put("volume", 0.8);
        buses.stream().filter(bus -> "effects".equals(bus.get("id"))).findFirst().orElseThrow().put("volume", 0.6);
        @SuppressWarnings("unchecked") Map<String, Object> mixer = (Map<String, Object>) config.get("mixer");
        mixer.put("global_volume", 0.5);
        mixer.put("global_mute", masterMuted);
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", "speaker", "selector", DefaultConfig.map("kind", "name_equals", "value", "test-output"),
                "role", "output", "volume", 1.0, "mute", false));
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "main-to-effects", "from", "main", "to", "effects", "enabled", true));
        routes.add(DefaultConfig.map("id", "effects-to-output", "from", "effects", "to", "speaker", "enabled", true));
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("active_buses", List.of("main", "effects"));
        profiles.getFirst().put("active_routes", List.of("main-to-effects", "effects-to-output"));
        return config;
    }

    @Test
    void unpluggedSelectedSinkUsesExplicitFallbackAndReturnsWithoutImplicitDefault() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "effects-haptic", "from", "music", "to", "haptic-device",
                "fallback", "safe-usb", "enabled", true));
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", "haptic-device", "selector", DefaultConfig.map("kind", "name_equals", "value", "family-haptic"),
                "role", "haptic_output", "volume", 1.0, "mute", false));
        devices.add(DefaultConfig.map("id", "safe-usb", "selector", DefaultConfig.map("kind", "name_equals", "value", "usb-speakers"),
                "role", "speaker", "volume", 1.0, "mute", false));
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("active_buses", List.of("music"));
        profiles.getFirst().put("active_routes", List.of("effects-haptic"));
        AudioNode family17 = sink(50, "family-haptic", "Family 17h");
        AudioNode usbSpeakers = sink(51, "usb-speakers", "USB speakers");
        PipeWireBackend.Snapshot connected = snapshot(List.of(family17, usbSpeakers), family17.id());
        PipeWireRouteManager manager = new PipeWireRouteManager();

        PipeWireRouteManager.Plan selectedPlan = manager.plan(config, connected);
        assertEquals("family-haptic", selectedPlan.routes().getFirst().sink().name());

        PipeWireBackend.Snapshot unplugged = snapshot(List.of(usbSpeakers), usbSpeakers.id());
        PipeWireRouteManager.Plan fallbackPlan = manager.plan(config, unplugged);

        assertEquals("usb-speakers", fallbackPlan.routes().getFirst().sink().name());
        assertTrue(fallbackPlan.warnings().stream().anyMatch(warning -> warning.contains("using fallback output safe-usb")));

        routes.getLast().put("fallback", "missing-safe-output");
        AudioNode other = sink(52, "other-output", "HDMI output");
        PipeWireBackend.Snapshot noConfiguredOutputs = snapshot(List.of(other), other.id());
        PipeWireRouteManager.Plan unavailablePlan = manager.plan(config, noConfiguredOutputs);

        assertTrue(unavailablePlan.routes().isEmpty());
        assertTrue(unavailablePlan.warnings().stream().anyMatch(warning -> warning.contains("has no available output or fallback")));

        routes.getLast().put("fallback", "safe-usb");
        PipeWireRouteManager.Plan returnedPlan = manager.plan(config, connected);
        assertEquals("family-haptic", returnedPlan.routes().getFirst().sink().name());
    }

    @Test
    void hapticModeKeepsOnlyEffectsBusRoutesToExplicitHapticOutput() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "effects-haptic", "from", "effects", "to", "haptic-device", "enabled", true));
        routes.add(DefaultConfig.map("id", "music-speakers", "from", "music", "to", "speaker-device", "enabled", true));
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", "haptic-device", "selector", DefaultConfig.map("kind", "name_equals", "value", "analog-haptic"),
                "role", "haptic_output", "volume", 1.0, "mute", false));
        devices.add(DefaultConfig.map("id", "speaker-device", "selector", DefaultConfig.map("kind", "name_equals", "value", "usb-speakers"),
                "role", "speaker", "volume", 1.0, "mute", false));
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("mode", "haptic");
        profiles.getFirst().put("active_buses", List.of("effects", "music"));
        profiles.getFirst().put("active_routes", List.of("effects-haptic", "music-speakers"));
        AudioNode haptic = sink(41, "analog-haptic", "Family 17h");
        AudioNode speakers = sink(42, "usb-speakers", "USB speakers");
        PipeWireBackend.Snapshot snapshot = snapshot(List.of(haptic, speakers), haptic.id());

        PipeWireRouteManager.Plan plan = new PipeWireRouteManager().plan(config, snapshot);

        assertEquals(List.of("effects-haptic"), plan.routes().stream().map(PipeWireRouteManager.RoutePlan::id).toList());
        assertTrue(plan.warnings().stream().anyMatch(warning -> warning.contains("music-speakers skipped")));
    }

    @Test
    void hapticModeRejectsMusicThatIsRoutedIntoTheEffectsBus() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "music-leak", "from", "music", "to", "effects", "enabled", true));
        routes.add(DefaultConfig.map("id", "effects-out", "from", "effects", "to", "haptic-device", "enabled", true));
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", "haptic-device", "selector", DefaultConfig.map("kind", "name_equals", "value", "haptic-sink"),
                "role", "haptic_output", "volume", 1.0, "mute", false));
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("mode", "haptic");
        profiles.getFirst().put("active_buses", List.of("music", "effects"));
        profiles.getFirst().put("active_routes", List.of("music-leak", "effects-out"));
        AudioNode sink = sink(46, "haptic-sink", "Haptic line out");

        PipeWireRouteManager.Plan plan = new PipeWireRouteManager().plan(config, snapshot(List.of(sink), sink.id()));

        assertTrue(plan.routes().isEmpty());
        assertTrue(plan.warnings().stream().anyMatch(warning -> warning.contains("originate only from an effects bus")));
    }

    @Test
    void hapticModeAllowsAnEffectsPathThroughAnIntermediateBus() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> builtInBuses = (List<Map<String, Object>>) config.get("buses");
        List<Map<String, Object>> buses = new ArrayList<>(builtInBuses);
        buses.add(DefaultConfig.map("id", "effects-stage", "name", "Effects stage", "role", "general", "channels", "stereo", "volume", 1.0, "mute", false));
        config.put("buses", buses);
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "effects-stage-link", "from", "effects", "to", "effects-stage", "enabled", true));
        routes.add(DefaultConfig.map("id", "effects-out", "from", "effects-stage", "to", "haptic-device", "enabled", true));
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", "haptic-device", "selector", DefaultConfig.map("kind", "name_equals", "value", "haptic-sink"),
                "role", "haptic_output", "volume", 1.0, "mute", false));
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("mode", "haptic");
        profiles.getFirst().put("active_buses", List.of("effects", "effects-stage"));
        profiles.getFirst().put("active_routes", List.of("effects-stage-link", "effects-out"));
        AudioNode sink = sink(47, "haptic-sink", "Haptic line out");

        PipeWireRouteManager.Plan plan = new PipeWireRouteManager().plan(config, snapshot(List.of(sink), sink.id()));

        assertEquals(List.of("effects-out", "effects-stage-link"), plan.routes().stream().map(PipeWireRouteManager.RoutePlan::id).sorted().toList());
    }

    @Test
    void specializedModeSkipsOutputsWithNoAssignedRoleAndHybridKeepsThem() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "effects-out", "from", "effects", "to", "plain-device", "enabled", true));
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", "plain-device", "selector", DefaultConfig.map("kind", "name_equals", "value", "plain-sink"),
                "role", "output", "volume", 1.0, "mute", false));
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("active_buses", List.of("effects"));
        profiles.getFirst().put("active_routes", List.of("effects-out"));
        AudioNode sink = sink(43, "plain-sink", "Line out");
        PipeWireBackend.Snapshot snapshot = snapshot(List.of(sink), sink.id());
        PipeWireRouteManager manager = new PipeWireRouteManager();

        profiles.getFirst().put("mode", "haptic");
        PipeWireRouteManager.Plan hapticPlan = manager.plan(config, snapshot);
        assertTrue(hapticPlan.routes().isEmpty());
        assertTrue(hapticPlan.warnings().stream().anyMatch(warning -> warning.contains("haptic_output")));
        assertFalse(manager.reportAppliedPlan(hapticPlan).warnings().isEmpty());

        profiles.getFirst().put("mode", "hybrid");
        PipeWireRouteManager.Plan hybridPlan = manager.plan(config, snapshot);
        assertEquals(1, hybridPlan.routes().size());
        assertTrue(manager.reportAppliedPlan(hybridPlan).warnings().isEmpty());
    }

    @Test
    void subwooferModeRequiresItsBusAndOutputRolesAndWarnsWhenCrossoverIsMissing() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "sub-out", "from", "subwoofer", "to", "sub-device", "enabled", true));
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", "sub-device", "selector", DefaultConfig.map("kind", "name_equals", "value", "sub-sink"),
                "role", "subwoofer", "volume", 1.0, "mute", false));
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("mode", "subwoofer");
        profiles.getFirst().put("active_buses", List.of("subwoofer"));
        profiles.getFirst().put("active_routes", List.of("sub-out"));
        AudioNode sink = sink(45, "sub-sink", "Bass output");

        PipeWireRouteManager.Plan plan = new PipeWireRouteManager().plan(config, snapshot(List.of(sink), sink.id()));

        assertEquals(List.of("sub-out"), plan.routes().stream().map(PipeWireRouteManager.RoutePlan::id).toList());
        assertTrue(plan.warnings().stream().anyMatch(warning -> warning.contains("no low_pass")));
    }

    @Test
    void subwooferModeRecognizesLowPassOnTheMusicToSubwooferBranch() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "music-bass-branch", "from", "music", "to", "subwoofer", "enabled", true,
                "filters_ref", "dsp.route_chains.subwoofer_low_pass"));
        routes.add(DefaultConfig.map("id", "sub-out", "from", "subwoofer", "to", "sub-device", "enabled", true));
        @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) config.get("dsp");
        @SuppressWarnings("unchecked") Map<String, Object> chains = (Map<String, Object>) dsp.get("route_chains");
        chains.put("subwoofer_low_pass", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "low_pass", "frequency_hz", 120.0, "enabled", true))));
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", "sub-device", "selector", DefaultConfig.map("kind", "name_equals", "value", "sub-sink"),
                "role", "subwoofer", "volume", 1.0, "mute", false));
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("mode", "subwoofer");
        profiles.getFirst().put("active_buses", List.of("music", "subwoofer"));
        profiles.getFirst().put("active_routes", List.of("music-bass-branch", "sub-out"));
        AudioNode sink = sink(48, "sub-sink", "Bass output");

        PipeWireRouteManager.Plan plan = new PipeWireRouteManager().plan(config, snapshot(List.of(sink), sink.id()));

        assertEquals(2, plan.routes().size());
        assertTrue(plan.warnings().stream().noneMatch(warning -> warning.contains("no low_pass")));
    }

    @Test
    void multiOutputModeWarnsWhenRoutesOnlyReachOneDistinctOutput() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "music-out", "from", "music", "to", "system_default", "enabled", true));
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("mode", "multi-output");
        profiles.getFirst().put("active_buses", List.of("music"));
        profiles.getFirst().put("active_routes", List.of("music-out"));
        AudioNode sink = sink(44, "default-sink", "Default output");

        PipeWireRouteManager.Plan plan = new PipeWireRouteManager().plan(config, snapshot(List.of(sink), sink.id()));

        assertEquals(1, plan.routes().size());
        assertTrue(plan.warnings().stream().anyMatch(warning -> warning.contains("at least two distinct outputs")));
    }

    @Test
    void unmatchedEventSoundStudioStreamNeverUsesTheGeneralCatchAllButExactRuleCanRouteIt() throws IOException {
        Map<String,Object> config = DefaultConfig.create();
        config.put("graph_default_bus", "main");
        @SuppressWarnings("unchecked") List<Map<String,Object>> routes = (List<Map<String,Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "main-out", "from", "main", "to", "system_default", "enabled", true));
        @SuppressWarnings("unchecked") List<Map<String,Object>> profiles = (List<Map<String,Object>>) config.get("profiles");
        profiles.getFirst().put("active_buses", List.of("main"));
        profiles.getFirst().put("active_routes", List.of("main-out"));
        AudioNode sink = sink(70, "default-sink", "Default output");
        AudioNode eventStream = new AudioNode(71, "event-stream", "Event effects", "Stream/Output/Audio", "running",
                "Event Sound Studio", "pw-cat", PipeWireRouteManager.EVENT_SOUND_STUDIO_APPLICATION_ID,
                2, List.of(48000), 1.0, false, null);
        PipeWireBackend.Snapshot snapshot = snapshotWithStreams(List.of(sink), List.of(eventStream), sink.id());
        PipeWireRouteManager manager = new PipeWireRouteManager();

        PipeWireRouteManager.Plan isolated = manager.plan(config, snapshot);
        assertTrue(isolated.streams().isEmpty(), "an unmatched event stream must remain on its isolated virtual sink");
        assertEquals(List.of("main-out"), isolated.routes().stream().map(PipeWireRouteManager.RoutePlan::id).toList());

        @SuppressWarnings("unchecked") List<Map<String,Object>> applications = (List<Map<String,Object>>) config.get("applications");
        applications.add(DefaultConfig.map("id", "event-effects", "name", "Event effects", "match",
                DefaultConfig.map("app_id", PipeWireRouteManager.EVENT_SOUND_STUDIO_APPLICATION_ID), "bus", "main",
                "volume", 1.0, "mute", false));
        PipeWireRouteManager.Plan explicitlyRouted = manager.plan(config, snapshot);
        assertEquals(List.of("event-stream"), explicitlyRouted.routedStreamNames());
    }

    private static AudioNode sink(int id, String name, String description) {
        return new AudioNode(id, name, description, "Audio/Sink", "running", "", "", "", 2,
                List.of(48000), 1.0, false, null);
    }

    private static PipeWireBackend.Snapshot snapshot(List<AudioNode> sinks, int defaultSinkId) {
        return new PipeWireBackend.Snapshot(true, "", sinks, List.of(), List.of(), List.of(), Map.of(), defaultSinkId,
                System.currentTimeMillis());
    }

    private static PipeWireBackend.Snapshot snapshotWithStreams(List<AudioNode> sinks, List<AudioNode> streams, int defaultSinkId) {
        return new PipeWireBackend.Snapshot(true, "", sinks, List.of(), streams, List.of(), Map.of(), defaultSinkId,
                System.currentTimeMillis());
    }

    @Test
    void busEqIsComposedBeforeRouteEqInTheActivePath() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> buses = (List<Map<String, Object>>) config.get("buses");
        buses.stream().filter(bus -> "music".equals(bus.get("id"))).findFirst().orElseThrow()
                .put("filters_ref", "dsp.route_chains.bus_eq");
        @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) config.get("dsp");
        @SuppressWarnings("unchecked") Map<String, Object> chains = (Map<String, Object>) dsp.get("route_chains");
        chains.put("bus_eq", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "low_pass", "frequency_hz", 120.0, "enabled", true))));
        chains.put("route_eq", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "notch", "frequency_hz", 80.0, "gain_db", -2.0, "q", 2.0, "enabled", true))));

        List<Map<String, Object>> filters = new PipeWireRouteManager().routeFilters(
                DefaultConfig.map("id", "music-out", "from", "music", "filters_ref", "dsp.route_chains.route_eq"),
                config);
        assertEquals(List.of("low_pass", "notch"), filters.stream().map(filter -> filter.get("type")).toList());
    }

    @Test
    void sameBusAndRouteChainIsAppliedOnlyOnce() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> buses = (List<Map<String, Object>>) config.get("buses");
        buses.stream().filter(bus -> "music".equals(bus.get("id"))).findFirst().orElseThrow()
                .put("filters_ref", "dsp.route_chains.shared");
        @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) config.get("dsp");
        @SuppressWarnings("unchecked") Map<String, Object> chains = (Map<String, Object>) dsp.get("route_chains");
        chains.put("shared", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "notch", "frequency_hz", 80.0, "gain_db", -2.0, "q", 2.0, "enabled", true))));

        List<Map<String, Object>> filters = new PipeWireRouteManager().routeFilters(
                DefaultConfig.map("id", "music-out", "from", "music", "filters_ref", "dsp.route_chains.shared"),
                config);
        assertEquals(1, filters.size());
        assertEquals("notch", filters.getFirst().get("type"));
    }

    @Test
    void routePolarityAddsAnInvertFilterAfterOtherDsp() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) config.get("dsp");
        @SuppressWarnings("unchecked") Map<String, Object> chains = (Map<String, Object>) dsp.get("route_chains");
        chains.put("route_eq", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "notch", "frequency_hz", 80.0, "gain_db", -2.0, "q", 2.0, "enabled", true))));

        List<Map<String, Object>> filters = new PipeWireRouteManager().routeFilters(
                DefaultConfig.map("id", "music-out", "from", "music", "filters_ref", "dsp.route_chains.route_eq", "polarity", "invert"),
                config);
        assertEquals("notch", filters.get(0).get("type"));
        assertEquals("phase_invert", filters.get(1).get("type"));
    }

    @Test
    void routePolarityCanInvertOnlyLeftChannel() throws IOException {
        List<Map<String, Object>> filters = new PipeWireRouteManager().routeFilters(
                DefaultConfig.map("id", "music-out", "from", "music", "polarity", "invert_left"),
                DefaultConfig.create());
        assertEquals(Map.of("type", "phase_invert", "enabled", true, "channels", List.of("FL")), filters.getFirst());
    }

    @Test
    void globalAndDeviceCorrectionComposeIntoOnePhysicalOutputChain() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) config.get("dsp");
        dsp.put("global_eq", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "notch", "frequency_hz", 90.0, "gain_db", -2.0, "q", 2.0, "enabled", true))));
        @SuppressWarnings("unchecked") Map<String, Object> deviceEq = (Map<String, Object>) dsp.get("devices");
        deviceEq.put("dac", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "low_pass", "frequency_hz", 12000.0, "enabled", true))));
        @SuppressWarnings("unchecked") Map<String, Object> chains = (Map<String, Object>) dsp.get("route_chains");
        chains.put("output_notch", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "notch", "frequency_hz", 70.0, "gain_db", -1.5, "q", 2.0, "enabled", true))));
        AudioNode sink = new AudioNode(27, "alsa_output.usb-DAC", "USB DAC", "Audio/Sink", "running",
                "", "", "", 2, List.of(48000), 1.0, false, null);
        List<Map<String, Object>> devices = List.of(DefaultConfig.map("id", "dac",
                "selector", DefaultConfig.map("kind", "description_contains", "value", "USB DAC"),
                "filters_ref", "dsp.route_chains.output_notch"));

        List<Map<String, Object>> filters = new PipeWireRouteManager().outputFilters(sink, devices, config);
        assertEquals(List.of("notch", "low_pass", "notch"), filters.stream().map(filter -> filter.get("type")).toList());
        assertEquals(List.of(), new PipeWireRouteManager().routeFilters(
                DefaultConfig.map("id", "out", "from", "main"), config));
    }

    @Test
    void physicalOutputLimiterIsASeparatePostMixChain() {
        Map<String, Object> config = DefaultConfig.create();
        PipeWireRouteManager manager = new PipeWireRouteManager();
        List<Map<String, Object>> filters = manager.outputLimiterFilters(config);
        assertEquals(List.of("limiter"), filters.stream().map(filter -> filter.get("type")).toList());
        assertEquals(-1.0, filters.getFirst().get("ceiling_db"));
        assertEquals(5.0, filters.getFirst().get("lookahead_ms"));
        assertEquals(50.0, filters.getFirst().get("release_ms"));
    }

    @Test
    void headroomEstimateUsesMasterBusRouteGainAndLimiterCeiling() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "music-out", "from", "music", "to", "system_default",
                "gain_db", 12.041199826, "volume", 0.5, "channel_map", "mono_sum", "enabled", true));
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("active_buses", List.of("music"));
        profiles.getFirst().put("active_routes", List.of("music-out"));
        AudioNode sink = new AudioNode(31, "analog-output", "Analog output", "Audio/Sink", "running",
                "", "", "", 2, List.of(48000), 1.0, false, null);
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(sink), List.of(),
                List.of(), List.of(), Map.of(), sink.id(), System.currentTimeMillis());

        PipeWireRouteManager.HeadroomEstimate estimate = new PipeWireRouteManager().estimateHeadroom(config, snapshot, sink);

        assertTrue(estimate.available());
        assertEquals(20 * Math.log10(3.2), estimate.preLimiterPeakDbfs(), 1e-6);
        assertEquals(-1.0, estimate.limiterCeilingDbfs());
        assertEquals(-1.0, estimate.postLimiterPeakDbfs());
        assertEquals(1.0 + 20 * Math.log10(3.2), estimate.worstCaseReductionDb(), 1e-6);
    }

    @Test
    void eqGainBoundIncludesRouteAndPostMixOutputBoostsForAbMatching() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) config.get("dsp");
        dsp.put("global_eq", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "high_shelf", "frequency_hz", 5000.0, "gain_db", 1.0, "q", 0.7, "enabled", true))));
        @SuppressWarnings("unchecked") Map<String, Object> chains = (Map<String, Object>) dsp.get("route_chains");
        chains.put("route_boost", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "peaking", "frequency_hz", 120.0, "gain_db", 2.0, "q", 1.0, "enabled", true))));
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "music-out", "from", "music", "to", "system_default",
                "gain_db", 0.0, "volume", 1.0, "filters_ref", "dsp.route_chains.route_boost", "enabled", true));
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("active_buses", List.of("music"));
        profiles.getFirst().put("active_routes", List.of("music-out"));
        AudioNode sink = new AudioNode(31, "analog-output", "Analog output", "Audio/Sink", "running",
                "", "", "", 2, List.of(48000), 1.0, false, null);
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(sink), List.of(),
                List.of(), List.of(), Map.of(), sink.id(), System.currentTimeMillis());

        assertEquals(3.0, new PipeWireRouteManager().maximumEqBoostDb(config, snapshot, sink), 1e-9);
    }

    @Test
    void applicationEqResolvesItsSharedChainAndSkipsDisabledFilters() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) config.get("dsp");
        @SuppressWarnings("unchecked") Map<String, Object> chains = (Map<String, Object>) dsp.get("route_chains");
        chains.put("browser_eq", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "low_pass", "frequency_hz", 120.0, "enabled", true),
                DefaultConfig.map("type", "notch", "frequency_hz", 80.0, "gain_db", -2.0, "q", 2.0, "enabled", false))));

        List<Map<String, Object>> filters = new PipeWireRouteManager().applicationFilters(
                DefaultConfig.map("id", "browser", "filters_ref", "dsp.route_chains.browser_eq"), config);
        assertEquals(List.of("low_pass"), filters.stream().map(filter -> filter.get("type")).toList());
    }

    @Test
    void applicationEqRejectsPositiveGainUntilLimiterExists() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) config.get("dsp");
        @SuppressWarnings("unchecked") Map<String, Object> limiter = (Map<String, Object>) dsp.get("limiter");
        limiter.put("enabled", false);
        @SuppressWarnings("unchecked") Map<String, Object> chains = (Map<String, Object>) dsp.get("route_chains");
        chains.put("hot_eq", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "peaking", "frequency_hz", 80.0, "gain_db", 2.0, "q", 1.0, "enabled", true))));

        IOException error = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () ->
                new PipeWireRouteManager().applicationFilters(
                        DefaultConfig.map("id", "browser", "filters_ref", "dsp.route_chains.hot_eq"), config));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("Positive EQ gain"));
    }

    @Test
    void stereoLinksChannelsByPosition() {
        List<PipeWireRouteManager.Link> links = PipeWireRouteManager.channelLinks(
                List.of("music:monitor_FL", "music:monitor_FR"),
                List.of("route:capture_FL", "route:capture_FR"), "stereo", true);

        assertEquals(List.of(
                new PipeWireRouteManager.Link("music:monitor_FL", "route:capture_FL"),
                new PipeWireRouteManager.Link("music:monitor_FR", "route:capture_FR")), links);
    }

    @Test
    void monoSumFeedsBothStereoChannelsIntoTheMonoRoute() {
        List<PipeWireRouteManager.Link> links = PipeWireRouteManager.channelLinks(
                List.of("music:monitor_FL", "music:monitor_FR"),
                List.of("route:capture_MONO"), "mono_sum", true);

        assertEquals(List.of(
                new PipeWireRouteManager.Link("music:monitor_FL", "route:capture_MONO"),
                new PipeWireRouteManager.Link("music:monitor_FR", "route:capture_MONO")), links);
    }

    @Test
    void monoRouteFeedsBothChannelsOfStereoDestination() {
        List<PipeWireRouteManager.Link> links = PipeWireRouteManager.channelLinks(
                List.of("route:playback_MONO"),
                List.of("speakers:playback_FL", "speakers:playback_FR"), "mono_sum", false);

        assertEquals(List.of(
                new PipeWireRouteManager.Link("route:playback_MONO", "speakers:playback_FL"),
                new PipeWireRouteManager.Link("route:playback_MONO", "speakers:playback_FR")), links);
    }

    @Test
    void leftChannelMapSelectsOnlyTheLeftSourceChannel() {
        List<PipeWireRouteManager.Link> links = PipeWireRouteManager.channelLinks(
                List.of("music:monitor_FL", "music:monitor_FR"),
                List.of("route:capture_MONO"), "left", true);

        assertEquals(List.of(new PipeWireRouteManager.Link("music:monitor_FL", "route:capture_MONO")), links);
    }

    @Test
    void swapLrReversesTheStereoPair() {
        List<PipeWireRouteManager.Link> links = PipeWireRouteManager.channelLinks(
                List.of("route:playback_FL", "route:playback_FR"),
                List.of("speakers:playback_FL", "speakers:playback_FR"), "swap_lr", false);

        assertEquals(List.of(
                new PipeWireRouteManager.Link("route:playback_FL", "speakers:playback_FR"),
                new PipeWireRouteManager.Link("route:playback_FR", "speakers:playback_FL")), links);
    }

    @Test
    void centerAndLfeMapsSelectTheirNamedSurroundChannel() {
        List<String> outputs = List.of("music:monitor_FL", "music:monitor_FR", "music:monitor_FC", "music:monitor_LFE",
                "music:monitor_RL", "music:monitor_RR");
        List<String> monoInput = List.of("route:capture_MONO");

        assertEquals(List.of(new PipeWireRouteManager.Link("music:monitor_FC", "route:capture_MONO")),
                PipeWireRouteManager.channelLinks(outputs, monoInput, "center", true));
        assertEquals(List.of(new PipeWireRouteManager.Link("music:monitor_LFE", "route:capture_MONO")),
                PipeWireRouteManager.channelLinks(outputs, monoInput, "lfe", true));
    }

    @Test
    void rearAndSideSumMapsCombineOnlyTheirAvailablePair() {
        List<String> outputs = List.of("music:monitor_FL", "music:monitor_FR", "music:monitor_RL", "music:monitor_RR",
                "music:monitor_SL", "music:monitor_SR");
        List<String> monoInput = List.of("route:capture_MONO");

        assertEquals(List.of(new PipeWireRouteManager.Link("music:monitor_RL", "route:capture_MONO"),
                        new PipeWireRouteManager.Link("music:monitor_RR", "route:capture_MONO")),
                PipeWireRouteManager.channelLinks(outputs, monoInput, "rear_sum", true));
        assertEquals(List.of(new PipeWireRouteManager.Link("music:monitor_SL", "route:capture_MONO"),
                        new PipeWireRouteManager.Link("music:monitor_SR", "route:capture_MONO")),
                PipeWireRouteManager.channelLinks(outputs, monoInput, "side_sum", true));
    }

    @Test
    void allSumUsesEveryRecognizedInputChannelInStableOrder() {
        List<String> outputs = List.of("music:monitor_FL", "music:monitor_FR", "music:monitor_FC", "music:monitor_LFE",
                "music:monitor_RL", "music:monitor_RR", "music:monitor_RC", "music:monitor_SL", "music:monitor_SR");

        assertEquals(outputs.stream().map(source -> new PipeWireRouteManager.Link(source, "route:capture_MONO")).toList(),
                PipeWireRouteManager.channelLinks(outputs, List.of("route:capture_MONO"), "all_sum", true));
    }

    @Test
    void customMatrixPlanUsesNamedOutputPositionsAndContributesToHeadroom() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "matrix-route", "from", "music", "to", "system_default", "enabled", true,
                "channel_map", "stereo", "channel_matrix", DefaultConfig.map("inputs", List.of("FL", "FR"),
                        "outputs", List.of("FL"), "coefficients", List.of(List.of(0.5, 0.5)))));
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("active_routes", List.of("matrix-route"));
        profiles.getFirst().put("active_buses", List.of("music"));
        AudioNode output = sink(90, "test-output", "Test output");
        PipeWireBackend.Snapshot snapshot = snapshot(List.of(output), output.id());

        PipeWireRouteManager manager = new PipeWireRouteManager();
        PipeWireRouteManager.Plan plan = manager.plan(config, snapshot);
        PipeWireRouteManager.RoutePlan route = plan.routes().getFirst();
        PipeWireRouteManager.HeadroomEstimate headroom = manager.estimateHeadroom(config, snapshot, output);

        assertEquals(List.of("FL"), route.positions());
        assertEquals(List.of("FL", "FR"), route.channelMatrix().inputs());
        assertEquals(1.0, route.channelMatrix().maximumRowGain(), 1e-9);
        assertTrue(headroom.available());
        assertTrue(PipeWireRouteManager.positionLinks(List.of("route:playback_FL", "route:playback_FR"),
                List.of("sink:input_FR", "sink:input_FL")).contains(new PipeWireRouteManager.Link("route:playback_FL", "sink:input_FL")));
    }
}
