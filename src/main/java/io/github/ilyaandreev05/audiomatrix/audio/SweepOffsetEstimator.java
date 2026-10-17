package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.ArrayList;
import java.util.List;

/** Locates one clear logarithmic stepped-sine sequence in a recorded sweep. */
final class SweepOffsetEstimator {
    private static final int COARSE_STEP_MS = 10;

    private SweepOffsetEstimator() {}

    static CalibrationMeasurementAnalyzer.OffsetEstimate estimate(
            CalibrationMeasurementAnalyzer.Recording recording, double startHz, double endHz,
            int steps, int dwellMs, int maxSearchMs) {
        validate(startHz, endHz, steps, dwellMs, maxSearchMs, recording);
        int sampleRate = recording.sampleRate();
        double[] samples = recording.samples();
        int dwellFrames = Math.toIntExact((long) sampleRate * dwellMs / 1000);
        int trimFrames = Math.min(sampleRate / 50, dwellFrames / 10);
        int analysisFrames = dwellFrames - 2 * trimFrames;
        requireEnoughCycles(startHz, analysisFrames, sampleRate);
        int stride = Math.max(1, (sampleRate + 23_999) / 24_000);
        int scoreFrames = analysisFrames / stride;
        long scheduleFrames = (long) dwellFrames * steps;
        long lastFrame = samples.length - scheduleFrames;
        if (lastFrame < 0) throw new IllegalArgumentException("WAV is shorter than the selected stepped-sine sweep");
        int lastOffsetMs = (int) Math.min(maxSearchMs, lastFrame * 1000 / sampleRate);
        List<OffsetScore> scores = scoreOffsets(samples, sampleRate, startHz, endHz, steps,
                dwellFrames, trimFrames, scoreFrames, stride, lastOffsetMs);
        int bestIndex = bestIndex(scores);
        double bestScore = scores.get(bestIndex).score();
        if (bestScore < 0.45) throw weakSequence(maxSearchMs);
        OffsetRange strongest = strongestCandidate(scores, bestScore, dwellMs);
        int estimate = (int) Math.round(strongest.centerMs());
        int uncertainty = Math.max(COARSE_STEP_MS / 2,
                (int) Math.ceil((strongest.lastMs() - strongest.firstMs()) / 2.0 + COARSE_STEP_MS / 2.0));
        return new CalibrationMeasurementAnalyzer.OffsetEstimate(estimate, bestScore, uncertainty);
    }

    private static void validate(double startHz, double endHz, int steps, int dwellMs, int maxSearchMs,
                                 CalibrationMeasurementAnalyzer.Recording recording) {
        if (recording == null) throw new IllegalArgumentException("A WAV recording is required");
        if (!Double.isFinite(startHz) || !Double.isFinite(endHz) || startHz < 20 || endHz > 1000 || endHz <= startHz)
            throw new IllegalArgumentException("Sweep frequencies must be increasing and within 20–1000 Hz");
        if (steps < 2 || steps > 16) throw new IllegalArgumentException("Sweep step count must be between 2 and 16");
        if (dwellMs < 50 || dwellMs > 300) throw new IllegalArgumentException("Sweep dwell must be between 50 and 300 ms");
        if (maxSearchMs < 0 || maxSearchMs > 60_000)
            throw new IllegalArgumentException("Offset search must be between 0 and 60000 ms");
    }

    private static void requireEnoughCycles(double frequency, int frames, int sampleRate) {
        if (frequency * frames / sampleRate < 2.5)
            throw new IllegalArgumentException("Increase sweep dwell time to capture at least 2.5 cycles at the lowest frequency");
    }

