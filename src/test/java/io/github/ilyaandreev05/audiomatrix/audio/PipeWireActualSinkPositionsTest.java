package io.github.ilyaandreev05.audiomatrix.audio;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PipeWireActualSinkPositionsTest extends PipeWireRouteManagerTestSupport {
    private static AudioNode output(String positions) {
        return new AudioNode(90, "sink", "sink", "Audio/Sink", "running", "", "", "", 4,
                List.of(48000), 1, false, null, DeviceIdentity.from(Map.of("audio.position", positions), 4));
    }

    private static Map<String, Object> matrixConfig() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "rear", "from", "music", "to", "system_default", "enabled", true,
                "channel_map", "stereo", "channel_matrix", DefaultConfig.map("inputs", List.of("FL", "FR"),
                        "outputs", List.of("RL", "RR"), "coefficients", List.of(List.of(1.0, 0.0), List.of(0.0, 1.0)))));
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("active_routes", List.of("rear"));
        profiles.getFirst().put("active_buses", List.of("music"));
        return config;
    }

    @Test void matrixRequiresRealUnambiguousPositions() throws Exception {
        PipeWireRouteManager manager = new PipeWireRouteManager();
        for (String positions : List.of("[ FL FR SL SR ]", "", "[ FL FR RL RL ]")) {
            AudioNode sink = output(positions);
            var plan = manager.plan(matrixConfig(), snapshot(List.of(sink), sink.id()));
            assertTrue(plan.routes().isEmpty(), positions);
            assertFalse(plan.warnings().isEmpty(), positions);
        }
        AudioNode sink = output("[ FL FR RL RR ]");
        assertEquals(List.of("RL", "RR"), manager.plan(matrixConfig(), snapshot(List.of(sink), sink.id()))
                .routes().getFirst().positions());
    }

    @Test void outputLimiterRequiresEveryPhysicalPortAndRejectsDuplicates() throws Exception {
        AudioNode sink = output("[ FL FR SL SR ]");
        var plan = new PipeWireRouteTypes.OutputPlan(sink, List.of("FL", "FR", "SL", "SR"), List.of(),
                List.of(Map.of("type", "limiter")), "eq-in", "eq-out", "lim-in", "lim-out");
        Map<String, List<String>> outputs = Map.of("lim-out", List.of("lim-out:output_FL", "lim-out:output_FR",
                "lim-out:output_SL", "lim-out:output_SR"));
        assertThrows(IOException.class, () -> PipeWireRouteLinkBuilder.output(plan,
                Map.of("sink", List.of("sink:playback_FL", "sink:playback_FR", "sink:playback_SL")), outputs));

        assertThrows(IOException.class, () -> PipeWireRouteLinkBuilder.output(plan,
                Map.of("sink", List.of("sink:playback_FL", "sink:other_FL", "sink:playback_FR", "sink:playback_SL", "sink:playback_SR")), outputs));
        assertEquals(4, PipeWireRouteLinkBuilder.output(plan, Map.of("sink", List.of("sink:playback_FL",
                "sink:playback_FR", "sink:playback_SL", "sink:playback_SR")), outputs).size());
    }

    @Test void numericPortsUseTheActualOrderedLayout() throws Exception {
        AudioNode sink = output("[ FL FR SL SR ]");
        var plan = new PipeWireRouteTypes.OutputPlan(sink, List.of("FL", "FR", "SL", "SR"), List.of(),
                List.of(Map.of("type", "limiter")), "eq-in", "eq-out", "lim-in", "lim-out");
        var links = PipeWireRouteLinkBuilder.output(plan,
                Map.of("sink", List.of("sink:input_0", "sink:input_1", "sink:input_2", "sink:input_3")),
                Map.of("lim-out", List.of("lim-out:output_0", "lim-out:output_1", "lim-out:output_2", "lim-out:output_3")));
        assertTrue(links.contains(new PipeWireRouteTypes.Link("lim-out:output_2", "sink:input_2")));
        assertEquals(4, links.size());
    }

    @Test void ordinaryStereoMatrixWithoutMetadataIsDeferredToPortChecks() throws Exception {
        Map<String, Object> config = matrixConfig();
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.getLast().put("channel_matrix", DefaultConfig.map("inputs", List.of("FL", "FR"),
                "outputs", List.of("FL", "FR"), "coefficients", List.of(List.of(1.0, 0.0), List.of(0.0, 1.0))));
        AudioNode sink = sink(91, "stereo", "stereo");
        var planned = new PipeWireRouteManager().plan(config, snapshot(List.of(sink), sink.id()));
        assertEquals(1, planned.routes().size());
        var route = planned.routes().getFirst();
        var bus = planned.buses().get(route.fromBusId());
        var plan = new PipeWireRouteTypes.Plan(planned.buses(), planned.routes(), List.of(), Map.of(),
                planned.warnings(), false, 1.0, false);
        var inputs = Map.of(route.matrixInputNodeName(), List.of("matrix:input_0", "matrix:input_1"),
                route.captureNodeName(), List.of("capture:input_0", "capture:input_1"),
                sink.name(), List.of("stereo:playback_0", "stereo:playback_1"));
        var outputs = Map.of(bus.nodeName(), List.of("bus:output_0", "bus:output_1"),
                route.matrixOutputNodeName(), List.of("matrix:output_0", "matrix:output_1"),
                route.playbackNodeName(), List.of("route:output_0", "route:output_1"));
        var links = PipeWireRouteLinkBuilder.route(route, plan, inputs, outputs);
        assertTrue(links.contains(new PipeWireRouteTypes.Link("route:output_0", "stereo:playback_0")));
        assertTrue(links.contains(new PipeWireRouteTypes.Link("route:output_1", "stereo:playback_1")));
    }
    @Test void aliasesMustClaimDisjointRealChannels() throws Exception {
        Map<String, Object> config = matrixConfig();
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "front", "from", "music", "to", "alias", "enabled", true,
                "channel_map", "stereo"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", "alias", "selector", DefaultConfig.map("kind", "name_equals", "value", "sink"),
                "role", "output"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("active_routes", List.of("rear", "front"));
        AudioNode sink = output("[ FL FR RL RR ]");
        PipeWireRouteManager manager = new PipeWireRouteManager();
        var snapshot = snapshot(List.of(sink), sink.id());
        assertEquals(2, manager.plan(config, snapshot).routes().size());
        routes.getLast().put("channel_matrix", DefaultConfig.map("inputs", List.of("FL", "FR"),
                "outputs", List.of("RL", "RR"), "coefficients", List.of(List.of(1.0, 0.0), List.of(0.0, 1.0))));
        var overlapping = manager.plan(config, snapshot);
        assertTrue(overlapping.routes().isEmpty());
        assertTrue(overlapping.warnings().stream().anyMatch(w -> w.contains("overlap")));
    }

    @Test void ordinaryStereoWithoutMetadataStillPlans() throws Exception {
        AudioNode sink = sink(91, "stereo", "stereo");
        Map<String, Object> config = matrixConfig();
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.getLast().remove("channel_matrix");
        assertEquals(1, new PipeWireRouteManager().plan(config, snapshot(List.of(sink), sink.id())).routes().size());
    }
}
