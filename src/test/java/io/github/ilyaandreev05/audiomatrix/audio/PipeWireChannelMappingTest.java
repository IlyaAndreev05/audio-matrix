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

class PipeWireChannelMappingTest extends PipeWireRouteManagerTestSupport {
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
