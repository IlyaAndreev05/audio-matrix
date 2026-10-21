package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DynamicApplicationRuleTest {
    @Test void firstReliableApplicationDiscoveryAddsOneCanonicalRuleAndLaterSnapshotsDoNotWrite() {
        Map<String, Object> config = config(new ArrayList<>());
        DynamicInstanceRegistry.InstanceSnapshot first = DynamicInstanceRegistry.application(
                stream(14, "org.example.player", "music-player", "Music"));
        DynamicInstanceRegistry.InstanceSnapshot reconnect = DynamicInstanceRegistry.application(
                stream(88, "org.example.player", "music-player", "Music"));

        var inserted = DynamicInstanceRegistry.ensureApplicationRule(config, first).orElseThrow();
        var existing = DynamicInstanceRegistry.ensureApplicationRule(config, reconnect).orElseThrow();
        assertTrue(inserted.inserted());
        assertFalse(existing.inserted());
        assertEquals(inserted.ruleId(), existing.ruleId());
        List<Map<String, Object>> rules = maps(config.get("applications"));
        assertEquals(1, rules.size());
        Map<String, Object> rule = rules.getFirst();
        assertEquals(Map.of("app_id", "org.example.player"), rule.get("match"));
        assertEquals(1.0, rule.get("volume"));
        assertEquals(false, rule.get("mute"));
        assertTrue(rule.get("id") instanceof String id && !id.isBlank());
    }

    @Test void existingCanonicalRuleAndLowConfidenceOrLogicalSnapshotsArePreservedOrIgnored() {
        Map<String, Object> saved = new LinkedHashMap<>(Map.of("id", "saved-player", "match",
                Map.of("app_id", "org.example.player"), "volume", 0.23, "mute", true,
                "filters", List.of(Map.of("processor", "high_pass", "frequency_hz", 70.0))));
        Map<String, Object> config = config(new ArrayList<>(List.of(saved)));
        var existing = DynamicInstanceRegistry.ensureApplicationRule(config,
                DynamicInstanceRegistry.application(stream(2, "org.example.player", "music-player", "Music")))
                .orElseThrow();
        assertFalse(existing.inserted());
        assertEquals("saved-player", existing.ruleId());
        assertEquals(List.of(saved), config.get("applications"));

        Map<String, Object> empty = config(new ArrayList<>());
        assertTrue(DynamicInstanceRegistry.ensureApplicationRule(empty,
                DynamicInstanceRegistry.application(stream(3, "", "", "Unknown"))).isEmpty());
        assertTrue(DynamicInstanceRegistry.ensureApplicationRule(empty,
                DynamicInstanceRegistry.browserParentApplication("chrome", "", "", "Chrome")).isEmpty());
        assertTrue(DynamicInstanceRegistry.ensureApplicationRule(empty,
                DynamicInstanceRegistry.application(stream(4, EventSoundStudioSource.APPLICATION_ID,
                        "eventsoundstudio", "ESS"))).isEmpty());
        assertTrue(maps(empty.get("applications")).isEmpty());
    }

    private static Map<String, Object> config(List<Map<String, Object>> applications) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("applications", applications);
        return config;
    }

    private static AudioNode stream(int id, String appId, String process, String name) {
        return new AudioNode(id, "stream-" + id, name, "Stream/Output/Audio", "running", name, process,
                appId, 2, List.of(48_000), 1.0, false, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
