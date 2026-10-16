package io.github.ilyaandreev05.audiomatrix.audio;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DspComparisonConfigTest {
    @Test
    void referenceModeDisablesEqKeepsLimiterAndDoesNotMutateSavedYaml() {
        Map<String, Object> source = DefaultConfig.create();
        @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) source.get("dsp");
        dsp.put("global_eq", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "peaking", "frequency_hz", 100.0, "gain_db", 4.0, "enabled", true))));
        @SuppressWarnings("unchecked") Map<String, Object> devices = (Map<String, Object>) dsp.get("devices");
        devices.put("dac", DefaultConfig.map("enabled", true, "filters", List.of()));
        @SuppressWarnings("unchecked") Map<String, Object> chains = (Map<String, Object>) dsp.get("route_chains");
        chains.put("music", DefaultConfig.map("enabled", true, "filters", List.of()));
        @SuppressWarnings("unchecked") Map<String, Object> mixer = (Map<String, Object>) source.get("mixer");
        mixer.put("global_eq", DefaultConfig.map("enabled", true, "filters", List.of()));

        Map<String, Object> reference = DspComparisonConfig.reference(source, 6.0);
        @SuppressWarnings("unchecked") Map<String, Object> refDsp = (Map<String, Object>) reference.get("dsp");
        @SuppressWarnings("unchecked") Map<String, Object> limiter = (Map<String, Object>) refDsp.get("limiter");
        @SuppressWarnings("unchecked") Map<String, Object> refMixer = (Map<String, Object>) reference.get("mixer");
        @SuppressWarnings("unchecked") Map<String, Object> refBus = ((List<Map<String, Object>>) reference.get("buses")).stream()
                .filter(bus -> "music".equals(bus.get("id"))).findFirst().orElseThrow();

        assertFalse(((Map<String, Object>) refDsp.get("global_eq")).get("enabled").equals(Boolean.TRUE));
        assertFalse(((Map<String, Object>) ((Map<String, Object>) refDsp.get("devices")).get("dac")).get("enabled").equals(Boolean.TRUE));
        assertFalse(((Map<String, Object>) ((Map<String, Object>) refDsp.get("route_chains")).get("music")).get("enabled").equals(Boolean.TRUE));
        assertFalse(((Map<String, Object>) refMixer.get("global_eq")).get("enabled").equals(Boolean.TRUE));
        assertTrue((Boolean) limiter.get("enabled"));
        assertEquals(Math.pow(10, -6.0 / 20.0), (Double) refBus.get("volume"), 1e-9);
        assertTrue((Boolean) ((Map<String, Object>) dsp.get("global_eq")).get("enabled"));
        assertEquals(1.0, ((Number) ((List<Map<String, Object>>) source.get("buses")).stream()
                .filter(bus -> "music".equals(bus.get("id"))).findFirst().orElseThrow().get("volume")).doubleValue());
    }
}
