package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigSchema11MigrationTest {
    @Test void schemaTenUpgradeKeepsProfilesRoutesAndUserStateWithoutLegacyReset() {
        Map<String, Object> config = DefaultConfig.create();
        config.put("schema_version", 10);
        Map<String, Object> profile = maps(config.get("profiles")).getFirst();
        profile.put("name", "Пользовательский");
        profile.put("selected_route_id", "main");
        profile.put("default_output", "benq-hdmi");
        config.put("devices", new ArrayList<>(List.of(DefaultConfig.map("id", "benq-hdmi",
                "name", "BenQ PD3200Q", "selector", DefaultConfig.map("kind", "name_equals",
                        "value", "alsa_output.pci-0000_09_00.1.hdmi-stereo"),
                "role", "output", "volume", 0.25, "mute", false))));
        String originalProfileId = String.valueOf(profile.get("id"));
        Map<String, Object> route = maps(config.get("route_library")).getFirst();
        List<String> nodeIds = GraphCodec.fromMap(route).nodes().stream().map(node -> node.id()).toList();
        List<String> edgeIds = GraphCodec.fromMap(route).connections().stream().map(edge -> edge.id()).toList();
        Map<String, Object> layout = DefaultConfig.map("main", DefaultConfig.map(
                nodeIds.getFirst(), DefaultConfig.map("x", 317.0, "y", 91.0)));
        config.put("graph_ui_layout", layout);
        String savedCopyId = GraphRouteLibrary.saveInactiveCopy(config, "main", "Saved main route");
        Map<String, Object> savedCopy = maps(config.get("saved_copies")).getFirst();
        AudioGraph savedGraph = GraphCodec.fromMap(object(savedCopy.get("route")));
        List<String> savedNodeIds = savedGraph.nodes().stream().map(node -> node.id()).toList();
        List<String> savedEdgeIds = savedGraph.connections().stream().map(edge -> edge.id()).toList();
        QuickSettingsConfig.pinNode(config, "main", nodeIds.getFirst(), "", "applications", 7, "DEFAULT");
        List<Map<String, Object>> pins = QuickSettingsConfig.pins(config).stream()
                .map(pin -> (Map<String, Object>) new LinkedHashMap<>(pin)).toList();
        List<Map<String, Object>> appRules = List.of(DefaultConfig.map("id", "firefox-rule",
                "match", DefaultConfig.map("app_id", "org.mozilla.firefox"), "volume", 0.37, "mute", true));
        config.put("applications", new ArrayList<>(appRules));
        Map<String, Object> filters = new LinkedHashMap<>(object(config.get("dsp")));
        Map<String, Object> globalEq = new LinkedHashMap<>(object(filters.get("global_eq")));
        globalEq.put("enabled", true);
        filters.put("global_eq", globalEq);
        config.put("dsp", filters);
        Map<String, Object> archive = DefaultConfig.map("source_schema_version", 8,
                "prior_config", DefaultConfig.map("user_note", "keep existing archive"));
        config.put("migration_archive", archive);
        config.put("active_profile", originalProfileId);
        config.put("active_route", "main");

        ConfigMigration.upgradeConfig(config);

        assertEquals(11, config.get("schema_version"));
        assertEquals(originalProfileId, config.get("active_profile"));
        assertEquals("main", config.get("active_route"));
        assertEquals(List.of(originalProfileId), maps(config.get("profiles")).stream()
                .map(item -> String.valueOf(item.get("id"))).toList());
        assertEquals("Пользовательский", maps(config.get("profiles")).getFirst().get("name"));
        assertEquals("main", maps(config.get("profiles")).getFirst().get("selected_route_id"));
        assertEquals("benq-hdmi", maps(config.get("profiles")).getFirst().get("default_output"));
        Map<String, Object> migratedRoute = maps(config.get("route_library")).getFirst();
        AudioGraph migratedGraph = GraphCodec.fromMap(migratedRoute);
        assertTrue(migratedGraph.nodes().stream().map(node -> node.id()).toList().containsAll(nodeIds));
        assertTrue(migratedGraph.connections().stream().map(edge -> edge.id()).toList().containsAll(edgeIds));
        assertTrue(migratedGraph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).anyMatch(node -> "outputs".equals(node.registry())));
        assertEquals(layout, config.get("graph_ui_layout"));
        Map<String, Object> migratedCopy = maps(config.get("saved_copies")).stream()
                .filter(copy -> savedCopyId.equals(copy.get("id"))).findFirst().orElseThrow();
        AudioGraph restoredSnapshot = GraphCodec.fromMap(object(migratedCopy.get("route")));
        assertTrue(restoredSnapshot.nodes().stream().map(node -> node.id()).toList().containsAll(savedNodeIds));
        assertTrue(restoredSnapshot.connections().stream().map(edge -> edge.id()).toList().containsAll(savedEdgeIds));
        assertEquals(pins, QuickSettingsConfig.pins(config));
        assertEquals(appRules, config.get("applications"));
        assertEquals(filters, config.get("dsp"));
        assertEquals(archive.get("prior_config"), priorArchive(config));
        assertNotNull(config.get("migration_archive"));
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> priorArchive(Map<String, Object> config) {
        return object(object(config.get("migration_archive")).get("prior_config"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
