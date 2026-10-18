package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphCanvasInteractionTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test void portDragPayloadRoundTripsAndRejectsForeignText() {
        String encoded = GraphPortDrag.encode("source-1", "out");
        assertEquals(new GraphPortDrag.Endpoint("source-1", "out"), GraphPortDrag.decode(encoded).orElseThrow());
        assertTrue(GraphPortDrag.decode("unrelated clipboard text").isEmpty());
        assertTrue(GraphPortDrag.decode("audio-matrix-port:!!!").isEmpty());
    }

    @Test void selectedNodeHighlightsOnlyPathsLeadingIntoOrOutOfIt() {
        SourceNode first = new SourceNode("source-1", "Browser", "application:browser", STEREO);
        SourceNode side = new SourceNode("source-2", "Side", "application:side", STEREO);
        GainNode gain = new GainNode("gain", "Gain", STEREO, 1, false);
        OutputNode out = new OutputNode("out", "Output", "device", STEREO, null);
        AudioGraph graph = new AudioGraph("route", "Route", List.of(first, side, gain, out), List.of(
                edge("a", "source-1", "gain"), edge("b", "gain", "out"), edge("unrelated", "source-2", "out")));
        assertEquals(Set.of("a", "b"), GraphPathHighlights.throughNode(graph, "gain"));
    }

    private static GraphConnection edge(String id, String from, String to) {
        return new GraphConnection(id, from, "out", to, "in", true, 1.0);
    }
}
