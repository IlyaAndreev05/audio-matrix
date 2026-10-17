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
        assertEquals(4, config.get("schema_version"));
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
}
