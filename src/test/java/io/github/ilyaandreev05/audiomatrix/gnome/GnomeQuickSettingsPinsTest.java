package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GnomeQuickSettingsPinsTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test void routesApplicationsToPinnedSourcesAndPublishesPinnedOutputAvailability() {
        Map<String, Object> config = DefaultConfig.create();
        AudioNode sink = new AudioNode(12, "alsa_output.benq", "BenQ HDMI", "Audio/Sink", "running",
                "", "", "", 2, List.of(48000), .7, false, null);
        config.put("devices", List.of(DefaultConfig.map("id", "monitor", "name", "Monitor",
                "selector", DefaultConfig.map("kind", "name_equals", "value", sink.name()))));
        AudioGraph graph = new AudioGraph("desk-route", "Desk", List.of(
                new SourceNode("chrome", "Chrome", "application:chrome", STEREO),
                new SourceNode("other-apps", "Other apps", "application:*", STEREO),
                new MixerNode("mixer-1", "Application mixer", STEREO,
                        Map.of("chrome-in", .7, "telegram-in", .3), Map.of("telegram-in", true)),
                new OutputNode("monitor-out", "Monitor", "monitor", STEREO)), List.of(
                edge("chrome", "mixer-1", "chrome-in"), edge("other-apps", "mixer-1", "telegram-in"),
                edge("mixer-1", "monitor-out", "in")));
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphRouteLibrary.activate(config, graph.id());
        QuickSettingsConfig.pinNode(config, graph.id(), "chrome", "applications", 0, "EXPANDED");
        QuickSettingsConfig.pinNode(config, graph.id(), "other-apps", "applications", 1, "COMPACT");
        QuickSettingsConfig.pinNode(config, graph.id(), "mixer-1", "routing", 0, "EXPANDED");
        QuickSettingsConfig.pinNode(config, graph.id(), "monitor-out", "outputs", 0, "EXPANDED");
        List<Map<String, Object>> applications = List.of(
                DefaultConfig.map("id", "chrome-stream", "application_id", "chrome", "process", "chrome",
                        "name", "Chrome", "volume", .5, "muted", false, "streams", List.of()),
                DefaultConfig.map("id", "telegram-stream", "application_id", "telegram", "process", "telegram-desktop",
                        "name", "Telegram", "volume", .8, "muted", false, "streams", List.of()));
        List<Map<String, Object>> outputs = List.of(DefaultConfig.map("id", "device:monitor", "name", sink.name(),
                "description", sink.description(), "volume", sink.volume(), "muted", sink.muted()));
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(sink), List.of(),
                List.of(), List.of(), Map.of(), sink.id(), 0);

        List<Map<String, Object>> pins = GnomeQuickSettingsPins.build(config, snapshot, List.of(), applications, outputs)
                .stream().filter(item -> graph.id().equals(item.get("routeId"))).toList();

        assertEquals(4, pins.size());
        Map<String, Object> chromePin = pin(pins, "chrome");
        assertEquals(1.0, ((Map<?, ?>) chromePin.get("sourceGain")).get("volume"));
        Map<String, Object> fallbackPin = pin(pins, "other-apps");
        assertEquals(List.of("chrome-stream"), ids(chromePin.get("applications")));
        assertEquals(false, chromePin.get("tabAdapterAvailable"));
        assertEquals(false, chromePin.get("pinned"));
        assertEquals(List.of("telegram-stream"), ids(fallbackPin.get("applications")));
        Map<String, Object> mixerPin = pin(pins, "mixer-1");
        @SuppressWarnings("unchecked") List<Map<String, Object>> mixerInputs = (List<Map<String, Object>>) mixerPin.get("mixerInputs");
        assertEquals(Set.of("Chrome", "Other apps"), Set.copyOf(mixerInputs.stream().map(item -> (String) item.get("name")).toList()));
        assertEquals(true, mixerInputs.stream().filter(item -> "telegram-in".equals(item.get("id")))
                .findFirst().orElseThrow().get("muted"));
        Map<?, ?> output = (Map<?, ?>) pin(pins, "monitor-out").get("output");
        assertTrue(Boolean.TRUE.equals(output.get("available")));
        assertEquals(1.0, output.get("volume"));
    }

    @Test void publishesDistinctGainReferencesForLogicalOutputsSharingOnePipeWireDevice() {
        Map<String, Object> config = DefaultConfig.create();
        AudioNode sink = new AudioNode(22, "alsa_output.family17h", "Family 17h", "Audio/Sink", "running",
                "", "", "", 4, List.of(48000), .7, false, null);
        config.put("devices", List.of(DefaultConfig.map("id", "family17h-surround", "selector",
                DefaultConfig.map("kind", "name_equals", "value", sink.name()))));
        GainNode greenGain = new GainNode("green-gain", "Green AUX level", STEREO, .25, false, "green-out");
        GainNode blackGain = new GainNode("black-gain", "Black AUX level", STEREO, .65, false, "black-out");
        OutputNode green = new OutputNode("green-out", "Green AUX", "device:family17h-surround", STEREO);
        OutputNode black = new OutputNode("black-out", "Black AUX", "device:family17h-surround", STEREO,
                new ChannelMatrix(STEREO, List.of("RL", "RR"), List.of(List.of(1.0, 0.0), List.of(0.0, 1.0))));
        AudioGraph graph = new AudioGraph("family-route", "Family outputs", List.of(
                new SourceNode("apps", "Applications", "application:unassigned", STEREO), greenGain, blackGain,
                green, black), List.of(edge("apps-green", "apps", "green-gain", "in"),
                edge("green-out", "green-gain", "green-out", "in"),
                edge("apps-black", "apps", "black-gain", "in"), edge("black-out", "black-gain", "black-out", "in")));
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphRouteLibrary.activate(config, graph.id());
        QuickSettingsConfig.pinNode(config, graph.id(), green.id(), "outputs", 0, "EXPANDED");
        QuickSettingsConfig.pinNode(config, graph.id(), black.id(), "outputs", 1, "EXPANDED");
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(sink), List.of(),
                List.of(), List.of(), Map.of(), sink.id(), 0);
        List<Map<String, Object>> outputs = List.of(DefaultConfig.map("id", "device:family17h-surround",
                "name", sink.name(), "description", sink.description(), "volume", sink.volume(), "muted", false));

        List<Map<String, Object>> pins = GnomeQuickSettingsPins.build(config, snapshot, List.of(), List.of(), outputs);

        assertEquals("green-gain", ((Map<?, ?>) pin(pins, green.id()).get("output")).get("graphGainNodeId"));
        assertEquals("black-gain", ((Map<?, ?>) pin(pins, black.id()).get("output")).get("graphGainNodeId"));
        assertEquals(((Map<?, ?>) pin(pins, green.id()).get("output")).get("id"),
                ((Map<?, ?>) pin(pins, black.id()).get("output")).get("id"));
    }

    @Test void publishesOutputPinsFromInactiveRoutesWithoutFabricatingGains() {
        Map<String, Object> config = DefaultConfig.create();
        AudioNode monitor = new AudioNode(72, "alsa_output.monitor", "Monitor", "Audio/Sink", "running",
                "", "", "", 2, List.of(48000), .7, false, null);
        AudioNode vibro = new AudioNode(73, "alsa_output.vibro", "Vibro", "Audio/Sink", "running",
                "", "", "", 2, List.of(48000), .3, true, null);
        config.put("devices", List.of(
                DefaultConfig.map("id", "monitor", "name", "Monitor",
                        "selector", DefaultConfig.map("kind", "name_equals", "value", monitor.name())),
                DefaultConfig.map("id", "vibro", "name", "Vibro",
                        "selector", DefaultConfig.map("kind", "name_equals", "value", vibro.name()))));
        bindSlot(config, "main", "octine-monitor-output", "monitor");
        bindSlot(config, "main", "octine-vibro-output", "vibro");
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(monitor, vibro), List.of(),
                List.of(), List.of(), Map.of(), monitor.id(), 0);

        List<Map<String, Object>> pins = GnomeQuickSettingsPins.build(config, snapshot,
                GnomeControlBridge.quickControls(config), List.of(), List.of());

        Map<String, Object> monitorPin = pinFor(pins, "main", "octine-monitor-output");
        Map<String, Object> vibroPin = pinFor(pins, "main", "octine-vibro-output");
        assertEquals("outputs", monitorPin.get("groupId"));
        assertEquals(true, ((Map<?, ?>) monitorPin.get("output")).get("available"));
        assertEquals(.7, ((Map<?, ?>) monitorPin.get("output")).get("volume"));
        assertEquals(true, ((Map<?, ?>) vibroPin.get("output")).get("muted"));
        assertEquals(.3, ((Map<?, ?>) vibroPin.get("output")).get("volume"));
        assertEquals(8, pins.size());
        assertFalse(pins.stream().anyMatch(item -> Set.of("octine-vibro-mixer", "octine-bass-low-pass")
                .contains(item.get("nodeId"))));
    }

    @Test void catchAllApplicationGeneratorExcludesEventAndAudioMatrixOwnedStreams() {
        Map<String, Object> config = DefaultConfig.create();
        Map<String, Object> chrome = application("chrome", "org.chromium.Chromium", "chromium");
        chrome.put("tabAdapterAvailable", true);
        chrome.put("browserAdapter", Map.of("browser", "Chrome", "independentTabVolume", true,
                "reason", "Несколько независимых потоков Chrome."));
        Map<String, Object> event = application("events", "io.github.ilyaandreev05.eventsoundstudio", "eventsoundstudio");
        Map<String, Object> own = application("audio-matrix", "io.github.ilyaandreev05.audiomatrix", "AudioMatrix");
        Map<String, Object> chat = application("chat", "org.telegram.desktop", "telegram-desktop");
        List<Map<String, Object>> applications = List.of(chrome, event, own, chat);
        PipeWireBackend.Snapshot snapshot = PipeWireBackend.Snapshot.offline("disconnected");

        List<Map<String, Object>> pins = GnomeQuickSettingsPins.build(config, snapshot, List.of(), applications, List.of());
        Map<String, Object> allApps = pinFor(pins, "main", "octine-main-source");

        assertEquals(Set.of("chrome", "chat"), Set.copyOf(ids(allApps.get("applications"))));
        assertEquals(true, allApps.get("tabAdapterAvailable"));
        assertEquals("Chrome", ((Map<?, ?>) ((List<?>) allApps.get("applications")).getFirst()).get("browserAdapter") == null
                ? "Chrome" : ((Map<?, ?>) ((Map<?, ?>) ((List<?>) allApps.get("applications")).getFirst()).get("browserAdapter")).get("browser"));
    }

    @SuppressWarnings("unchecked")
    private static void bindSlot(Map<String, Object> config, String routeId, String nodeId, String deviceId) {
        Map<String, Object> route = ((List<Map<String, Object>>) config.get("route_library")).stream()
                .filter(item -> routeId.equals(item.get("id"))).findFirst().orElseThrow();
        Map<String, Object> node = ((List<Map<String, Object>>) route.get("nodes")).stream()
                .filter(item -> nodeId.equals(item.get("id"))).findFirst().orElseThrow();
        node.put("parameters", DefaultConfig.map("device_id", deviceId));
    }

    private static Map<String, Object> pinFor(List<Map<String, Object>> pins, String routeId, String nodeId) {
        return pins.stream().filter(item -> routeId.equals(item.get("routeId")) && nodeId.equals(item.get("nodeId")))
                .findFirst().orElseThrow();
    }

    private static Map<String, Object> pin(List<Map<String, Object>> pins, String nodeId) {
        return pins.stream().filter(item -> nodeId.equals(item.get("nodeId"))).findFirst().orElseThrow();
    }

    private static List<String> ids(Object value) {
        return ((List<?>) value).stream().map(item -> String.valueOf(((Map<?, ?>) item).get("id"))).toList();
    }

    private static Map<String, Object> application(String id, String appId, String process) {
        return DefaultConfig.map("id", id, "name", id, "application_id", appId, "process", process,
                "volume", 1.0, "muted", false, "streams", List.of());
    }

    private static GraphConnection edge(String from, String to, String port) {
        String targetPort = "monitor-out".equals(to) ? "in" : port;
        return new GraphConnection(from + "-" + to, from, "out", to, targetPort, true, 1);
    }

    private static GraphConnection edge(String id, String from, String to, String port) {
        return new GraphConnection(id, from, "out", to, port, true, 1);
    }
}
