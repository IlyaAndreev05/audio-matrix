package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GnomeGraphOutputGainTest {
    @Test void outputMenuTargetChangesOnlyTheSavedGraphGain() {
        Map<String, Object> config = DefaultConfig.create();
        AudioNode sink = new AudioNode(12, "alsa_output.monitor", "Monitor HDMI", "Audio/Sink", "running",
                "", "", "", 2, List.of(48000), .7, false, null);
        config.put("devices", List.of(DefaultConfig.map("id", "monitor", "name", "Monitor",
                "selector", DefaultConfig.map("kind", "name_equals", "value", sink.name()))));
        AudioGraph graph = new AudioGraph("route-output-gain", "Monitor", List.of(
                new SourceNode("source", "Chrome", "application:chrome", List.of("FL", "FR")),
                new OutputNode("output", "Monitor", "device:monitor", List.of("FL", "FR"))),
                List.of(new GraphConnection("link", "source", "out", "output", "in", true, 1)));
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphRouteLibrary.activate(config, graph.id());
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(sink),
                List.of(), List.of(), List.of(), Map.of(), sink.id(), 0);
        GnomeGraphOutputGain.Target target = GnomeGraphOutputGain.find(config, snapshot, sink);
        assertNotNull(target);

        GnomeGraphOutputGain.set(config, target, .43, false);
        @SuppressWarnings("unchecked") Map<String, Object> savedRoute =
                ((List<Map<String, Object>>) config.get("route_library")).getFirst();
        AudioGraph changed = GraphCodec.fromMap(savedRoute);
        var output = (io.github.ilyaandreev05.audiomatrix.graph.OutputNode) changed.nodes().stream()
                .filter(OutputNode.class::isInstance).findFirst().orElseThrow();
        var gain = GnomeGraphOutputGain.forOutput(changed, output);

        assertEquals(.43, gain.gain());
        assertEquals(.7, sink.volume());
        assertNull(GnomeGraphOutputGain.find(config, snapshot,
                new AudioNode(99, "other", "Other", "Audio/Sink", "running", "", "", "", 2,
                        List.of(48000), .5, false, null)));
    }
}
