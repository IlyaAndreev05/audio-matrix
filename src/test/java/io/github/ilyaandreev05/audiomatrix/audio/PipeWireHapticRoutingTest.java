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

class PipeWireHapticRoutingTest extends PipeWireRouteManagerTestSupport {
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
}
