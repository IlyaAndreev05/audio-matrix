package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphFilterCompilerTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test
    void graphEqExpandsBandsIntoPipeWireFiltersAndSkipsDisabledBands() {
        FilterNode eq = new FilterNode("eq", "Tactile EQ", STEREO, "eq", true, Map.of("bands", List.of(
                Map.of("filterKind", "BELL", "frequencyHz", 95.0, "gainDb", -6.0, "q", 1.4),
                Map.of("filter_kind", "LOW_SHELF", "frequency_hz", 42.0, "gain_db", -3.0,
                        "q", 0.8, "enabled", false))));

        Map<String, Object> config = compile(eq);
        Map<String, Object> chain = routeChain(config);
        @SuppressWarnings("unchecked") List<Map<String, Object>> filters = (List<Map<String, Object>>) chain.get("filters");

        assertEquals(1, filters.size());
        assertEquals("peaking", filters.getFirst().get("type"));
        assertEquals(95.0, filters.getFirst().get("frequency_hz"));
        assertEquals(-6.0, filters.getFirst().get("gain_db"));
    }

    @Test
    void graphDelayCompilesToRouteDelayInsteadOfUnsupportedFilter() {
        FilterNode delay = new FilterNode("delay", "Output delay", STEREO, "delay", true,
                Map.of("delay_ms", 125.0));

        Map<String, Object> config = compile(delay);
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");

        assertEquals(125.0, routes.getFirst().get("delay_ms"));
        assertFalse(routes.getFirst().containsKey("filters_ref"));
    }

    @Test
    void invalidDelayAndMoreThanTwentyEqBandsAreRejectedBeforeApply() {
        FilterNode invalidDelay = new FilterNode("delay", "Invalid delay", STEREO, "delay", true,
                Map.of("delay_ms", 5000.1));
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++)
            tooMany.add(Map.of("type", "peaking", "frequency_hz", 100.0 + i, "gain_db", 0.0, "q", 1.0));
        FilterNode invalidEq = new FilterNode("eq", "Invalid EQ", STEREO, "eq", true,
                Map.of("bands", tooMany));

        assertThrows(IllegalArgumentException.class, () -> compile(invalidDelay));
        assertThrows(IllegalArgumentException.class, () -> compile(invalidEq));
    }

    private static Map<String, Object> compile(FilterNode filter) {
        SourceNode source = new SourceNode("source", "Browser", "application:browser", STEREO);
        OutputNode output = new OutputNode("output", "Monitor", "monitor-id", STEREO);
        AudioGraph graph = new AudioGraph("route", "Graph filter test", List.of(source, filter, output), List.of(
                edge("source", "out", filter.id(), "in"), edge(filter.id(), "out", "output", "in")));
        return GraphPipeWireCompiler.compile(graph, DefaultConfig.create());
    }

    private static Map<String, Object> routeChain(Map<String, Object> config) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        String reference = String.valueOf(routes.getFirst().get("filters_ref"));
        @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) config.get("dsp");
        @SuppressWarnings("unchecked") Map<String, Object> chains = (Map<String, Object>) dsp.get("route_chains");
        return (Map<String, Object>) chains.get(reference.substring("dsp.route_chains.".length()));
    }

    private static GraphConnection edge(String from, String fromPort, String to, String toPort) {
        return new GraphConnection(from + "-" + to, from, fromPort, to, toPort, true, 1.0);
    }
}
