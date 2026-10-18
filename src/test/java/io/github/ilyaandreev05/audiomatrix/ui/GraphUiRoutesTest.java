package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GraphUiRoutesTest {
    @Test void commonGraphIsTheSharedRootNotARouteCopy() {
        Map<String, Object> common = new LinkedHashMap<>(Map.of("id", GraphCommonVibro.COMMON_ROUTE_ID, "name", "Shared", "nodes", List.of(), "connections", List.of()));
        Map<String, Object> config = new LinkedHashMap<>(Map.of(
                "active_route", "route-a",
                "common_vibro_graph", common,
                "route_library", List.of(Map.of("id", "route-a", "name", "A", "nodes", List.of(), "connections", List.of()))));
        assertSame(common, GraphUiRoutes.route(config, GraphCommonVibro.COMMON_ROUTE_ID));
        assertTrue(GraphUiRoutes.available(config, GraphCommonVibro.COMMON_ROUTE_ID));
        assertFalse(GraphUiRoutes.available(config, "route-b"));
        assertEquals("A", GraphUiRoutes.route(config, "route-a").get("name"));
        assertThrows(IllegalArgumentException.class, () -> GraphUiRoutes.route(config, "route-b"));
    }

    @Test void commonGraphRequiresPersistedRoot() {
        Map<String, Object> config = new LinkedHashMap<>(Map.of("active_route", "route-a", "route_library", List.of()));
        assertThrows(IllegalArgumentException.class, () -> GraphUiRoutes.route(config, GraphCommonVibro.COMMON_ROUTE_ID));
    }
}
