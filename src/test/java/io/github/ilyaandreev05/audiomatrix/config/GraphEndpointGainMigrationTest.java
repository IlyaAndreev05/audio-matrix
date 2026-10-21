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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEndpointGainMigrationTest {
    @TempDir Path temp;

    @Test void schemaFiveMigrationBacksUpPriorGraphAndKeepsItInTheMigrationArchive() throws Exception {
        Path file = temp.resolve("config.json");
        byte[] oldBytes = new ObjectMapper().writeValueAsBytes(schemaFiveConfig());
        Files.write(file, oldBytes);

        try (ConfigStore store = new ConfigStore(file)) {
            Map<String, Object> migrated = store.snapshot();
            assertEquals(DefaultConfig.SCHEMA_VERSION, migrated.get("schema_version"));
            assertTrue(java.util.Arrays.equals(oldBytes,
                    Files.readAllBytes(temp.resolve("config.json.before-schema-"+DefaultConfig.SCHEMA_VERSION))));
            Map<String, Object> prior = prior(migrated);
            Map<String, Object> archived = route(prior, "desk-route");
            AudioGraph archivedGraph = GraphCodec.fromMap(archived);
            assertTrue(archivedGraph.connections().stream().anyMatch(edge -> edge.gain() == 0.37));
            assertEquals("main", migrated.get("active_route"));
            AudioGraph graph = GraphCodec.fromMap(route(migrated));
            Map<String, Object> compiled = GraphPipeWireCompiler.compile(graph, migrated);
            assertFalse(((List<?>) compiled.get("routes")).stream().anyMatch(item ->
                    "system_default".equals(((Map<?, ?>) item).get("to"))),
                    "the shipped graph has no system_default placeholder output");
            assertEquals(List.of(), ConfigValidator.validate(migrated));
        }
    }

    @Test void schemaSixMigrationLeavesLegacyConnectionLevelsUnchangedInTheArchive() throws Exception {
        Path file = temp.resolve("config-v6.json");
        Map<String, Object> old = schemaFiveConfig();
        old.put("schema_version", 6);
        byte[] oldBytes = new ObjectMapper().writeValueAsBytes(old);
        Files.write(file, oldBytes);

        try (ConfigStore store = new ConfigStore(file)) {
            Map<String, Object> migrated = store.snapshot();
            assertEquals(DefaultConfig.SCHEMA_VERSION, migrated.get("schema_version"));
            assertTrue(java.util.Arrays.equals(oldBytes, Files.readAllBytes(temp.resolve("config-v6.json.before-schema-"+DefaultConfig.SCHEMA_VERSION))));
            assertEquals(0.37, GraphCodec.fromMap(route(prior(migrated), "desk-route")).connections().getFirst().gain());
            assertEquals("main", migrated.get("active_route"));
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
        encoded.put("profile_id", "monitor");
        encoded.put("group_id", "");
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes =
                (List<Map<String, Object>>) config.get("route_library");
        routes.add(encoded);
        QuickSettingsConfig.pins(config).clear();
        QuickSettingsConfig.migrateNodePins(config);
        return config;
    }

    private static Map<String, Object> route(Map<String, Object> config) {
        String routeId = config.get("active_route") instanceof String value ? value : "main";
        return route(config, routeId);
    }

    private static Map<String, Object> route(Map<String, Object> config, String routeId) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes =
                (List<Map<String, Object>>) config.get("route_library");
        return routes.stream().filter(item -> routeId.equals(item.get("id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> prior(Map<String, Object> config) {
        return (Map<String, Object>) ((Map<String, Object>) config.get("migration_archive")).get("prior_config");
    }
}
