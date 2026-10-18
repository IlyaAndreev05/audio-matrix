package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
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
                new OutputNode("monitor-out", "Monitor", "device:monitor", STEREO)), List.of(
                edge("chrome", "mixer-1", "chrome-in"), edge("other-apps", "mixer-1", "telegram-in"),
                edge("mixer-1", "monitor-out", "in")));
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphRouteLibrary.activate(config, graph.id());
        QuickSettingsConfig.pinNode(config, graph.id(), "chrome", "sources", 0, "EXPANDED");
        QuickSettingsConfig.pinNode(config, graph.id(), "other-apps", "sources", 1, "COMPACT");
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

        List<Map<String, Object>> pins = GnomeQuickSettingsPins.build(config, snapshot, List.of(), applications, outputs);

        assertEquals(4, pins.size());
        Map<String, Object> chromePin = pin(pins, "chrome");
        Map<String, Object> fallbackPin = pin(pins, "other-apps");
        assertEquals(List.of("chrome-stream"), ids(chromePin.get("applications")));
        assertEquals(List.of("telegram-stream"), ids(fallbackPin.get("applications")));
        Map<String, Object> mixerPin = pin(pins, "mixer-1");
        @SuppressWarnings("unchecked") List<Map<String, Object>> mixerInputs = (List<Map<String, Object>>) mixerPin.get("mixerInputs");
        assertEquals(Set.of("Chrome", "Other apps"), Set.copyOf(mixerInputs.stream().map(item -> (String) item.get("name")).toList()));
        assertEquals(true, mixerInputs.stream().filter(item -> "telegram-in".equals(item.get("id")))
                .findFirst().orElseThrow().get("muted"));
        Map<?, ?> output = (Map<?, ?>) pin(pins, "monitor-out").get("output");
        assertTrue(Boolean.TRUE.equals(output.get("available")));
        assertEquals(.7, output.get("volume"));
    }

    private static Map<String, Object> pin(List<Map<String, Object>> pins, String nodeId) {
        return pins.stream().filter(item -> nodeId.equals(item.get("nodeId"))).findFirst().orElseThrow();
    }

    private static List<String> ids(Object value) {
        return ((List<?>) value).stream().map(item -> String.valueOf(((Map<?, ?>) item).get("id"))).toList();
    }

    private static GraphConnection edge(String from, String to, String port) {
        String targetPort = "monitor-out".equals(to) ? "in" : port;
        return new GraphConnection(from + "-" + to, from, "out", to, targetPort, true, 1);
    }
}
