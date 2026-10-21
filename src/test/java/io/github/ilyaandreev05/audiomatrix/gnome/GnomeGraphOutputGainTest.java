package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GnomeGraphOutputGainTest {
    @SuppressWarnings("unchecked")
    private static Map<String, Object> savedRoute(Map<String, Object> config, String routeId) {
        return ((List<Map<String, Object>>) config.get("route_library")).stream()
                .filter(item -> routeId.equals(item.get("id"))).findFirst().orElseThrow();
    }

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
        Map<String, Object> saved = savedRoute(config, graph.id());
        AudioGraph changed = GraphCodec.fromMap(saved);
        var output = (io.github.ilyaandreev05.audiomatrix.graph.OutputNode) changed.nodes().stream()
                .filter(OutputNode.class::isInstance).findFirst().orElseThrow();
        var gain = GnomeGraphOutputGain.forOutput(changed, output);

        assertEquals(.43, gain.gain());
        assertEquals(.7, sink.volume());
        assertNull(GnomeGraphOutputGain.find(config, snapshot,
                new AudioNode(99, "other", "Other", "Audio/Sink", "running", "", "", "", 2,
                        List.of(48000), .5, false, null)));
    }

    @Test void stableOutputNodeTargetsSeparateTwoLogicalOutputsOnOneFamilyCard() {
        Map<String, Object> config = DefaultConfig.create();
        List<String> stereo = List.of("FL", "FR");
        SourceNode source = new SourceNode("apps", "Applications", "application:unassigned", stereo);
        GainNode greenGain = new GainNode("green-gain", "Green AUX level", stereo, .25, false, "green");
        GainNode blackGain = new GainNode("black-gain", "Black AUX level", stereo, .65, false, "black");
        OutputNode green = new OutputNode("green", "Green AUX", "family17h-surround", stereo);
        OutputNode black = new OutputNode("black", "Black AUX", "family17h-surround", stereo,
                new ChannelMatrix(stereo, List.of("RL", "RR"), List.of(List.of(1.0, 0.0), List.of(0.0, 1.0))));
        AudioGraph graph = new AudioGraph("family-route", "Family outputs", List.of(source, greenGain, blackGain,
                green, black), List.of(new GraphConnection("apps-green", "apps", "out", "green-gain", "in", true, 1),
                new GraphConnection("green-out", "green-gain", "out", "green", "in", true, 1),
                new GraphConnection("apps-black", "apps", "out", "black-gain", "in", true, 1),
                new GraphConnection("black-out", "black-gain", "out", "black", "in", true, 1)));
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphRouteLibrary.activate(config, graph.id());
        GnomeGraphOutputGain.Target greenTarget = new GnomeGraphOutputGain.Target(graph.id(), "green", "green-gain");
        GnomeGraphOutputGain.Target blackTarget = new GnomeGraphOutputGain.Target(graph.id(), "black", "black-gain");

        GnomeGraphOutputGain.set(config, greenTarget, .18, false);
        GnomeGraphOutputGain.set(config, blackTarget, null, true);

        Map<String, Object> saved = savedRoute(config, graph.id());
        AudioGraph changed = GraphCodec.fromMap(saved);
        GainNode updatedGreen = (GainNode) changed.nodes().stream().filter(node -> node.id().equals("green-gain")).findFirst().orElseThrow();
        GainNode updatedBlack = (GainNode) changed.nodes().stream().filter(node -> node.id().equals("black-gain")).findFirst().orElseThrow();
        assertEquals(.18, updatedGreen.gain());
        assertEquals(false, updatedGreen.muted());
        assertEquals(.65, updatedBlack.gain());
        assertEquals(true, updatedBlack.muted());
    }

    @Test void graphOutputCommandsAreAcceptedAndRejectStaleRevision() {
        GnomeControlBridge.Command command = GnomeControlBridge.parseCommandLine(
                "AudioMatrix.Command (uint32 5, 'client-1:request-green', '12', 'set_graph_output_volume', 'family-route\\tgreen\\tgreen-gain\\t0.31')");

        assertEquals(new GnomeControlBridge.Command(5, "client-1:request-green", "12",
                "set_graph_output_volume", "family-route\tgreen\tgreen-gain\t0.31"), command);
        assertEquals("ok", GnomeControlBridge.execute(command,
                ignored -> CompletableFuture.completedFuture(null)).join().status());
        assertEquals(true, GnomeCommandRevision.isStale(command, 13));
        assertEquals(false, GnomeCommandRevision.isStale(command, 12));
    }
}
