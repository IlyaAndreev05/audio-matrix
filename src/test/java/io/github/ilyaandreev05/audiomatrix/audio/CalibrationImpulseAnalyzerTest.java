package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalibrationImpulseAnalyzerTest {
    @Test
    void findsRingDownFrequencyDecayAndQFromSyntheticSensorRecording() {
        int sampleRate = 48_000;
        int offsetMs = 100;
        int windowMs = 1_500;
        double frequencyHz = 164.0;
        double expectedQ = 25.0;
        int offsetFrame = sampleRate * offsetMs / 1000;
        double[] samples = new double[sampleRate * (offsetMs + windowMs + 100) / 1000];
        for (int i = 0; i < offsetFrame; i++)
            samples[i] = 0.01 + 0.00002 * Math.sin(2 * Math.PI * 37.0 * i / sampleRate);
        for (int i = offsetFrame; i < samples.length; i++) {
            double seconds = (double) (i - offsetFrame) / sampleRate;
            double amplitude = 0.05 * Math.exp(-Math.PI * frequencyHz * seconds / expectedQ);
            samples[i] = 0.01 + amplitude * Math.sin(2 * Math.PI * frequencyHz * seconds)
                    + 0.00002 * Math.sin(2 * Math.PI * 37.0 * seconds);
        }
        CalibrationMeasurementAnalyzer.Recording recording = new CalibrationMeasurementAnalyzer.Recording(sampleRate, 0, samples);

        CalibrationImpulseAnalyzer.Result result = CalibrationImpulseAnalyzer.analyze(recording, offsetMs, windowMs);

        assertEquals(sampleRate, result.sampleRate());
        assertEquals(0, result.channelIndex());
        assertEquals(offsetMs, result.impulseOffsetMs());
        assertTrue(result.detectedPeakOffsetMs() >= offsetMs);
        assertEquals(frequencyHz, result.dominantFrequencyHz(), 4.0);
        assertEquals(expectedQ, result.qualityFactor(), 7.0);
        assertNotNull(result.decayT20Ms());
        assertNotNull(result.projectedT60Ms());
        assertTrue(result.decayT20Ms() > 80 && result.decayT20Ms() < 150);
        assertTrue(result.projectedT60Ms() > result.decayT20Ms() * 2.9);
        assertFalse(result.clipped());
        assertTrue(result.points().size() >= 100);
        assertTrue(result.points().getLast().integratedDecayDb() > -120);
        for (int i = 1; i < result.points().size(); i++)
            assertTrue(result.points().get(i).integratedDecayDb() <= result.points().get(i - 1).integratedDecayDb() + 1e-9);
        assertTrue(CalibrationImpulseAnalyzer.toCsv(result).startsWith("time_ms,envelope_relative_db,integrated_decay_db\n"));
    }

    @Test
    void rejectsNoResponseInsufficientPreRollAndShortWindows() {
        double[] samples = new double[48_000];
        CalibrationMeasurementAnalyzer.Recording silent = new CalibrationMeasurementAnalyzer.Recording(48_000, 0, samples);
        assertThrows(IllegalArgumentException.class, () -> CalibrationImpulseAnalyzer.analyze(silent, 100, 500));
        assertThrows(IllegalArgumentException.class, () -> CalibrationImpulseAnalyzer.analyze(silent, 10, 500));
        assertThrows(IllegalArgumentException.class, () -> CalibrationImpulseAnalyzer.analyze(silent, 100, 1_500));
    }

    @Test
    void reportsClippingInImpulseWindow() {
        int sampleRate = 48_000;
        int offsetMs = 100;
        int offsetFrame = sampleRate * offsetMs / 1000;
        double[] samples = new double[sampleRate];
        for (int i = offsetFrame; i < samples.length; i++) {
            double seconds = (double) (i - offsetFrame) / sampleRate;
            samples[i] = 1.2 * Math.exp(-15 * seconds) * Math.sin(2 * Math.PI * 100 * seconds);
        }
        CalibrationMeasurementAnalyzer.Recording recording = new CalibrationMeasurementAnalyzer.Recording(sampleRate, 0, samples);
        assertTrue(CalibrationImpulseAnalyzer.analyze(recording, offsetMs, 500).clipped());
    }
}
