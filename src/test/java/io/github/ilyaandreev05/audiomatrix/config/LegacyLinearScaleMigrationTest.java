package io.github.ilyaandreev05.audiomatrix.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyLinearScaleMigrationTest {
    @Test void cubicWpctlValuesBecomeEquivalentLinearGainsAndBoostIsClamped() {
        Map<String, Object> config = DefaultConfig.map(
                "devices", List.of(DefaultConfig.map("volume", 0.5), DefaultConfig.map("volume", 1.5)),
                "mixer", DefaultConfig.map("global_volume", 0.8));

        LegacyLinearScaleMigration.cube(config);

        @SuppressWarnings("unchecked") List<Map<String, Object>> devices =
                (List<Map<String, Object>>) config.get("devices");
        assertEquals(0.125, devices.getFirst().get("volume"));
        assertEquals(1.0, devices.get(1).get("volume"));
        assertEquals(0.512, ((Number) ((Map<?, ?>) config.get("mixer")).get("global_volume")).doubleValue(), 1e-12);
    }
}
