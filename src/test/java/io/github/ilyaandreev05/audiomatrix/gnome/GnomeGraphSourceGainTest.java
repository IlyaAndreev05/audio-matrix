package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GnomeGraphSourceGainTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test void sourceMutePreservesStoredGainAndSliderChangeUnmutes() {
        Map<String, Object> config = DefaultConfig.create();
        SourceNode source = new SourceNode("chrome", "Chrome", "application:chrome", STEREO);
        GainNode gain = new GainNode("chrome-gain", "Chrome level", STEREO, .65, false, source.id());
        OutputNode output = new OutputNode("monitor", "Monitor", "system_default", STEREO);
        AudioGraph graph = new AudioGraph("route", "Desktop", List.of(source, gain, output), List.of(
                edge("source", source.id(), "out", gain.id(), "in"),
                edge("gain", gain.id(), "out", output.id(), "in")));
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphRouteLibrary.activate(config, graph.id());
        config.put("applications", List.of(DefaultConfig.map("match", DefaultConfig.map("app_id", "chrome"),
                "volume", .4, "mute", false)));
        GnomeGraphSourceGain.Target target = new GnomeGraphSourceGain.Target(graph.id(), source.id(), gain.id());

        GnomeGraphSourceGain.set(config, target, null, true);
        GainNode muted = sourceGain(config);
        assertTrue(muted.muted());
        assertEquals(.65, muted.gain());
        assertEquals(.4, ((Number) ((Map<?, ?>) ((List<?>) config.get("applications")).getFirst()).get("volume")).doubleValue());

        GnomeGraphSourceGain.set(config, target, .25, false);
        GainNode unmuted = sourceGain(config);
        assertFalse(unmuted.muted());
        assertEquals(.25, unmuted.gain());
    }

    private static GainNode sourceGain(Map<String, Object> config) {
        String active = String.valueOf(config.get("active_route"));
        Map<?, ?> route = ((List<?>) config.get("route_library")).stream().filter(Map.class::isInstance)
                .map(Map.class::cast).filter(item -> active.equals(item.get("id"))).findFirst().orElseThrow();
        Map<String, Object> encoded = new LinkedHashMap<>();
        route.forEach((key, value) -> encoded.put(String.valueOf(key), value));
        return (GainNode) GraphCodec.fromMap(encoded).nodes().stream().filter(GainNode.class::isInstance)
                .filter(node -> "chrome".equals(((GainNode) node).endpointNodeId())).findFirst().orElseThrow();
    }

    private static GraphConnection edge(String id, String from, String fromPort, String to, String toPort) {
        return new GraphConnection(id, from, fromPort, to, toPort, true, 1.0);
    }
}
