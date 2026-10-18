package io.github.ilyaandreev05.audiomatrix.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class GraphEqResponseTest {
    @Test
    void flatPeakingBandUsesUnityBiquadResponse() {
        Map<String, Object> band = Map.of("type", "peaking", "frequency_hz", 1000.0,
                "gain_db", 0.0, "q", 1.0);

        assertEquals(0.0, GraphEqResponse.responseDb(List.of(band), 1000), 1e-8);
        assertEquals(0.0, GraphEqResponse.responseDb(List.of(), 200), 1e-8);
    }

    @Test
    void peakingAndNotchCurvesUseTheirBiquadCoefficients() {
        Map<String, Object> peak = Map.of("type", "peaking", "frequency_hz", 1000.0,
                "gain_db", 6.0, "q", 1.0);
        Map<String, Object> notch = Map.of("type", "notch", "frequency_hz", 1000.0,
                "gain_db", 0.0, "q", 4.0);

        assertEquals(6.0, GraphEqResponse.responseDb(List.of(peak), 1000), 1e-8);
        assertTrue(GraphEqResponse.responseDb(List.of(notch), 1000) < -80.0);
    }

    @Test
    void stackedBandsSumInDecibels() {
        Map<String, Object> first = Map.of("type", "peaking", "frequency_hz", 1000.0,
                "gain_db", 3.0, "q", 1.0);
        Map<String, Object> second = Map.of("type", "peaking", "frequency_hz", 1000.0,
                "gain_db", -2.0, "q", 1.0);

        assertEquals(1.0, GraphEqResponse.responseDb(List.of(first, second), 1000), 1e-8);
    }

    @Test
    void shelvesUsePipeWireQualityRatherThanClampedSlope() {
        for (String type : List.of("low_shelf", "high_shelf")) {
            Map<String, Object> broad = Map.of("type", type, "frequency_hz", 1000.0, "gain_db", 12.0, "q", .5);
            Map<String, Object> resonant = Map.of("type", type, "frequency_hz", 1000.0, "gain_db", 12.0, "q", .8);
            assertEquals(6, GraphEqResponse.responseDb(List.of(broad), 1000), 1e-8);
            assertNotEquals(GraphEqResponse.responseDb(List.of(broad), 500),
                    GraphEqResponse.responseDb(List.of(resonant), 500), .01);
        }
    }

    @Test
    void disabledBandsAreExcludedAndLegacyBandNamesRemainReadable() {
        Map<String, Object> disabled = Map.of("type", "peaking", "frequency_hz", 1000,
                "gain_db", 18, "q", 1, "enabled", false);
        Map<String, Object> legacy = Map.of("filterKind", "bell", "frequencyHz", 1000, "gainDb", 4, "q", 1);
        assertEquals(4, GraphEqResponse.responseDb(List.of(disabled, legacy), 1000), 1e-8);
    }
}
