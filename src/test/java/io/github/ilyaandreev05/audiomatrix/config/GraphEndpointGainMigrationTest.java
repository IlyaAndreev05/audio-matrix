package io.github.ilyaandreev05.audiomatrix.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphPipeWireCompiler;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEndpointGainMigrationTest {
    @TempDir Path temp;

    @Test void schemaFiveMigrationBacksUpTheFileAndKeepsEveryLegacyRouteFactor() throws Exception {
        Path file = temp.resolve("config.json");
        byte[] oldBytes = new ObjectMapper().writeValueAsBytes(schemaFiveConfig());
        Files.write(file, oldBytes);

        try (ConfigStore store = new ConfigStore(file)) {
            Map<String, Object> migrated = store.snapshot();
            Map<String, Object> route = route(migrated);
            AudioGraph graph = GraphCodec.fromMap(route);
            Map<String, Object> compiled = GraphPipeWireCompiler.compile(graph, migrated);
            @SuppressWarnings("unchecked") Map<String, Object> active =
                    (Map<String, Object>) ((List<?>) compiled.get("routes")).getFirst();

            assertEquals(7, migrated.get("schema_version"));
            assertTrue(java.util.Arrays.equals(oldBytes,
                    Files.readAllBytes(temp.resolve("config.json.before-schema-7"))));
            assertEquals(3, graph.nodes().stream().filter(GainNode.class::isInstance).count());
            assertEquals(0.37, active.get("volume"));
            assertTrue(graph.connections().stream().allMatch(edge -> edge.gain() == 1.0));
            assertTrue(graph.nodes().stream().filter(GainNode.class::isInstance).map(GainNode.class::cast)
                    .anyMatch(gain -> gain.endpointNodeId().isBlank() && gain.gain() == 0.37));
            assertTrue(((List<?>) migrated.get("quick_controls")).stream().anyMatch(item ->
                    item.toString().contains("endpoint-gain") && item.toString().contains("gain")));
            assertEquals(List.of(), ConfigValidator.validate(migrated));
        }
    }

    @Test void schemaSixConfigMigrationPreservesLegacyConnectionLevelsInGainNodes() throws Exception {
        Path file = temp.resolve("config-v6.json");
        Map<String, Object> old = schemaFiveConfig();
        old.put("schema_version", 6);
        byte[] oldBytes = new ObjectMapper().writeValueAsBytes(old);
        Files.write(file, oldBytes);

        try (ConfigStore store = new ConfigStore(file)) {
            Map<String, Object> migrated = store.snapshot();
            AudioGraph graph = GraphCodec.fromMap(route(migrated));
            assertEquals(7, migrated.get("schema_version"));
            assertTrue(java.util.Arrays.equals(oldBytes, Files.readAllBytes(temp.resolve("config-v6.json.before-schema-7"))));
            assertTrue(graph.connections().stream().allMatch(edge -> edge.gain() == 1.0));
            assertTrue(graph.nodes().stream().filter(GainNode.class::isInstance).map(GainNode.class::cast)
                    .anyMatch(gain -> gain.endpointNodeId().isBlank() && gain.gain() == 0.37));
            assertEquals(List.of(), ConfigValidator.validate(migrated));
        }
    }

    private Map<String, Object> schemaFiveConfig() {
        Map<String, Object> config = DefaultConfig.create();
        config.put("schema_version", 5);
        AudioGraph graph = new AudioGraph("desk-route", "Desk", List.of(
                new SourceNode("source-a", "Chrome", "application:chrome", List.of("FL", "FR")),
                new OutputNode("output-a", "Monitor", "monitor", List.of("FL", "FR"))),
                List.of(new io.github.ilyaandreev05.audiomatrix.graph.GraphConnection(
                        "link-a", "source-a", "out", "output-a", "in", true, 0.37)));
        Map<String, Object> encoded = new LinkedHashMap<>(GraphCodec.toMap(graph));
        encoded.put("profile_id", "desk");
        encoded.put("group_id", "");
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes =
                (List<Map<String, Object>>) config.get("route_library");
        routes.add(encoded);
        QuickSettingsConfig.migrateNodePins(config);
        return config;
    }

    private static Map<String, Object> route(Map<String, Object> config) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes =
                (List<Map<String, Object>>) config.get("route_library");
        return routes.getFirst();
    }
}
