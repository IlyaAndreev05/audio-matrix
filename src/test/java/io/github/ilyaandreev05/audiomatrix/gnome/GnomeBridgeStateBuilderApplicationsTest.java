package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.CapturedStreamBinding;
import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GnomeBridgeStateBuilderApplicationsTest {
    private static final String EVENT_APPLICATION_ID = "io.github.ilyaandreev05.eventsoundstudio";

    @Test void publishesEveryApplicationWithTheLevelItsOwnerKeeps() {
        Map<String, Object> config = DefaultConfig.create();
        config.put("applications", List.of(DefaultConfig.map("id", "rule-chrome",
                "match", DefaultConfig.map("app_id", "com.example.Chrome"), "volume", 0.4, "mute", false)));
        AudioNode chrome = stream(41, "Chrome", "com.example.Chrome", "chrome", 0.9);
        AudioNode firefox = stream(42, "Firefox", "org.mozilla.firefox", "firefox", 0.8);
        AudioNode event = stream(43, "Event Sound Studio", EVENT_APPLICATION_ID, "eventsoundstudio", 0.5);
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(), List.of(),
                List.of(chrome, firefox, event), List.of(), Map.of(), -1, 0);
        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(route(config),
                Map.of(GraphCommonVibro.MAIN_SOURCE_ID, List.of(
                        DynamicInstanceRegistry.application(chrome),
                        DynamicInstanceRegistry.application(firefox),
                        DynamicInstanceRegistry.application(event))),
                (selector, instance) -> generatedLevel(instance));

        Map<String, Object> state = GnomeBridgeStateBuilder.build(snapshot, config, 7, List.of(), reconciled);

        List<Map<String, Object>> applications = maps(state.get("applications"));
        assertEquals(2, applications.size(), "the event stream belongs to its own branch");
        Map<String, Object> claimed = application(applications, "com.example.Chrome");
        assertEquals(0.4, claimed.get("volume"), "the saved rule owns the level of the application it claims");
        assertEquals(false, claimed.get("muted"));
        assertEquals(List.of(41), streamIds(claimed), "the application keeps its own streams");
        Map<String, Object> generated = application(applications, "org.mozilla.firefox");
        assertEquals(0.7, generated.get("volume"), "an application without a rule carries the generated level");
        assertEquals(true, generated.get("muted"));
        assertTrue(applications.stream().noneMatch(app -> EVENT_APPLICATION_ID.equals(app.get("application_id"))),
                "the event stream is never listed as an ordinary application");

        List<Map<String,Object>> visible = maps(state.get("quickPins")).stream()
                .flatMap(pin -> maps(pin.get("applications")).stream()).toList();
        assertEquals(Set.of("app:com.example.Chrome", "app:org.mozilla.firefox"), Set.copyOf(ids(visible)));
        assertEquals(2, visible.size(), "Each playback application has exactly one visible card");
        Map<String,Object> chromePin = maps(state.get("quickPins")).stream()
                .filter(pin -> maps(pin.get("applications")).stream().anyMatch(
                        app -> "com.example.Chrome".equals(app.get("application_id")))).findFirst().orElseThrow();
        assertEquals(.4, ((Map<?,?>)chromePin.get("gain")).get("volume"),
                "The visible generated card must use the saved rule, not a stale materialized gain");
    }

    @Test void publishesOrdinaryAppsButHidesCaptureAndSystemStreams() {
        Map<String, Object> config = DefaultConfig.create();
        AudioNode firefox = stream(51, "Firefox", "org.mozilla.firefox", "firefox", 0.8);
        AudioNode processIdentified = stream(55, "Music player", "", "spotify", 0.75);
        AudioNode capturedTab = new AudioNode(52, CapturedStreamBinding.NODE_PREFIX + "session-1",
                "Captured browser tab", "Stream/Output/Audio", "running", "Chrome Tab Capture",
                "capture-host", ChromeBrowserAdapter.CAPTURE_APPLICATION_ID, 2, List.of(48000), 0.7, false, null);
        AudioNode shellSound = stream(53, "GNOME Shell", "", "gnome-shell", 0.6);
        AudioNode helperSound = stream(54, "Desktop helper", "", "gsd-media-keys", 0.5);
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(), List.of(),
                List.of(firefox, processIdentified, capturedTab, shellSound, helperSound), List.of(), Map.of(), -1, 0);

        Map<String, Object> state = GnomeBridgeStateBuilder.build(snapshot, config, 7, List.of(), null);
        List<Map<String, Object>> applications = maps(state.get("applications"));

        assertEquals(List.of("app:org.mozilla.firefox", "process:spotify"), ids(applications),
                "Stable application IDs take precedence and process identity supports desktop apps without one");
        assertEquals(List.of(51), streamIds(applications.getFirst()),
                "The real browser playback stream remains grouped under its desktop application");
        Map<String, Object> spotify = applications.stream()
                .filter(app -> "process:spotify".equals(app.get("id"))).findFirst().orElseThrow();
        assertEquals("spotify", spotify.get("process"));
        assertEquals(List.of(55), streamIds(spotify));
    }

    /** The materialized instance still carries the level from before the rule was saved. */
    private static DynamicInstanceRegistry.GainState generatedLevel(DynamicInstanceRegistry.InstanceSnapshot instance) {
        String applicationId = String.valueOf(instance.runtimeBinding().get("applicationId"));
        if ("com.example.Chrome".equals(applicationId)) return new DynamicInstanceRegistry.GainState(0.95, false);
        if ("org.mozilla.firefox".equals(applicationId)) return new DynamicInstanceRegistry.GainState(0.7, true);
        return null;
    }

    private static AudioNode stream(int id, String name, String applicationId, String process, double volume) {
        return new AudioNode(id, name, name, "Stream/Output/Audio", "running", name, process, applicationId,
                2, List.of(48000), volume, false, null);
    }

    @SuppressWarnings("unchecked")
    private static AudioGraph route(Map<String, Object> config) {
        Map<String, Object> route = ((List<Map<String, Object>>) config.get("route_library")).stream()
                .filter(item -> "main".equals(item.get("id"))).findFirst().orElseThrow();
        return GraphCodec.fromMap(route);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static Map<String, Object> application(List<Map<String, Object>> applications, String applicationId) {
        return applications.stream().filter(app -> applicationId.equals(app.get("application_id")))
                .findFirst().orElseThrow(() -> new AssertionError("The application is missing: " + applicationId));
    }

    private static List<Integer> streamIds(Map<String, Object> application) {
        return maps(application.get("streams")).stream().map(stream -> ((Number) stream.get("id")).intValue()).toList();
    }

    private static List<String> ids(Object value) {
        return maps(value).stream().map(item -> String.valueOf(item.get("id"))).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pin(Map<String, Object> state, String nodeId) {
        return maps(state.get("quickPins")).stream()
                .filter(pin -> nodeId.equals(pin.get("nodeId")) && pin.get("instanceKey") == null)
                .findFirst().orElseThrow(() -> new AssertionError("The pinned node is missing: " + nodeId));
    }

    @Test void theEventStreamNeverBecomesAnOrdinaryApplication() {
        Map<String, Object> config = DefaultConfig.create();
        AudioNode event = stream(43, "Event Sound Studio", EVENT_APPLICATION_ID, "eventsoundstudio", 0.5);
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(), List.of(),
                List.of(event), List.of(), Map.of(), -1, 0);
        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(route(config),
                Map.of(GraphCommonVibro.MAIN_SOURCE_ID, List.of(DynamicInstanceRegistry.application(event))));

        Map<String, Object> state = GnomeBridgeStateBuilder.build(snapshot, config, 7, List.of(), reconciled);

        assertEquals(List.of(), state.get("applications"));
        assertFalse(maps(state.get("quickPins")).stream()
                .flatMap(pin -> maps(pin.get("applications")).stream()).anyMatch(
                        app -> EVENT_APPLICATION_ID.equals(app.get("application_id"))));
    }
}
