package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GraphSwitchMigrationTest {
    private static final List<String> CHANNELS = List.of("FL", "FR");

    @Test void migratesNestedCopiesWithoutChangingAnySelectedBranch() {
        Map<String, Object> single = node("switch", List.of("in->a"));
        Map<String, Object> multiple = node("multi_switch", List.of("in->a", "in->b"));
        Map<String, Object> config = new LinkedHashMap<>(Map.of(
                "route_library", List.of(Map.of("nodes", List.of(single))),
                "saved_copies", List.of(Map.of("route", Map.of("nodes", List.of(multiple))))));
        GraphSwitchMigration.upgrade(config);
        String first = config.toString();
        assertFalse(first.contains("multi_switch"));
        assertEquals(List.of("in->a"), parameters(firstNode(((List<?>)config.get("route_library")).getFirst())).get("active_links"));
        Map<?,?> copy = (Map<?,?>) ((List<?>)config.get("saved_copies")).getFirst();
        assertEquals(List.of("in->a", "in->b"), parameters(firstNode(copy.get("route"))).get("active_links"));
        GraphSwitchMigration.upgrade(config);
        assertEquals(first, config.toString());
    }

    @Test void schemaSevenUpgradeKeepsProfilesRoutesAndTheActiveSelection() {
        Map<String, Object> profile = new LinkedHashMap<>(Map.of("id", "desk", "name", "Desk", "mode", "hybrid",
                "default_output", "system_default", "selected_route_id", "route-1"));
        Map<String, Object> route = new LinkedHashMap<>(Map.of("id", "route-1", "name", "Legacy route",
                "profile_id", "desk", "group_id", "", "nodes", List.of(node("switch", List.of("in->a"))),
                "connections", List.of()));
        Map<String, Object> config = new LinkedHashMap<>(Map.of("schema_version", 7, "active_profile", "desk",
                "active_route", "route-1", "profiles", List.of(profile), "route_library", List.of(route)));

        ConfigMigration.upgradeConfig(config);

        assertEquals(DefaultConfig.SCHEMA_VERSION, config.get("schema_version"));
        @SuppressWarnings("unchecked") Map<String, Object> storedProfile =
                (Map<String, Object>) ((List<?>) config.get("profiles")).getFirst();
        @SuppressWarnings("unchecked") Map<String, Object> storedRoute =
                (Map<String, Object>) ((List<?>) config.get("route_library")).getFirst();
        assertEquals("desk", config.get("active_profile"));
        assertEquals("route-1", config.get("active_route"));
        assertEquals("route-1", storedProfile.get("selected_route_id"));
        assertEquals("Legacy route", storedRoute.get("name"));
        assertEquals("desk", storedRoute.get("profile_id"));
        assertEquals("switch", firstNode(storedRoute).get("type"));
        assertEquals(List.of("in->a"), parameters(firstNode(storedRoute)).get("active_links"));
        assertEquals(false, config.get("octine_profiles_available"));
    }

    @Test void legacyCodecAlwaysWritesCanonicalMultiSelectionSwitch() {
        for (String type : List.of("switch", "multi_switch")) {
            List<String> links = type.equals("switch") ? List.of("in->a") : List.of("in->a", "in->b");
            AudioGraph graph = GraphCodec.fromMap(Map.of("id", "route", "name", "Legacy", "nodes", List.of(node(type, links)), "connections", List.of()));
            SwitchNode decoded = assertInstanceOf(SwitchNode.class, graph.nodes().getFirst());
            assertEquals(links, decoded.activeLinks());
            assertTrue(decoded.multiple());
            assertEquals("switch", firstNode(GraphCodec.toMap(graph)).get("type"));
        }
    }

    private static Map<String,Object> node(String type, List<String> links) {
        return Map.of("id", "switch", "name", "Switch", "type", type, "channels", CHANNELS,
                "parameters", Map.of("output_ports", List.of("a", "b"), "active_links", links));
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> firstNode(Object graph) {
        return (Map<String,Object>) ((List<?>) ((Map<?,?>)graph).get("nodes")).getFirst();
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> parameters(Map<String,Object> node) {
        return (Map<String,Object>)node.get("parameters");
    }
}
