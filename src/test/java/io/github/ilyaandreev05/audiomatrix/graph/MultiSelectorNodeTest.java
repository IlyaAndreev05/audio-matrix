package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MultiSelectorNodeTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test
    void codecPreservesMultipleInputSelection() {
        MultiSelectorNode node = new MultiSelectorNode("select", "Music mix", STEREO,
                List.of("in-browser", "in-player"), List.of("in-browser->out", "in-player->out"));
        AudioGraph graph = new AudioGraph("route", "Music mix", List.of(node), List.of());

        MultiSelectorNode restored = (MultiSelectorNode) GraphCodec.fromMap(GraphCodec.toMap(graph)).nodes().getFirst();

        assertEquals("multi_selector", restored.type());
        assertEquals(List.of("in-browser->out", "in-player->out"), restored.activeLinks());
        assertEquals(true, restored.multiple());
    }

    @Test
    void selectedInputsSumAtUnityAndReceivePostMixPeakProtection() {
        SourceNode browser = new SourceNode("browser", "Browser", "application:browser", STEREO);
        SourceNode player = new SourceNode("player", "Player", "application:player", STEREO);
        MultiSelectorNode selector = new MultiSelectorNode("mix", "Selected music", STEREO,
                List.of("in-browser", "in-player"), List.of("in-browser->out", "in-player->out"));
        OutputNode output = new OutputNode("vibro", "Vibration speaker", "vibro-id", STEREO);
        AudioGraph graph = new AudioGraph("route", "Music to vibration speaker",
                List.of(browser, player, selector, output), List.of(
                link("browser", "out", "mix", "in-browser"),
                link("player", "out", "mix", "in-player"),
                link("mix", "out", "vibro", "in")));

        Map<String, Object> compiled = GraphPipeWireCompiler.compile(graph, DefaultConfig.create());
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) compiled.get("routes");
        @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) compiled.get("dsp");
        @SuppressWarnings("unchecked") Map<String, Object> limiters = (Map<String, Object>) dsp.get("output_limiters");
        @SuppressWarnings("unchecked") Map<String, Object> limiter = (Map<String, Object>) limiters.get("vibro-id");

        assertEquals(2, routes.size());
        assertEquals(List.of(1.0, 1.0), routes.stream().map(route -> route.get("volume")).toList());
        assertFalse(routes.stream().anyMatch(route -> Boolean.TRUE.equals(route.get("mute"))));
        assertEquals(-1.0, limiter.get("ceiling_db"));
        assertEquals(5.0, limiter.get("lookahead_ms"));
    }

    private static GraphConnection link(String from, String out, String to, String in) {
        return new GraphConnection(from + "-" + to + "-" + in, from, out, to, in, true, 1.0);
    }
}
