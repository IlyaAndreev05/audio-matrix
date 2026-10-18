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

    private static Map<String, Object> object(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
