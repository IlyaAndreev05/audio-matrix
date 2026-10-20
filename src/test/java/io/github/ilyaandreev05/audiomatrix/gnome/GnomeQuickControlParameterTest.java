package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GnomeQuickControlParameterTest {
    @Test void describesLowPassCutoffAndSlopeWithTheirRealRanges() {
        FilterNode filter = new FilterNode("low", "Bass low-pass", List.of("FL", "FR"), "low_pass", true,
                Map.of("frequency_hz", 100.0, "slope_db_oct", 24));

        Map<String, Object> cutoff = GnomeQuickControlParameter.describe(filter, "frequency_hz", "en");
        Map<String, Object> slope = GnomeQuickControlParameter.describe(filter, "slope_db_oct", "en");

        assertEquals("range", cutoff.get("kind"));
        assertEquals(20.0, cutoff.get("minimum"));
        assertEquals(20000.0, cutoff.get("maximum"));
        assertEquals("log", cutoff.get("scale"));
        assertEquals("choice", slope.get("kind"));
        assertEquals(List.of("enabled", "frequency_hz", "slope_db_oct"),
                GnomeQuickControlParameter.definitions(filter).stream().map(GnomeQuickControlParameter.Definition::id).toList());
    }

    @Test void sharedBassFilterPublishesItsNarrowFrequencyAndSteepSlopeRanges() {
        FilterNode filter = new FilterNode("octine-bass-low-pass", "Басы · 60 Гц", List.of("FL", "FR"),
                "low_pass", true, Map.of("frequency_hz", 60.0, "slope_db_oct", 48.0));

        Map<String, Object> cutoff = GnomeQuickControlParameter.describe(filter, "frequency_hz", "ru");
        Map<String, Object> slope = GnomeQuickControlParameter.describe(filter, "slope_db_oct", "ru");

        assertEquals(40, cutoff.get("minimum"));
        assertEquals(100, cutoff.get("maximum"));
        assertEquals("linear", cutoff.get("scale"));
        List<?> slopes = (List<?>) slope.get("options");
        assertEquals(List.of("24", "36", "48"), slopes.stream()
                .map(option -> String.valueOf(((Map<?, ?>) option).get("id"))).toList());
    }

    @Test void sharedBassFilterRejectsCutoffsAndSlopesOutsideItsPublishedRange() {
        Map<String, Object> node = new LinkedHashMap<>(Map.of("id", "octine-bass-low-pass", "type", "filter",
                "parameters", Map.of("processor", "low_pass", "enabled", true,
                        "frequency_hz", 60.0, "slope_db_oct", 48.0)));

        GnomeQuickControlParameter.apply(node, "frequency_hz", "100", false);
        assertEquals(100.0, object(node.get("parameters")).get("frequency_hz"));
        assertThrows(IllegalArgumentException.class,
                () -> GnomeQuickControlParameter.apply(node, "frequency_hz", "101", false));
        assertThrows(IllegalArgumentException.class,
                () -> GnomeQuickControlParameter.choose(node, "slope_db_oct", "12"));
    }

    @Test void editsEqBandFieldsAndRejectsValuesOutsideDspBounds() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "filter");
        node.put("parameters", Map.of("processor", "eq", "enabled", true,
                "bands", List.of(Map.of("type", "peaking", "frequency_hz", 110.0,
                        "gain_db", 0.0, "q", 1.0))));

        GnomeQuickControlParameter.apply(node, "bands.0.gain_db", "-4.5", false);
        GnomeQuickControlParameter.choose(node, "bands.0.type", "low_shelf");

        Map<String, Object> parameters = object(node.get("parameters"));
        Map<String, Object> band = object(((List<?>) parameters.get("bands")).getFirst());
        assertEquals(-4.5, band.get("gain_db"));
        assertEquals("low_shelf", band.get("type"));
        assertThrows(IllegalArgumentException.class,
                () -> GnomeQuickControlParameter.apply(node, "bands.0.gain_db", "24.1", false));
        assertThrows(IllegalArgumentException.class,
                () -> GnomeQuickControlParameter.choose(node, "bands.0.type", "unknown"));
    }

    @Test void publishesOnlyGateOnOffAndPreservesGainWhenMuting() {
        Map<String, Object> gate = new LinkedHashMap<>(Map.of("type", "gate",
                "parameters", Map.of("open", true)));
        GnomeQuickControlParameter.apply(gate, "open", "", true);
        assertEquals(false, object(gate.get("parameters")).get("open"));
        GnomeQuickControlParameter.apply(gate, "open", "", true);
        assertEquals(true, object(gate.get("parameters")).get("open"));

        Map<String, Object> gain = new LinkedHashMap<>(Map.of("type", "gain",
                "parameters", Map.of("gain", .42, "muted", false)));
        GnomeQuickControlParameter.apply(gain, "gain", "", true);
        assertEquals(Map.of("gain", .42, "muted", true), gain.get("parameters"));
        GnomeQuickControlParameter.apply(gain, "gain", "", true);
        assertEquals(Map.of("gain", .42, "muted", false), gain.get("parameters"));
    }

    @Test void excludesPhaseInvertAndLegacyMultiSwitchFromPublishedControls() {
        assertEquals(null, GnomeQuickControlParameter.describe("filter",
                Map.of("processor", "phase_invert", "enabled", true), "enabled", "en"));
        assertEquals(null, GnomeQuickControlParameter.describe("multi_switch",
                Map.of("active_links", List.of("in->out")), "active_links", "en"));
        assertEquals("links", GnomeQuickControlParameter.describe("switch",
                Map.of("active_links", List.of("in->a", "in->b")), "active_links", "en").get("kind"));
    }

    private static Map<String, Object> object(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
