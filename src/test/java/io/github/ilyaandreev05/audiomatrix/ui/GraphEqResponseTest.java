package io.github.ilyaandreev05.audiomatrix.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
