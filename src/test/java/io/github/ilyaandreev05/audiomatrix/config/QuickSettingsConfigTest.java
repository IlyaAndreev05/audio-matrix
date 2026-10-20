package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import org.junit.jupiter.api.Test;

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
}
