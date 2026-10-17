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

class PipeWireRouteDspTest extends PipeWireRouteManagerTestSupport {
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
}
