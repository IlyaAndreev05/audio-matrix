package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEndpointGainFactoryTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test void insertsUnityEndpointGainsIdempotentlyAndPreservesTheExistingRouteLevel() {
        AudioGraph original = new AudioGraph("route-a", "Desk", List.of(
                new SourceNode("source-a", "Chrome", "application:chrome", STEREO),
                new OutputNode("output-a", "Monitor", "monitor", STEREO)),
                List.of(new GraphConnection("link-a", "source-a", "out", "output-a", "in", true, 0.42)));

        AudioGraph migrated = GraphEndpointGainFactory.ensure(original);
        AudioGraph roundTrip = GraphCodec.fromMap(GraphCodec.toMap(migrated));
        AudioGraph repeated = GraphEndpointGainFactory.ensure(roundTrip);
        Map<String, Object> compiled = GraphPipeWireCompiler.compile(repeated, DefaultConfig.create());
        @SuppressWarnings("unchecked") Map<String, Object> route =
                (Map<String, Object>) ((List<?>) compiled.get("routes")).getFirst();

        assertEquals(2, repeated.nodes().stream().filter(GainNode.class::isInstance).count());
        assertEquals(3, repeated.connections().size());
        assertEquals(0.42, route.get("volume"));
        assertTrue(repeated.nodes().stream().filter(GainNode.class::isInstance)
                .map(GainNode.class::cast).allMatch(gain -> gain.gain() == 1.0 && !gain.muted()));
        assertTrue(GraphValidator.validate(repeated).isEmpty());
        assertFalse(GraphCodec.toMap(repeated).toString().contains("pipewire_node_id"));
    }

    @Test void postLimiterEndpointGainRemainsAnAttenuatingOutputControl() {
        FilterNode limiter = new FilterNode("limiter", "Peak limiter", STEREO, "limiter", true,
                Map.of("ceiling_db", -1.0, "lookahead_ms", 5.0, "release_ms", 80.0));
        AudioGraph original = new AudioGraph("route-limited", "Limited", List.of(
                new SourceNode("source-a", "Chrome", "application:chrome", STEREO), limiter,
                new OutputNode("output-a", "Monitor", "monitor", STEREO)), List.of(
                link("source-a", "out", "limiter", "in"), link("limiter", "out", "output-a", "in")));

        AudioGraph migrated = GraphEndpointGainFactory.ensure(original);

        assertTrue(GraphValidator.validate(migrated).isEmpty());
        assertTrue(GraphPipeWireCompiler.compile(migrated, DefaultConfig.create()).get("routes") instanceof List<?>);
    }

    @Test void simpleMatrixCellsToggleOnlyTheirDirectEndpointConnection() {
        AudioGraph graph = GraphEndpointGainFactory.ensure(new AudioGraph("route-matrix", "Desk", List.of(
                new SourceNode("source-a", "Chrome", "application:chrome", STEREO),
                new OutputNode("output-a", "Monitor", "monitor", STEREO)), List.of()));
        SourceNode source = (SourceNode) graph.nodes().stream().filter(SourceNode.class::isInstance).findFirst().orElseThrow();
        OutputNode output = (OutputNode) graph.nodes().stream().filter(OutputNode.class::isInstance).findFirst().orElseThrow();

        assertFalse(GraphMatrixRouting.connected(graph, source, output));
        assertTrue(GraphMatrixRouting.canToggle(graph, source, output));
        AudioGraph connected = GraphMatrixRouting.setDirect(graph, source, output, true);
        assertTrue(GraphMatrixRouting.connected(connected, source, output));
        assertTrue(GraphMatrixRouting.canToggle(connected, source, output));
        assertFalse(GraphMatrixRouting.connected(GraphMatrixRouting.setDirect(connected, source, output, false), source, output));
    }

    private static GraphConnection link(String from, String fromPort, String to, String toPort) {
        return new GraphConnection("link-" + from + "-" + to, from, fromPort, to, toPort, true, 1);
    }
}
