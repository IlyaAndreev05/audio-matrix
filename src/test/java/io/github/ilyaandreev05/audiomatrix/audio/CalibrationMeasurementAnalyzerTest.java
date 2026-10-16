package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalibrationMeasurementAnalyzerTest {
    @TempDir Path temp;

    @Test
    void analyzesPcmWavAtItsSteppedToneFrequenciesWithoutClaimingPhysicalUnits() throws Exception {
        Path wav = temp.resolve("sensor.wav");
        Files.write(wav, CalibrationSignal.steppedSineWave(30, 300, 8, 200));

        CalibrationMeasurementAnalyzer.Recording recording = CalibrationMeasurementAnalyzer.readPcm16Wav(wav, 1);
        CalibrationMeasurementAnalyzer.Result result = CalibrationMeasurementAnalyzer.analyze(recording, 30, 300, 8, 200, 0);

        assertEquals(48_000, result.sampleRate());
        assertEquals(1, result.channelIndex());
        assertEquals(8, result.points().size());
        assertTrue(result.points().stream().allMatch(point -> Math.abs(point.relativeDb()) < 0.02));
        assertTrue(result.points().stream().allMatch(point -> point.amplitudePcm() > 0.024 && point.amplitudePcm() < 0.026));
        assertFalse(result.clipped());
        assertTrue(CalibrationMeasurementAnalyzer.toCsv(result).startsWith("frequency_hz,amplitude_pcm,relative_db,clipped\n"));
    }

    @Test
    void relativeCurveFindsFrequencyDependentAttenuationWithPreRollAndDcOffset() {
        int sampleRate = 48_000;
        int steps = 3;
        int dwellMs = 200;
        int offsetMs = 50;
        double[] gains = {0.5, 1.0, 0.25};
        double[] samples = new double[sampleRate * offsetMs / 1000 + sampleRate * dwellMs * steps / 1000];
        int firstFrame = sampleRate * offsetMs / 1000;
        for (int step = 0; step < steps; step++) {
            double frequency = 40 * Math.pow(400.0 / 40, (double) step / (steps - 1));
            int frames = sampleRate * dwellMs / 1000;
            for (int frame = 0; frame < frames; frame++) {
                samples[firstFrame + step * frames + frame] = 0.02
                        + 0.025 * gains[step] * Math.sin(2 * Math.PI * frequency * frame / sampleRate + 0.7);
            }
        }
        CalibrationMeasurementAnalyzer.Recording recording = new CalibrationMeasurementAnalyzer.Recording(sampleRate, 0, samples);

        List<CalibrationMeasurementAnalyzer.Point> points = CalibrationMeasurementAnalyzer.analyze(recording, 40, 400,
                steps, dwellMs, offsetMs).points();

        assertEquals(0.0, points.get(0).relativeDb(), 0.02);
        assertEquals(20 * Math.log10(2.0), points.get(1).relativeDb(), 0.03);
        assertEquals(20 * Math.log10(0.5), points.get(2).relativeDb(), 0.03);
    }

    @Test
    void estimatesSweepStartFromFrequencySequenceDespitePreRollAndLowNoise() {
        int sampleRate = 48_000;
        int steps = 8;
        int dwellMs = 200;
        int trueOffsetMs = 730;
        double[] samples = synthesizeSweep(sampleRate, 30, 300, steps, dwellMs, trueOffsetMs, 200, 0.025, 0.0002);
        CalibrationMeasurementAnalyzer.Recording recording = new CalibrationMeasurementAnalyzer.Recording(sampleRate, 0, samples);

        CalibrationMeasurementAnalyzer.OffsetEstimate estimate = CalibrationMeasurementAnalyzer.estimateSweepOffset(
                recording, 30, 300, steps, dwellMs, 3_000);
        CalibrationMeasurementAnalyzer.Result result = CalibrationMeasurementAnalyzer.analyze(recording, 30, 300,
                steps, dwellMs, estimate.offsetMs());

        assertEquals(trueOffsetMs, estimate.offsetMs(), 25);
        assertTrue(estimate.sequenceScore() > 0.9);
        assertTrue(estimate.uncertaintyMs() <= 40);
        assertTrue(result.points().stream().allMatch(point -> Math.abs(point.relativeDb()) < 0.1));
    }

    @Test
    void automaticAlignmentRejectsMissingAndRepeatedSweepSequences() {
        int sampleRate = 48_000;
        double[] silence = new double[sampleRate * 2];
        CalibrationMeasurementAnalyzer.Recording noSweep = new CalibrationMeasurementAnalyzer.Recording(sampleRate, 0, silence);
        assertThrows(IllegalArgumentException.class, () -> CalibrationMeasurementAnalyzer.estimateSweepOffset(
                noSweep, 30, 300, 8, 200, 1_000));

        int sweepMs = 8 * 200;
        int gapMs = 400;
        double[] first = synthesizeSweep(sampleRate, 30, 300, 8, 200, 0, 0, 0.025, 0);
        double[] second = synthesizeSweep(sampleRate, 30, 300, 8, 200, 0, 0, 0.025, 0);
        double[] repeated = new double[sampleRate * (sweepMs * 2 + gapMs) / 1000];
        System.arraycopy(first, 0, repeated, 0, first.length);
        System.arraycopy(second, 0, repeated, first.length + sampleRate * gapMs / 1000, second.length);
        CalibrationMeasurementAnalyzer.Recording twoSweeps = new CalibrationMeasurementAnalyzer.Recording(sampleRate, 0, repeated);
        assertThrows(IllegalArgumentException.class, () -> CalibrationMeasurementAnalyzer.estimateSweepOffset(
                twoSweeps, 30, 300, 8, 200, 3_000));
    }

    private static double[] synthesizeSweep(int sampleRate, double startHz, double endHz, int steps, int dwellMs,
                                            int offsetMs, int tailMs, double amplitude, double noiseAmplitude) {
        int dwellFrames = sampleRate * dwellMs / 1000;
        int offsetFrames = sampleRate * offsetMs / 1000;
        int tailFrames = sampleRate * tailMs / 1000;
        double[] samples = new double[offsetFrames + dwellFrames * steps + tailFrames];
        for (int frame = 0; frame < samples.length; frame++)
            samples[frame] = noiseAmplitude * Math.sin(2 * Math.PI * 137.0 * frame / sampleRate);
        for (int step = 0; step < steps; step++) {
            double fraction = (double) step / (steps - 1);
            double frequency = startHz * Math.pow(endHz / startHz, fraction);
            int first = offsetFrames + step * dwellFrames;
            for (int frame = 0; frame < dwellFrames; frame++)
                samples[first + frame] += amplitude * Math.sin(2 * Math.PI * frequency * frame / sampleRate + 0.37);
        }
        return samples;
    }

    @Test
    void rejectsUnsupportedWavChannelShortRecordingsAndUnderSampledLowToneWindows() throws Exception {
        Path wav = temp.resolve("sensor.wav");
        Files.write(wav, CalibrationSignal.steppedSineWave(30, 300, 8, 200));
        assertThrows(java.io.IOException.class, () -> CalibrationMeasurementAnalyzer.readPcm16Wav(wav, 2));

        CalibrationMeasurementAnalyzer.Recording shortRecording = new CalibrationMeasurementAnalyzer.Recording(48_000, 0, new double[100]);
        assertThrows(IllegalArgumentException.class, () -> CalibrationMeasurementAnalyzer.analyze(shortRecording, 30, 300, 8, 200, 0));
        assertThrows(IllegalArgumentException.class, () -> CalibrationMeasurementAnalyzer.analyze(shortRecording, 20, 100, 8, 50, 0));
    }

    @Test
    void comparesNormalizedCurvesOnTheSharedLogFrequencyGrid() {
        CalibrationMeasurementAnalyzer.Result before = new CalibrationMeasurementAnalyzer.Result(48_000, 0, 0.2, false, 0.1, List.of(
                new CalibrationMeasurementAnalyzer.Point(40, 0.1, 0, false),
                new CalibrationMeasurementAnalyzer.Point(400, 0.1, 0, false)));
        CalibrationMeasurementAnalyzer.Result after = new CalibrationMeasurementAnalyzer.Result(48_000, 0, 0.2, false, 0.1, List.of(
                new CalibrationMeasurementAnalyzer.Point(40, 0.1, -2, false),
                new CalibrationMeasurementAnalyzer.Point(200, 0.1, 1, false),
                new CalibrationMeasurementAnalyzer.Point(400, 0.1, 4, false)));

        CalibrationMeasurementAnalyzer.Comparison comparison = CalibrationMeasurementAnalyzer.compare(before, after);

        assertEquals(List.of(40.0, 200.0, 400.0), comparison.points().stream()
                .map(CalibrationMeasurementAnalyzer.ComparisonPoint::frequencyHz).toList());
        assertEquals(-2.0, comparison.points().getFirst().deltaDb(), 1e-9);
        assertEquals(1.0, comparison.points().get(1).afterRelativeDb(), 1e-9);
        assertEquals(1.0, comparison.points().get(1).deltaDb(), 1e-9);
        assertEquals(4.0, comparison.points().getLast().deltaDb(), 1e-9);
    }

    @Test
    void refusesCurveComparisonWithoutAnOverlappingRange() {
        CalibrationMeasurementAnalyzer.Result before = new CalibrationMeasurementAnalyzer.Result(48_000, 0, 0.2, false, 0.1, List.of(
                new CalibrationMeasurementAnalyzer.Point(20, 0.1, 0, false),
                new CalibrationMeasurementAnalyzer.Point(30, 0.1, 0, false)));
        CalibrationMeasurementAnalyzer.Result after = new CalibrationMeasurementAnalyzer.Result(48_000, 0, 0.2, false, 0.1, List.of(
                new CalibrationMeasurementAnalyzer.Point(40, 0.1, 0, false),
                new CalibrationMeasurementAnalyzer.Point(50, 0.1, 0, false)));
        assertThrows(IllegalArgumentException.class, () -> CalibrationMeasurementAnalyzer.compare(before, after));
    }
}
