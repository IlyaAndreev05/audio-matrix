package io.github.ilyaandreev05.audiomatrix.graph;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.STEREO;
import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.map;
import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.maps;
import static org.junit.jupiter.api.Assertions.*;

class DynamicGeneratorSchemaUpgradeTest {
    @Test void schemaUpgradeDisablesLegacyOutputAutoSelectionButPreservesExplicitPolicy() {
        Map<String, Object> legacyRoute = Map.of("id", "legacy-output", "name", "Legacy output",
                "nodes", List.of(legacyOutputGenerator("legacy", false, false)), "connections", List.of());
        Map<String, Object> explicitRoute = Map.of("id", "explicit-output", "name", "Explicit output",
                "nodes", List.of(legacyOutputGenerator("explicit", true, true)), "connections", List.of());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("route_library", List.of(legacyRoute, explicitRoute));

        List<Map<String, Object>> upgraded = maps(GraphCodec.upgradeSchema10Config(config).get("route_library"));
        Map<String, Object> legacyParameters = map(maps(upgraded.get(0).get("nodes")).getFirst().get("parameters"));
        Map<String, Object> explicitParameters = map(maps(upgraded.get(1).get("nodes")).getFirst().get("parameters"));

        assertEquals(Boolean.FALSE, legacyParameters.get("auto_select_trusted_hdmi_on_first_discovery"));
        assertEquals(Boolean.TRUE, explicitParameters.get("auto_select_trusted_hdmi_on_first_discovery"));
        DynamicGeneratorNode fresh = new DynamicGeneratorNode("fresh-output", "Fresh", "device:*", STEREO,
                Map.of("registry", "outputs"));
        assertFalse(fresh.autoSelectTrustedHdmiOnFirstDiscovery());
        assertTrue(fresh.withParameter("auto_select_trusted_hdmi_on_first_discovery", true)
                .autoSelectTrustedHdmiOnFirstDiscovery());
        DynamicGeneratorNode updated = fresh
                .withParameter("auto_select_trusted_hdmi_on_first_discovery", false)
                .withParameter("extension", "retained");
        assertFalse(updated.autoSelectTrustedHdmiOnFirstDiscovery());
        assertEquals("retained", updated.parameters().get("extension"));
    }

    @Test void schemaTenUpgradeNormalizesOnlyGeneratorParametersAndInfersLegacyTarget() {
        Map<String, Object> legacyEdge = Map.of("id", "legacy-link", "from_node", "apps", "from_port", "out",
                "to_node", "mixer", "to_port", "applications", "enabled", true, "gain", 1.0);
        Map<String, Object> legacyNode = legacyGenerator("apps");
        Map<String, Object> otherNode = Map.of("id", "mixer", "name", "Mixer", "type", "gain",
                "channels", STEREO, "parameters", Map.of("gain", 1.0, "muted", false), "extension", "untouched");
        Object extension = Map.of("preserved", List.of("value"));
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("id", "route-id");
        route.put("name", "Route");
        route.put("route_extension", Map.of("nested", "untouched"));
        route.put("nodes", new ArrayList<>(List.of(legacyNode, otherNode)));
        route.put("connections", new ArrayList<>(List.of(legacyEdge)));
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("id", "copy-id");
        copy.put("copy_extension", "untouched");
        copy.put("route", route);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("opaque", extension);
        config.put("route_library", new ArrayList<>(List.of(route)));
        config.put("saved_copies", new ArrayList<>(List.of(copy)));
        config.put("common_vibro_graph", route);

        Map<String, Object> upgraded = GraphCodec.upgradeSchema10Config(config);
        Map<String, Object> upgradedRoute = maps(upgraded.get("route_library")).getFirst();
        Map<String, Object> upgradedNode = maps(upgradedRoute.get("nodes")).getFirst();
        Map<String, Object> upgradedParameters = map(upgradedNode.get("parameters"));
        Map<String, Object> upgradedCopy = maps(upgraded.get("saved_copies")).getFirst();
        Map<String, Object> copiedRoute = map(upgradedCopy.get("route"));
        Map<String, Object> copiedGenerator = maps(copiedRoute.get("nodes")).getFirst();
        Map<String, Object> commonGenerator = maps(map(upgraded.get("common_vibro_graph")).get("nodes")).getFirst();

        assertEquals(extension, upgraded.get("opaque"));
        assertEquals("route-id", upgradedRoute.get("id"));
        assertEquals("route-id", copiedRoute.get("id"));
        assertEquals("copy-id", upgradedCopy.get("id"));
        assertEquals("untouched", map(upgradedRoute.get("route_extension")).get("nested"));
        assertEquals("untouched", maps(upgradedRoute.get("nodes")).get(1).get("extension"));
        assertEquals(Map.of("retained", List.of(3, "x")), upgradedParameters.get("generator_extension"));
        assertEquals(Map.of("retained", true), upgradedNode.get("node_extension"));
        assertEquals(List.of(legacyEdge), upgradedRoute.get("connections"));
        assertEquals(Map.of("node_id", "mixer", "port_id", "applications"), upgradedParameters.get("connect_to"));
        assertEquals("applications", upgradedParameters.get("registry"));
        assertEquals("default-application", upgradedParameters.get("template"));
        assertTrue(upgradedParameters.containsKey("template_nodes"));
        assertTrue(upgradedParameters.containsKey("template_connections"));
        assertTrue(map(copiedGenerator.get("parameters")).containsKey("instances"));
        assertTrue(map(commonGenerator.get("parameters")).containsKey("restore_on_reappear"));
        assertEquals("application:unassigned", map(legacyNode.get("parameters")).get("selector"));
        assertFalse(map(legacyNode.get("parameters")).containsKey("registry"));
    }

    private static Map<String, Object> legacyOutputGenerator(String id, boolean includePolicy, boolean policy) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("selector", "device:*");
        parameters.put("registry", "outputs");
        if (includePolicy) parameters.put("auto_select_trusted_hdmi_on_first_discovery", policy);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("name", "Outputs");
        node.put("type", "dynamic_generator");
        node.put("channels", STEREO);
        node.put("parameters", parameters);
        return node;
    }

    private static Map<String, Object> legacyGenerator(String id) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("name", "Applications");
        node.put("type", "dynamic_generator");
        node.put("channels", STEREO);
        node.put("parameters", new LinkedHashMap<>(Map.of("selector", "application:unassigned",
                "generator_extension", Map.of("retained", List.of(3, "x")))));
        node.put("node_extension", Map.of("retained", true));
        return node;
    }
}
