package io.github.ilyaandreev05.audiomatrix.audio;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.CapturedStreamBinding;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PipeWireCapturedStreamRoutingTest extends PipeWireRouteManagerTestSupport {
    private static final String OWNED_APP_ID = "io.github.ilyaandreev05.audiomatrix";

    @Test
    void captureStreamNameBindsOnlyItsGeneratedSourceWhileFirefoxRemainsOrdinaryAudio() throws Exception {
        Map<String, Object> config = DefaultConfig.create();
        config.put("active_route", "");
        config.put("graph_default_bus", "");
        @SuppressWarnings("unchecked") List<Map<String, Object>> buses = (List<Map<String, Object>>) config.get("buses");
        buses.add(DefaultConfig.map("id", "capture-source", "name", "Captured tab", "role", "general",
                "channels", "stereo", "volume", 1.0, "mute", false));
        buses.add(DefaultConfig.map("id", "firefox-source", "name", "Firefox", "role", "general",
                "channels", "stereo", "volume", 1.0, "mute", false));
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) config.get("devices");
        devices.clear();
        devices.add(DefaultConfig.map("id", "private-output", "selector",
                DefaultConfig.map("kind", "name_equals", "value", "private-output"),
                "role", "speaker", "volume", 1.0, "mute", false));
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.clear();
        routes.add(DefaultConfig.map("id", "capture-output", "from", "capture-source", "to", "private-output", "enabled", true));
        routes.add(DefaultConfig.map("id", "firefox-output", "from", "firefox-source", "to", "private-output", "enabled", true));
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("mode", "hybrid");
        profiles.getFirst().put("active_buses", List.of("capture-source", "firefox-source"));
        profiles.getFirst().put("active_routes", List.of("capture-output", "firefox-output"));
        List<Map<String, Object>> applications = new ArrayList<>(List.of(
                DefaultConfig.map("id", "browser-rule", "match", DefaultConfig.map("app_id", "com.google.Chrome"),
                        "bus", "capture-source", "volume", 0.8, "mute", false),
                DefaultConfig.map("id", "firefox-rule", "match", DefaultConfig.map("app_id", "org.mozilla.firefox"),
                        "bus", "firefox-source", "volume", 0.7, "mute", false)));
        config.put("applications", applications);

        AudioNode output = sink(81, "private-output", "Private virtual output");
        AudioNode captured = new AudioNode(101, "audio-matrix-chrome-capture-key", "Opaque capture", "Stream/Output/Audio",
                "running", "Audio Matrix", "audiomatrix", OWNED_APP_ID, 2, List.of(48000), 1.0, false, null);
        AudioNode unboundCapture = new AudioNode(102, "audio-matrix-chrome-other-key", "Other capture", "Stream/Output/Audio",
                "running", "Audio Matrix", "audiomatrix", OWNED_APP_ID, 2, List.of(48000), 1.0, false, null);
        AudioNode firefox = new AudioNode(103, "firefox-stream", "Firefox", "Stream/Output/Audio", "running",
                "Firefox", "firefox", "org.mozilla.firefox", 2, List.of(48000), 1.0, false, null);
        PipeWireBackend.Snapshot snapshot = snapshotWithStreams(List.of(output), List.of(captured, unboundCapture, firefox), output.id());
        CapturedStreamBinding binding = new CapturedStreamBinding(captured.name(), "capture-source", "browser-rule",
                "com.google.Chrome", "chrome", "tab-opaque-key", 0.5, false);

        PipeWireRouteManager.Plan plan = new PipeWireRouteManager().plan(config, snapshot, Map.of(captured.name(), binding));

        Map<String, String> sourceByStream = plan.streams().stream().collect(java.util.stream.Collectors.toMap(
                stream -> stream.node().name(), stream -> stream.busId()));
        assertEquals("capture-source", sourceByStream.get(captured.name()));
        assertEquals("firefox-source", sourceByStream.get(firefox.name()));
        assertFalse(sourceByStream.containsKey(unboundCapture.name()), "Unbound owned captures must not use app catch-all rules");
        assertEquals(1.0, plan.routes().stream().filter(route -> route.id().equals("capture-output")).findFirst()
                .orElseThrow().volume(), 1e-9, "Tab gain is applied to the native stream, not multiplied into route gain");
        assertEquals(0.0, plan.routes().stream().filter(route -> route.id().equals("capture-output")).findFirst()
                .orElseThrow().gainDb(), 1e-9);
    }
}
