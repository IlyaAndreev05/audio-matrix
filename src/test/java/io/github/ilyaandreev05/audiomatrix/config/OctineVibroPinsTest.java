package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OctineVibroPinsTest {
    @Test void presetKeepsBassControlsWithoutPinningVibroMixer() {
        Map<String, Object> config = DefaultConfig.create();
        QuickSettingsConfig.groups(config).add(DefaultConfig.map("id", "sources", "name", "Источники", "order", 4));
        OctineProfileConfig.install(config);
        assertNull(QuickSettingsConfig.findPin(config, GraphCommonVibro.COMMON_ROUTE_ID,
                GraphCommonVibro.VIBRO_MIXER_ID, "", ""));
        assertNotNull(QuickSettingsConfig.findPin(config, GraphCommonVibro.COMMON_ROUTE_ID,
                GraphCommonVibro.VIBRO_GAIN_ID, "", ""));
        assertTrue(maps(config.get("quick_controls")).stream()
                .anyMatch(control -> "octine-bass-frequency".equals(control.get("id"))));
        assertTrue(maps(config.get("quick_controls")).stream()
                .anyMatch(control -> "octine-bass-slope".equals(control.get("id"))));
    }

    @Test void legacyMixerPinStillProvidesBassControlGroup() {
        Map<String, Object> config = DefaultConfig.create();
        GraphCommonVibro.save(config, GraphCommonVibro.defaults());
        QuickSettingsConfig.pinNode(config, GraphCommonVibro.COMMON_ROUTE_ID,
                GraphCommonVibro.VIBRO_MIXER_ID, "routing", 0, "DEFAULT");
        QuickSettingsConfig.migrateVibroBassControls(config);
        Map<String, Object> filter = QuickSettingsConfig.findPin(config, GraphCommonVibro.COMMON_ROUTE_ID,
                "octine-bass-low-pass", "", "");
        assertNotNull(filter);
        assertEquals("routing", filter.get("group_id"));
        assertTrue(maps(config.get("quick_controls")).stream()
                .anyMatch(control -> "octine-bass-frequency".equals(control.get("id"))));
    }
    @Test void gainPinProvidesBassControlGroupWhenMixerPinIsMissing() {
        Map<String, Object> config = DefaultConfig.create();
        GraphCommonVibro.save(config, GraphCommonVibro.defaults());
        QuickSettingsConfig.pinNode(config, GraphCommonVibro.COMMON_ROUTE_ID,
                GraphCommonVibro.VIBRO_GAIN_ID, "routing", 0, "DEFAULT");
        QuickSettingsConfig.migrateVibroBassControls(config);
        assertEquals("routing", QuickSettingsConfig.findPin(config, GraphCommonVibro.COMMON_ROUTE_ID,
                "octine-bass-low-pass", "", "").get("group_id"));
    }

    @Test void missingOrIncompatibleBassNodeDoesNotCreatePinOrControls() {
        AudioGraph defaults = GraphCommonVibro.defaults();
        for (int variant = 0; variant < 3; variant++) {
            Map<String, Object> config = DefaultConfig.create();
            List<GraphNode> nodes = new ArrayList<>(defaults.nodes().stream()
                    .filter(node -> !"octine-bass-low-pass".equals(node.id())).toList());
            if (variant == 1) nodes.add(new GainNode("octine-bass-low-pass", "Changed", List.of("FL", "FR"), 1, false));
            if (variant == 2) nodes.add(new FilterNode("octine-bass-low-pass", "Changed", List.of("FL", "FR"),
                    "high_pass", true, Map.of("frequency_hz", 60.0)));
            AudioGraph graph = new AudioGraph(defaults.id(), defaults.name(), nodes, defaults.connections());
            config.put("common_vibro_graph", GraphCodec.toMap(graph));
            QuickSettingsConfig.pinNode(config, GraphCommonVibro.COMMON_ROUTE_ID,
                    GraphCommonVibro.VIBRO_GAIN_ID, "routing", 0, "DEFAULT");
            QuickSettingsConfig.migrateVibroBassControls(config);
            assertNull(QuickSettingsConfig.findPin(config, GraphCommonVibro.COMMON_ROUTE_ID,
                    "octine-bass-low-pass", "", ""));
            assertTrue(maps(config.get("quick_controls")).stream()
                    .noneMatch(control -> "octine-bass-frequency".equals(control.get("id"))
                            || "octine-bass-slope".equals(control.get("id"))));
        }
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
