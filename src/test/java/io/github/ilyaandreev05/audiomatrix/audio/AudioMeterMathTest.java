package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioMeterMathTest {
    @Test
    void reportsPeakRmsAndDigitalClippingFromInterleavedFrames() {
        AudioMeterMath.Measurement result = AudioMeterMath.measure(new float[]{1.0f, 0.5f, -0.5f, 0.0f}, 2);

        assertEquals(0.0, result.peakDbfs(), 1e-9);
        assertEquals(20.0 * Math.log10(Math.sqrt(0.375)), result.rmsDbfs(), 1e-6);
        assertTrue(result.clipped());
        assertEquals(4, result.sampleCount());
    }

    @Test
    void silenceUsesAStableFloorAndNonClippingState() {
        AudioMeterMath.Measurement result = AudioMeterMath.measure(new float[8], 2);

        assertEquals(-120.0, result.peakDbfs());
        assertEquals(-120.0, result.rmsDbfs());
        assertFalse(result.clipped());
    }

    @Test
    void ignoresNonFiniteSamplesAndRejectsPartialFrames() {
        AudioMeterMath.Measurement result = AudioMeterMath.measure(new float[]{Float.NaN, Float.POSITIVE_INFINITY, 0.25f}, 1);
        assertEquals(20.0 * Math.log10(0.25), result.peakDbfs(), 1e-6);
        assertEquals(20.0 * Math.log10(0.25), result.rmsDbfs(), 1e-6);
        assertEquals(1, result.sampleCount());
        assertThrows(IllegalArgumentException.class, () -> AudioMeterMath.measure(new float[]{0.1f, 0.2f, 0.3f}, 2));
    }
}
