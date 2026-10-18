package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GnomeQuickFilterPublicationTest {
    @Test void publishesFilterRangesAndChoicesForPinnedGraphNodes() {
        Map<String, Object> config = DefaultConfig.create();
        List<String> stereo = List.of("FL", "FR");
        AudioGraph graph = new AudioGraph("filter-route", "Filter route", List.of(
                new SourceNode("source", "Apps", "application:*", stereo),
                new FilterNode("low-pass", "Bass low-pass", stereo, "low_pass", true,
                        Map.of("frequency_hz", 100.0, "slope_db_oct", 24)),
                new OutputNode("output", "Vibration", "device:buur", stereo)), List.of(
                new GraphConnection("source-filter", "source", "out", "low-pass", "in", true, 1),
                new GraphConnection("filter-output", "low-pass", "out", "output", "in", true, 1)));
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphRouteLibrary.activate(config, graph.id());
        config.put("quick_controls", List.of(
                Map.of("id", "cutoff", "route_id", graph.id(), "node_id", "low-pass",
                        "parameter_id", "frequency_hz", "label", "Cutoff"),
                Map.of("id", "slope", "route_id", graph.id(), "node_id", "low-pass",
                        "parameter_id", "slope_db_oct", "label", "Slope")));

        List<Map<String, Object>> controls = GnomeControlBridge.quickControls(config);

        assertEquals(List.of("range", "choice"), controls.stream().map(item -> item.get("kind")).toList());
        assertEquals(100.0, controls.getFirst().get("value"));
        assertEquals(20.0, controls.getFirst().get("minimum"));
        assertEquals("log", controls.getFirst().get("scale"));
        assertEquals(24, controls.getLast().get("value"));
        List<?> options = (List<?>) controls.getLast().get("options");
        assertEquals(List.of("6", "12", "18", "24", "36", "48"), options.stream()
                .map(option -> ((Map<?, ?>) option).get("id")).toList());
    }
}
