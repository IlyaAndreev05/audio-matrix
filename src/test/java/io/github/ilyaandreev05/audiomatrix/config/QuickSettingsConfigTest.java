package io.github.ilyaandreev05.audiomatrix.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickSettingsConfigTest {
    @Test void schemaThreeControlsMigrateIntoNamedStableGroups() {
        Map<String, Object> config = DefaultConfig.create();
        config.put("schema_version", 3);
        config.put("quick_controls", List.of(DefaultConfig.map(
                "id", "bass", "route_id", "route-1", "node_id", "gain-1", "parameter_id", "gain",
                "section", "Bass and events", "label", "Bass", "order", 2)));

        ConfigMigration.upgradeConfig(config);

        Map<String, Object> control = ConfigValidationValues.optionalList(config.get("quick_controls"),
                "quick_controls", new java.util.ArrayList<>()).getFirst();
        String groupId = String.valueOf(control.get("group_id"));
        Map<String, Object> group = QuickSettingsConfig.groups(config).stream()
                .filter(candidate -> groupId.equals(candidate.get("id"))).findFirst().orElse(null);
        assertEquals(5, config.get("schema_version"));
        assertNotNull(group);
        assertEquals("Bass and events", group.get("name"));
        assertEquals("Bass and events", control.get("section"));
        assertTrue(Boolean.FALSE.equals(group.get("collapsed")));
    }

    @Test void defaultConfigurationHasTheFourSuggestedDisplayGroups() {
        List<Map<String, Object>> groups = QuickSettingsConfig.groups(DefaultConfig.create());

        assertEquals(List.of("sources", "outputs", "routing", "effects"),
                groups.stream().map(group -> String.valueOf(group.get("id"))).toList());
        assertFalse(groups.getFirst().get("collapsed").equals(true));
    }

    @Test void migrationGroupsLegacyControlsAndPinsRouteSourcesAndOutputs() {
        Map<String, Object> config = DefaultConfig.create();
        config.put("schema_version", 4);
        config.put("quick_controls", List.of(DefaultConfig.map(
                "id", "gain-control", "route_id", "route-1", "node_id", "gain-1", "parameter_id", "gain",
                "section", "Коммутация и управление", "label", "Bass", "order", 1)));
        Map<String, Object> route = DefaultConfig.map(
                "id", "route-1", "name", "Desk", "profile_id", "desk", "group_id", "", "nodes", List.of(
                        DefaultConfig.map("id", "source-1", "name", "Chrome", "type", "source",
                                "channels", List.of("FL", "FR"), "parameters", DefaultConfig.map("selector", "application:chrome")),
                        DefaultConfig.map("id", "gain-1", "name", "Bass", "type", "gain",
                                "channels", List.of("FL", "FR"), "parameters", DefaultConfig.map("gain", 0.5, "muted", false)),
                        DefaultConfig.map("id", "out-1", "name", "Monitor", "type", "output",
                                "channels", List.of("FL", "FR"), "parameters", DefaultConfig.map("device_id", "monitor"))),
                "connections", List.of());
        config.put("route_library", List.of(route));

        ConfigMigration.upgradeConfig(config);

        List<Map<String, Object>> pins = QuickSettingsConfig.pins(config);
        assertEquals(5, config.get("schema_version"));
        assertEquals(3, pins.size());
        assertTrue(pins.stream().anyMatch(pin -> "source-1".equals(pin.get("node_id"))
                && "sources".equals(pin.get("group_id"))));
        assertTrue(pins.stream().anyMatch(pin -> "out-1".equals(pin.get("node_id"))
                && "outputs".equals(pin.get("group_id"))));
        Map<String, Object> gainPin = pins.stream().filter(pin -> "gain-1".equals(pin.get("node_id"))).findFirst().orElseThrow();
        assertEquals(List.of("gain-control"), gainPin.get("control_ids"));
        assertEquals(List.of(), ConfigValidator.validate(config));
    }
}
