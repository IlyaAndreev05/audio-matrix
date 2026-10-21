package io.github.ilyaandreev05.audiomatrix.graph;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.STEREO;
import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.map;
import static org.junit.jupiter.api.Assertions.*;

class DynamicGeneratorNodeTest {
    @Test void generatorHasNoPcmPortsAndRoundTripsParametersAndInstanceExtensions() {
        String key = "app:org.example.player";
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("node_ids", Map.of(
                "source", DynamicInstanceRegistry.stableNodeId("apps", key, "source"),
                "gain", DynamicInstanceRegistry.stableNodeId("apps", key, "gain")));
        instance.put("composite_id", DynamicInstanceRegistry.stableCompositeId("apps", key));
        instance.put("overrides", Map.of("icon", "audio-x-generic", "display_name", "Player"));
        instance.put("instance_extension", Map.of("nested", List.of("kept", 7)));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("instances", Map.of(key, instance));
        parameters.put("parameter_extension", Map.of("nested", List.of(1, true, "kept")));

        DynamicGeneratorNode generator = new DynamicGeneratorNode(
                "apps", "Applications", "application:unassigned", STEREO, parameters);

        assertTrue(generator.ports().isEmpty());
        assertFalse(SourceNode.class.isAssignableFrom(generator.getClass()));
        assertEquals(STEREO, generator.channels());
        assertEquals("applications", generator.registry());
        assertEquals("default-application", generator.template());
        assertEquals(List.of("source", "gain"), generator.templateNodes().stream().map(node -> node.get("id")).toList());
        assertEquals(Map.of("nested", List.of(1, true, "kept")), generator.parameters().get("parameter_extension"));

        AudioGraph roundTripped = GraphCodec.fromMap(GraphCodec.toMap(
                new AudioGraph("route", "Route", List.of(generator), List.of())));
        DynamicGeneratorNode decoded = (DynamicGeneratorNode) roundTripped.nodes().getFirst();

        assertTrue(decoded.ports().isEmpty());
        assertEquals(generator.parameters(), decoded.parameters());
        assertEquals(generator.instances().get(key), decoded.instances().get(key));
        assertEquals(List.of(), GraphValidator.validate(roundTripped));

        // Ordinary application levels live in applications[] rules, never in instance overrides.
        assertTrue(GraphValidator.validate(overrideGraph("applications", "application:unassigned",
                Map.of("muted", true))).stream().anyMatch(message ->
                message.contains("must keep muted in applications[]")));
        assertTrue(GraphValidator.validate(overrideGraph("applications", "application:unassigned",
                Map.of("gain", 0.4))).stream().anyMatch(message ->
                message.contains("must keep gain in applications[]")));
        // Browser tabs keep their own gain and mute in sparse instance overrides.
        assertEquals(List.of(), GraphValidator.validate(overrideGraph("browser_tabs", "browser:*",
                Map.of("gain", 0.4, "muted", true))));
    }

    private static AudioGraph overrideGraph(String registry, String selector, Map<String, Object> overrides) {
        String key = "app:org.example.player";
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("node_ids", Map.of(
                "source", DynamicInstanceRegistry.stableNodeId("apps", key, "source"),
                "gain", DynamicInstanceRegistry.stableNodeId("apps", key, "gain")));
        instance.put("composite_id", DynamicInstanceRegistry.stableCompositeId("apps", key));
        instance.put("overrides", overrides);
        DynamicGeneratorNode generator = new DynamicGeneratorNode("apps", "Applications", selector, STEREO,
                Map.of("registry", registry, "instances", Map.of(key, instance)));
        return new AudioGraph("route", "Route", List.of(generator), List.of());
    }

    @Test void defaultsSelectTheRegistrySpecificTemplateGraphs() {
        DynamicGeneratorNode applications = new DynamicGeneratorNode("apps", "Apps", "application:unassigned", STEREO);
        DynamicGeneratorNode tabs = new DynamicGeneratorNode("tabs", "Tabs", "browser:*", STEREO,
                Map.of("registry", "browser_tabs"));
        DynamicGeneratorNode outputs = new DynamicGeneratorNode("outputs", "Outputs", "device:*", STEREO,
                Map.of("registry", "outputs"));

        assertEquals("default-application", applications.template());
        assertEquals(List.of("source", "gain"), applications.templateNodes().stream().map(node -> node.get("id")).toList());
        assertEquals("source-gain", applications.templateConnections().getFirst().get("id"));
        assertEquals("default-tab", tabs.template());
        assertEquals(List.of("source", "gain"), tabs.templateNodes().stream().map(node -> node.get("id")).toList());
        assertEquals("default-output", outputs.template());
        assertEquals(List.of("gain", "output"), outputs.templateNodes().stream().map(node -> node.get("id")).toList());
        assertEquals("device:unbound", map(outputs.templateNodes().get(1).get("parameters")).get("device_id"));
        assertEquals(1.0, applications.initialGain());
        assertTrue(applications.autoPin());
        assertTrue(applications.retainOnDisappear());
        assertTrue(applications.restoreOnReappear());
        assertEquals(List.of(), applications.excludedSelectors());
        assertEquals(Map.of(), applications.connectTo());
    }
}
