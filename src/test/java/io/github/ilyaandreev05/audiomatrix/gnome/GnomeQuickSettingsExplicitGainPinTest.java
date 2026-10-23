package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GnomeQuickSettingsExplicitGainPinTest {
    @Test void anExplicitlyPinnedEndpointGainIsPublishedAsTheControllableNode() {
        Map<String, Object> config = DefaultConfig.create();
        List<String> channels = List.of("FL", "FR");
        SourceNode source = new SourceNode("source", "Music", "application:music", channels);
        GainNode endpointGain = new GainNode("explicit-gain", "Music Volume", channels, .42, true, source.id());
        OutputNode output = new OutputNode("monitor", "Monitor", "system_default", channels);
        AudioGraph graph = new AudioGraph("explicit-gain-route", "Explicit Gain",
                List.of(source, endpointGain, output), List.of(
                new GraphConnection("source-gain", source.id(), "out", endpointGain.id(), "in", true, 1),
                new GraphConnection("gain-output", endpointGain.id(), "out", output.id(), "in", true, 1)));
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphRouteLibrary.activate(config, graph.id());
        QuickSettingsConfig.pinNode(config, graph.id(), endpointGain.id(), "routing", 0, "EXPANDED");

        AudioNode player = new AudioNode(21, "music-stream", "Music", "Stream/Output/Audio", "running",
                "Music", "music", "music", 2, List.of(48000), .7, false, null);
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true,"",List.of(),List.of(),
                List.of(player),List.of(),Map.of(),-1,0);
        List<Map<String, Object>> pins = GnomeQuickSettingsPins.build(config, snapshot, List.of(),
                GnomeApplicationProjection.applications(snapshot,config,List.of(),null),List.of(),null,Map.of());
        Map<String, Object> state = pins.stream()
                .filter(pin -> graph.id().equals(pin.get("routeId")) && endpointGain.id().equals(pin.get("nodeId")))
                .findFirst().orElse(null);

        assertNotNull(state, "An explicit Gain pin must not be dropped because it targets an endpoint");
        Map<?, ?> gain = (Map<?, ?>) state.get("gain");
        assertEquals(endpointGain.id(), gain.get("nodeId"));
        assertEquals(.42, gain.get("volume"));
        assertEquals(true, gain.get("muted"));
    }
}