    private static List<OffsetScore> scoreOffsets(double[] samples, int sampleRate, double startHz,
                                                   double endHz, int steps, int dwellFrames, int trimFrames,
                                                   int scoreFrames, int stride, int lastOffsetMs) {
        List<OffsetScore> scores = new ArrayList<>();
        for (int offsetMs = 0; offsetMs <= lastOffsetMs; offsetMs += COARSE_STEP_MS)
            scores.add(scoreAtOffset(samples, sampleRate, startHz, endHz, steps, dwellFrames,
                    trimFrames, scoreFrames, stride, offsetMs));
        if (scores.isEmpty() || scores.getLast().offsetMs() != lastOffsetMs)
            scores.add(scoreAtOffset(samples, sampleRate, startHz, endHz, steps, dwellFrames,
                    trimFrames, scoreFrames, stride, lastOffsetMs));
        return scores;
    }

    private static OffsetScore scoreAtOffset(double[] samples, int sampleRate, double startHz,
                                              double endHz, int steps, int dwellFrames, int trimFrames,
                                              int scoreFrames, int stride, int offsetMs) {
        long offsetFrames = (long) sampleRate * offsetMs / 1000;
        return new OffsetScore(offsetMs, scoreSweepAt(samples, sampleRate, startHz, endHz, steps,
                dwellFrames, trimFrames, scoreFrames, stride, offsetFrames));
    }

    private static double scoreSweepAt(double[] samples, int sampleRate, double startHz, double endHz,
                                       int steps, int dwellFrames, int trimFrames, int scoreFrames,
                                       int stride, long offsetFrames) {
        double score = 0;
        for (int step = 0; step < steps; step++) {
            double fraction = (double) step / (steps - 1);
            double frequency = startHz * Math.pow(endHz / startHz, fraction);
            int first = Math.toIntExact(offsetFrames + (long) step * dwellFrames + trimFrames);
            score += CalibrationSignalFit.fit(samples, first, scoreFrames, sampleRate, frequency, stride)
                    .toneFitScore();
        }
        return score / steps;
    }

    private static int bestIndex(List<OffsetScore> scores) {
        int best = 0;
        for (int index = 1; index < scores.size(); index++)
            if (scores.get(index).score() > scores.get(best).score()) best = index;
        return best;
    }

    private static OffsetRange strongestCandidate(List<OffsetScore> scores, double bestScore, int dwellMs) {
        double threshold = bestScore - Math.max(0.02, bestScore * 0.05);
        List<OffsetRange> candidates = candidates(scores, threshold);
        OffsetRange strongest = candidates.stream().max(java.util.Comparator.comparingDouble(OffsetRange::score))
                .orElseThrow(() -> new IllegalArgumentException("No stepped-sine alignment candidate was found"));
        boolean ambiguous = candidates.stream().anyMatch(range -> range != strongest
                && range.score() >= bestScore - 0.02
                && Math.abs(range.centerMs() - strongest.centerMs()) > dwellMs);
        if (ambiguous) throw new IllegalArgumentException(
                "Several stepped-sine sequences match this recording; set the recording offset manually");
        return strongest;
    }

    private static List<OffsetRange> candidates(List<OffsetScore> scores, double threshold) {
        List<OffsetRange> result = new ArrayList<>();
        int index = 0;
        while (index < scores.size()) {
            if (scores.get(index).score() < threshold) { index++; continue; }
            int first = index;
            double peak = scores.get(index).score();
            while (index + 1 < scores.size() && scores.get(index + 1).score() >= threshold) {
                index++;
                peak = Math.max(peak, scores.get(index).score());
            }
            result.add(new OffsetRange(scores.get(first).offsetMs(), scores.get(index).offsetMs(), peak));
            index++;
        }
        return result;
    }

    private static IllegalArgumentException weakSequence(int maxSearchMs) {
        return new IllegalArgumentException("No clear stepped-sine sequence was found in the first " + maxSearchMs
                + " ms; set the recording offset manually");
    }

    private record OffsetScore(int offsetMs, double score) {}
    private record OffsetRange(int firstMs, int lastMs, double score) {
        double centerMs() { return (firstMs + lastMs) / 2.0; }
    }
}
