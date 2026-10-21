package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import org.junit.jupiter.api.Test;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class QuickSettingsConfigTest {
    @Test void defaultsHaveFourStableRussianGroups() {
        List<Map<String, Object>> groups = QuickSettingsConfig.groups(DefaultConfig.create());

        assertEquals(List.of("applications", "outputs", "routing", "events"),
                groups.stream().map(group -> String.valueOf(group.get("id"))).toList());
        assertEquals(List.of("Приложения", "Выходные устройства", "Маршрутизация", "Звуки событий"),
                groups.stream().map(group -> String.valueOf(group.get("name"))).toList());
        assertTrue(groups.stream().noneMatch(group -> Boolean.TRUE.equals(group.get("collapsed"))));
    }

    @Test void defaultPinsExposeMainSourcesAndControlsButNotTheInternalMerge() {
        Map<String, Object> config = DefaultConfig.create();
        List<Map<String, Object>> pins = QuickSettingsConfig.pins(config);

        assertEquals(8, pins.size());
        assertTrue(pins.stream().anyMatch(pin -> "octine-main-source".equals(pin.get("node_id"))
                && "applications".equals(pin.get("group_id"))));
        assertTrue(pins.stream().anyMatch(pin -> "octine-vibro-gain".equals(pin.get("node_id"))
                && "routing".equals(pin.get("group_id"))));
        assertFalse(pins.stream().anyMatch(pin -> Set.of("octine-vibro-mixer", "octine-bass-low-pass")
                .contains(pin.get("node_id"))));
    }

    @Test void migrationDropsBassControlsFromMenuAndKeepsThemInTheArchive() {
        Map<String, Object> config = DefaultConfig.create();
        config.put("schema_version", 9);
        config.put("common_vibro_graph", io.github.ilyaandreev05.audiomatrix.graph.GraphCodec.toMap(
                GraphCommonVibro.defaults()));
        config.put("quick_controls", new ArrayList<>(List.of(DefaultConfig.map(
                "id", "bass-frequency", "route_id", "octine-common-vibro",
                "node_id", "octine-bass-low-pass", "parameter_id", "frequency_hz",
                "section", "Details", "order", 1))));
        config.put("quick_settings", DefaultConfig.map("groups", List.of(
                DefaultConfig.map("id", "sources", "name", "Sources", "order", 0, "collapsed", false),
                DefaultConfig.map("id", "vibro", "name", "Vibro", "order", 1, "collapsed", false)),
                "pins", List.of()));

        ConfigMigration.upgradeConfig(config);

        assertEquals(List.of("applications", "outputs", "routing", "events"),
                QuickSettingsConfig.groups(config).stream().map(group -> String.valueOf(group.get("id"))).toList());
        assertTrue(QuickSettingsConfig.pins(config).stream().noneMatch(pin ->
                "octine-bass-low-pass".equals(pin.get("node_id")) || "octine-vibro-mixer".equals(pin.get("node_id"))));
        assertTrue(ConfigValidationValues.optionalList(config.get("quick_controls"), "quick_controls", new ArrayList<>()).isEmpty());
        Map<String, Object> archive = (Map<String, Object>) config.get("migration_archive");
        Map<String, Object> prior = (Map<String, Object>) archive.get("prior_config");
        assertTrue(prior.toString().contains("bass-frequency"));
        assertEquals(List.of(), ConfigValidator.validate(config));
    }

    @Test void aUserControlOnAnExistingNodeKeepsItsIdAndGroupDuringMigration() {
        Map<String, Object> config = DefaultConfig.create();
        config.put("schema_version", 9);
        config.put("quick_controls", new ArrayList<>(List.of(DefaultConfig.map(
                "id", "my-overall-strength", "route_id", "main", "node_id", "octine-vibro-gain",
                "parameter_id", "gain", "section", "My vibration", "group_id", "routing", "order", 4))));

        ConfigMigration.upgradeConfig(config);

        Map<String, Object> kept = QuickSettingsConfig.groups(config).stream()
                .filter(group -> "routing".equals(group.get("id"))).findFirst().orElseThrow();
        Map<String, Object> control = ConfigValidationValues.optionalList(config.get("quick_controls"),
                "quick_controls", new ArrayList<>()).stream()
                .filter(item -> "my-overall-strength".equals(item.get("id"))).findFirst().orElseThrow();
        assertEquals("Маршрутизация", kept.get("name"));
        assertEquals("routing", control.get("group_id"));
        assertTrue(QuickSettingsConfig.pins(config).stream().anyMatch(pin ->
                "octine-vibro-gain".equals(pin.get("node_id"))
                        && ((List<?>) pin.get("control_ids")).contains("my-overall-strength")));
    }
    @Test void pinDisplayOverridesUnpinWithoutDeletingTheGraphNode() {
        Map<String, Object> config = DefaultConfig.create();
        Map<String, Object> existing = QuickSettingsConfig.pins(config).stream()
                .filter(pin -> "main".equals(pin.get("route_id"))).findFirst().orElseThrow();
        String routeId = String.valueOf(existing.get("route_id"));
        String nodeId = String.valueOf(existing.get("node_id"));
        Map<String, Object> updated = QuickSettingsConfig.updatePin(config, routeId, nodeId, "",
                String.valueOf(existing.get("group_id")), 6, "EXPANDED",
                "My Application Source", "audio-x-generic-symbolic", false, true);
        assertEquals(existing.get("id"), updated.get("id"));
        assertEquals("My Application Source", updated.get("display_name"));
        assertEquals("audio-x-generic-symbolic", updated.get("icon"));
        assertEquals(false, updated.get("show_children"));
        assertEquals(true, updated.get("show_when_offline"));

        assertTrue(QuickSettingsConfig.unpinNode(config, routeId, nodeId, ""));
        assertFalse(QuickSettingsConfig.pins(config).stream().anyMatch(pin ->
                routeId.equals(pin.get("route_id")) && nodeId.equals(pin.get("node_id"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> route = ((List<Map<String, Object>>) config.get("route_library")).stream()
                .filter(item -> routeId.equals(item.get("id"))).findFirst().orElseThrow();
        assertTrue(((List<Map<String, Object>>) route.get("nodes")).stream()
                .anyMatch(node -> nodeId.equals(node.get("id"))), "Unpinning never deletes the graph node");
    }

    @Test void removingAQuickSettingsGroupMovesPinsAndControlsBeforeDeletion() {
        Map<String, Object> config = DefaultConfig.create();
        Map<String, Object> created = QuickSettingsConfig.createGroup(config, "Temporary", 10);
        String groupId = String.valueOf(created.get("id"));
        Map<String, Object> pin = QuickSettingsConfig.pinNode(config, "main", "octine-main-source",
                groupId, 9, "COMPACT");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> controls = (List<Map<String, Object>>) config.get("quick_controls");
        controls.add(DefaultConfig.map("id", "temporary-control", "route_id", "main",
                "node_id", "octine-main-source", "parameter_id", "gain", "section", "Temporary",
                "group_id", groupId, "order", 3));

        QuickSettingsConfig.removeGroup(config, groupId);

        assertFalse(QuickSettingsConfig.groups(config).stream().anyMatch(item -> groupId.equals(item.get("id"))));
        assertEquals("applications", pin.get("group_id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> movedControls = (List<Map<String, Object>>) config.get("quick_controls");
        assertEquals("applications", movedControls.getLast().get("group_id"));
        assertTrue(QuickSettingsConfig.pins(config).contains(pin));
    }
    @Test void generatedUnpinUsesTheSparseInstanceOverride() {
        Map<String, Object> config = DefaultConfig.create();
        String instanceKey = "app:browser-parent:chrome";
        QuickSettingsConfig.pinNode(config, "main", "octine-main-source", instanceKey,
                "applications", 0, "EXPANDED");
        assertTrue(QuickSettingsConfig.unpinNode(config, "main", "octine-main-source", instanceKey));
        assertFalse(QuickSettingsConfig.pins(config).stream().anyMatch(pin ->
                instanceKey.equals(pin.get("instance_key"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> route = ((List<Map<String, Object>>) config.get("route_library")).stream()
                .filter(item -> "main".equals(item.get("id"))).findFirst().orElseThrow();
        AudioGraph graph = GraphCodec.fromMap(route);
        DynamicGeneratorNode generator = graph.nodes().stream()
                .filter(DynamicGeneratorNode.class::isInstance).map(DynamicGeneratorNode.class::cast)
                .filter(node -> "octine-main-source".equals(node.id())).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> instance = (Map<String, Object>) generator.instances().get(instanceKey);
        @SuppressWarnings("unchecked")
        Map<String, Object> overrides = (Map<String, Object>) instance.get("overrides");
        assertEquals(false, overrides.get("pinned"), "Sparse unpin survives automatic pin defaults");
    }

}
