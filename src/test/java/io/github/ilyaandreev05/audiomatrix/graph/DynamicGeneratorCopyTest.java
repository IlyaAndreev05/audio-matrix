package io.github.ilyaandreev05.audiomatrix.graph;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.STEREO;
import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.map;
import static org.junit.jupiter.api.Assertions.*;

class DynamicGeneratorCopyTest {
    @Test void copyRemapsConnectionAndInstanceIdsWhileRetainingExtensions() {
        String key = "app:org.example.player";
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("node_ids", Map.of("source", "old-source", "gain", "old-gain"));
        record.put("composite_id", "old-composite");
        record.put("overrides", Map.of("pinned", false));
        record.put("record_extension", Map.of("kept", true));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("registry", "applications");
        parameters.put("connect_to", Map.of("node_id", "mixer", "port_id", "applications", "anchor_extension", "kept"));
        parameters.put("instances", Map.of(key, record));
        parameters.put("parameter_extension", List.of("kept", Map.of("value", 8)));
        DynamicGeneratorNode original = new DynamicGeneratorNode("apps", "Apps", "application:unassigned", STEREO, parameters);

        DynamicGeneratorNode copied = (DynamicGeneratorNode) GraphNodeCopies.copy(original, "apps-copy",
                Map.of("mixer", "mixer-copy"));
        Map<String, Object> copiedRecord = map(copied.instances().get(key));
        Map<String, Object> copiedNodeIds = map(copiedRecord.get("node_ids"));

        assertEquals("mixer-copy", copied.connectTo().get("node_id"));
        assertEquals("kept", copied.connectTo().get("anchor_extension"));
        assertEquals(DynamicInstanceRegistry.stableNodeId("apps-copy", key, "source"), copiedNodeIds.get("source"));
        assertEquals(DynamicInstanceRegistry.stableNodeId("apps-copy", key, "gain"), copiedNodeIds.get("gain"));
        assertEquals(DynamicInstanceRegistry.stableCompositeId("apps-copy", key), copiedRecord.get("composite_id"));
        assertEquals(Map.of("pinned", false), copiedRecord.get("overrides"));
        assertEquals(Map.of("kept", true), copiedRecord.get("record_extension"));
        assertEquals(List.of("kept", Map.of("value", 8)), copied.parameters().get("parameter_extension"));
    }
}
