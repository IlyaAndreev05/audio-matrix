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
        config.put("active_route", "octine-monitor");
        DeviceIdentity identity = new DeviceIdentity(
                "dc1da7e53884d84b5072b438b0f886d5ac4bcff5018d98d2168fbf07a10d5e4e",
                "pci-0000:0b:00.3", "", "analog-stereo", "analog-output-headphones", "", 2, "FL,FR", "HIGH");
        config.put("devices", List.of(DefaultConfig.map("id", "family17h-front-headphones",
                "name", "Front headphones", "kind", "device_slot", "role", "haptic_output",
                "identity", identity.toMap())));
        bindSlot(config, "octine-headphones", "octine-headphones-output");
        bindSlot(config, "octine-free", "octine-free-headphones");
        AudioNode sink = new AudioNode(72, "alsa_output.pci-0000_0b_00.3.analog-stereo",
                "Family 17h analog stereo", "Audio/Sink", "running", "", "", "", 2, List.of(48000),
                1.0, false, null, identity);
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(sink), List.of(),
                List.of(), List.of(), Map.of(), sink.id(), 0);

        List<Map<String, Object>> pins = GnomeQuickSettingsPins.build(config, snapshot,
                GnomeControlBridge.quickControls(config), List.of(), List.of());

        Map<String, Object> headphones = pinFor(pins, "octine-headphones", "octine-headphones-output");
        assertEquals("outputs", headphones.get("groupId"));
        assertEquals(1, headphones.get("order"));
        assertEquals(false, headphones.get("pinned"));
        assertEquals(false, headphones.containsKey("gain"));
        Map<?, ?> headphonesOutput = (Map<?, ?>) headphones.get("output");
        assertEquals("ONLINE", headphonesOutput.get("state"));
        assertEquals(true, headphonesOutput.get("available"));
        assertEquals(false, headphonesOutput.containsKey("volume"));
        Map<String, Object> freeHeadphones = pinFor(pins, "octine-free", "octine-free-headphones");
        assertEquals(3, freeHeadphones.get("order"));
        assertEquals(false, freeHeadphones.get("pinned"));
        Map<?, ?> freeOutput = (Map<?, ?>) freeHeadphones.get("output");
        assertEquals("ONLINE", freeOutput.get("state"));
        assertEquals(false, freeOutput.containsKey("volume"));
        assertEquals(1, pins.stream().filter(item -> "octine-headphones".equals(item.get("routeId"))
                && "octine-headphones-output".equals(item.get("nodeId"))).count());
        assertEquals(1, pins.stream().filter(item -> "octine-monitor".equals(item.get("routeId"))
                && "octine-monitor-output".equals(item.get("nodeId"))).count());
        assertEquals(6, pins.stream().filter(item -> "octine-common-vibro".equals(item.get("routeId"))).count());
        Map<String, Object> bassFilter = pinFor(pins, "octine-common-vibro", "octine-bass-low-pass");
        assertEquals(List.of("octine-bass-frequency", "octine-bass-slope"),
                ((List<Map<String, Object>>) bassFilter.get("controls")).stream()
                        .map(control -> String.valueOf(control.get("id"))).toList());
        assertEquals(false, pins.stream().anyMatch(item -> "octine-free-switch".equals(item.get("nodeId"))));
    }

    @SuppressWarnings("unchecked")
    private static void bindSlot(Map<String, Object> config, String routeId, String nodeId) {
        Map<String, Object> route = ((List<Map<String, Object>>) config.get("route_library")).stream()
                .filter(item -> routeId.equals(item.get("id"))).findFirst().orElseThrow();
        Map<String, Object> node = ((List<Map<String, Object>>) route.get("nodes")).stream()
                .filter(item -> nodeId.equals(item.get("id"))).findFirst().orElseThrow();
        node.put("parameters", DefaultConfig.map("device_id", "family17h-front-headphones"));
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

    private static GraphConnection edge(String from, String to, String port) {
        String targetPort = "monitor-out".equals(to) ? "in" : port;
        return new GraphConnection(from + "-" + to, from, "out", to, targetPort, true, 1);
    }

    private static GraphConnection edge(String id, String from, String to, String port) {
        return new GraphConnection(id, from, "out", to, port, true, 1);
    }
}
