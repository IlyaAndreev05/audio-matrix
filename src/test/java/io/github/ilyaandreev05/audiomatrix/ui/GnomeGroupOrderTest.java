package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GnomeGroupOrderTest {
    @Test void dragChangesCanonicalOrderAndKeepsNamesAndDisclosureState() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("quick_settings", QuickSettingsConfig.defaults());
        Map<String, Object> source = QuickSettingsConfig.groups(config).getFirst();
        source.put("name", "My arbitrary sources");
        source.put("collapsed", true);
        GnomeGroupOrder.apply(config, "sources\t3");
        List<Map<String, Object>> ordered = QuickSettingsConfig.groups(config).stream()
                .sorted(Comparator.comparingInt(group -> ((Number) group.get("order")).intValue())).toList();
        assertEquals(List.of("outputs", "routing", "effects", "sources"), ordered.stream().map(group -> group.get("id")).toList());
        assertEquals("My arbitrary sources", ordered.getLast().get("name"));
        assertEquals(true, ordered.getLast().get("collapsed"));
    }

    @Test void staleOrMalformedTargetsDoNotChangeOrder() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("quick_settings", QuickSettingsConfig.defaults());
        assertThrows(IllegalArgumentException.class, () -> GnomeGroupOrder.apply(config, "missing\t0"));
        assertThrows(IllegalArgumentException.class, () -> GnomeGroupOrder.apply(config, "sources\t-1"));
        assertThrows(IllegalArgumentException.class, () -> GnomeGroupOrder.apply(config, "sources\t4"));
        assertThrows(IllegalArgumentException.class, () -> GnomeGroupOrder.apply(config, "sources\tNaN"));
        assertEquals(0, QuickSettingsConfig.groups(config).getFirst().get("order"));
    }
}
