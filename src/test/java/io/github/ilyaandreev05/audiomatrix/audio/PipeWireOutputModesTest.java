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

class PipeWireOutputModesTest extends PipeWireRouteManagerTestSupport {
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
}
