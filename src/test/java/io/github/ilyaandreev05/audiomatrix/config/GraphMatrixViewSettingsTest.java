package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.MatrixNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphMatrixViewSettingsTest {
    @Test void savesAndRestoresTheSelectedViewWithoutChangingDsp() {
        Map<String, Object> config = DefaultConfig.create();
        config.put("graph_ui_matrix_views", Map.of("route-a", Map.of("matrix-a", "ARROWS")));
        double before = ((Number) ((Map<?, ?>) config.get("mixer")).get("global_volume")).doubleValue();

        assertEquals("ARROWS", GraphMatrixViewSettings.get(config, "route-a", "matrix-a"));
        assertEquals("MATRIX", GraphMatrixViewSettings.get(config, "route-a", "missing"));
        GraphMatrixViewSettings.set(config, "route-a", "matrix-a", "MATRIX");

        assertEquals("MATRIX", GraphMatrixViewSettings.get(config, "route-a", "matrix-a"));
        assertEquals(before, ((Number) ((Map<?, ?>) config.get("mixer")).get("global_volume")).doubleValue());
    }

    @Test void duplicatesViewsWithRemappedNodeIdsAndRemovesRouteState() {
        Map<String, Object> config = DefaultConfig.create();
        GraphMatrixViewSettings.set(config, "route-a", "matrix-a", "ARROWS");

        GraphMatrixViewSettings.duplicateRoute(config, "route-a", "route-b", Map.of("matrix-a", "matrix-b"));
        assertEquals("ARROWS", GraphMatrixViewSettings.get(config, "route-b", "matrix-b"));
        GraphMatrixViewSettings.removeRoute(config, "route-a");
        assertEquals("MATRIX", GraphMatrixViewSettings.get(config, "route-a", "matrix-a"));
    }

    @Test void rejectsUnknownViewsAndValidatorRejectsNonMatrixNodes() {
        assertThrows(IllegalArgumentException.class, () -> GraphMatrixViewSettings.set(
                DefaultConfig.create(), "route-a", "node-a", "OTHER"));
        AudioGraph graph = new AudioGraph("route-a", "Route", List.of(
                new MatrixNode("matrix-a", "Matrix", List.of("FL", "FR"), List.of("in"), List.of("out"), List.of()),
                new SourceNode("source-a", "Source", "application:test", List.of("FL", "FR"))), List.of());
        Map<String, Object> config = DefaultConfig.create();
        config.put("graph_ui_matrix_views", Map.of("route-a", Map.of("source-a", "ARROWS")));
        assertEquals(List.of("graph_ui_matrix_views[route-a] references a missing Matrix node: source-a"),
                validate(config, graph));
    }

    private static List<String> validate(Map<String, Object> config, AudioGraph graph) {
        java.util.List<String> errors = new java.util.ArrayList<>();
        ConfigGraphMatrixViewRules.validate(config, Map.of(graph.id(), graph),
                java.util.Set.of(graph.id()), errors);
        return errors;
    }

}
