package io.github.ilyaandreev05.audiomatrix.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/** Reads a sensor WAV and estimates relative response to a known stepped-sine schedule. */
public final class CalibrationMeasurementAnalyzer {
    private static final long MAX_WAV_BYTES = 64L * 1024 * 1024;
    private static final int MAX_FRAMES = 12_000_000;
    private static final double CLIP_THRESHOLD = 0.999;
    private static final double RELATIVE_FLOOR_DB = -120.0;

    private CalibrationMeasurementAnalyzer() {}

    public static Recording readPcm16Wav(Path path, int channelIndex) throws IOException {
        if (path == null || !Files.isRegularFile(path)) throw new IOException("Choose a regular WAV recording file");
        if (Files.size(path) > MAX_WAV_BYTES) throw new IOException("WAV recording exceeds 64 MiB");
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(path.toFile())) {
            AudioFormat format = stream.getFormat();
            int channels = format.getChannels();
            int sampleBits = format.getSampleSizeInBits();
            float sampleRate = format.getSampleRate();
            int bytesPerSample = sampleBits / 8;
            if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()) || sampleBits != 16
                    || channels < 1 || channels > 2 || bytesPerSample != 2
                    || format.getFrameSize() < channels * bytesPerSample
                    || !Float.isFinite(sampleRate) || sampleRate < 8_000 || sampleRate > 192_000
                    || Math.abs(sampleRate - Math.rint(sampleRate)) > 0.01)
                throw new IOException("Use mono or stereo 16-bit signed PCM WAV at 8–192 kHz");
            if (channelIndex < 0 || channelIndex >= channels)
                throw new IOException("Selected WAV channel is not available");
            byte[] bytes = stream.readNBytes((int) MAX_WAV_BYTES + 1);
            if (bytes.length > MAX_WAV_BYTES) throw new IOException("Decoded WAV data exceeds 64 MiB");
            if (bytes.length == 0 || bytes.length % format.getFrameSize() != 0)
                throw new IOException("WAV recording has no complete PCM frames");
            int frameCount = bytes.length / format.getFrameSize();
            if (frameCount > MAX_FRAMES) throw new IOException("WAV recording is longer than the supported analysis window");
            double[] samples = new double[frameCount];
            int channelOffset = channelIndex * bytesPerSample;
            for (int frame = 0; frame < frameCount; frame++) {
                int at = frame * format.getFrameSize() + channelOffset;
                int first = bytes[at] & 0xff;
                int second = bytes[at + 1] & 0xff;
                short value = (short) (format.isBigEndian() ? (first << 8) | second : (second << 8) | first);
                samples[frame] = value / 32768.0;
            }
            return new Recording((int) Math.round(sampleRate), channelIndex, samples);
        } catch (UnsupportedAudioFileException unsupported) {
            throw new IOException("File is not a supported WAV recording", unsupported);
        }
    }

    public static Result analyze(Recording recording, double startHz, double endHz, int steps,
                                 int dwellMs, int sweepOffsetMs) {
        if (recording == null) throw new IllegalArgumentException("A WAV recording is required");
        if (!Double.isFinite(startHz) || !Double.isFinite(endHz) || startHz < 20 || endHz > 1000 || endHz <= startHz)
            throw new IllegalArgumentException("Sweep frequencies must be increasing and within 20–1000 Hz");
        if (steps < 2 || steps > 16) throw new IllegalArgumentException("Sweep step count must be between 2 and 16");
        if (dwellMs < 50 || dwellMs > 300) throw new IllegalArgumentException("Sweep dwell must be between 50 and 300 ms");
        if (sweepOffsetMs < 0 || sweepOffsetMs > 60_000) throw new IllegalArgumentException("Sweep offset must be between 0 and 60000 ms");

        int sampleRate = recording.sampleRate();
        double[] samples = recording.samples();
        int dwellFrames = Math.toIntExact((long) sampleRate * dwellMs / 1000);
        int trimFrames = Math.min(sampleRate / 50, dwellFrames / 10);
        int analysisFrames = dwellFrames - 2 * trimFrames;
        if (startHz * analysisFrames / sampleRate < 2.5)
            throw new IllegalArgumentException("Increase sweep dwell time to capture at least 2.5 cycles at the lowest frequency");
        long offsetFrames = (long) sampleRate * sweepOffsetMs / 1000;
        long requiredFrames = offsetFrames + (long) dwellFrames * steps;
        if (requiredFrames > samples.length)
            throw new IllegalArgumentException("WAV is shorter than the selected sweep and recording offset");

        List<RawPoint> rawPoints = new ArrayList<>(steps);
        double peak = 0;
        boolean clipped = false;
        for (double sample : samples) {
            peak = Math.max(peak, Math.abs(sample));
            clipped |= Math.abs(sample) >= CLIP_THRESHOLD;
        }
        for (int step = 0; step < steps; step++) {
            double fraction = (double) step / (steps - 1);
            double frequency = startHz * Math.pow(endHz / startHz, fraction);
            int first = Math.toIntExact(offsetFrames + (long) step * dwellFrames + trimFrames);
            Fit fit = fitSine(samples, first, analysisFrames, sampleRate, frequency);
            boolean stepClipped = false;
            for (int i = first; i < first + analysisFrames; i++) stepClipped |= Math.abs(samples[i]) >= CLIP_THRESHOLD;
            rawPoints.add(new RawPoint(frequency, fit.amplitude(), stepClipped));
        }
        double[] amplitudes = rawPoints.stream().mapToDouble(RawPoint::amplitude).filter(value -> value > 1e-9).toArray();
        if (amplitudes.length == 0) throw new IllegalArgumentException("No measurable tone was found in the selected WAV channel");
        Arrays.sort(amplitudes);
        double reference = amplitudes[amplitudes.length / 2];
        List<Point> points = rawPoints.stream().map(point -> {
            double relativeDb = point.amplitude() <= 1e-9 ? RELATIVE_FLOOR_DB
                    : Math.max(RELATIVE_FLOOR_DB, 20.0 * Math.log10(point.amplitude() / reference));
            return new Point(point.frequencyHz(), point.amplitude(), relativeDb, point.clipped());
        }).toList();
        return new Result(sampleRate, recording.channelIndex(), peak, clipped, reference, points);
    }

    /**
     * Estimates the start of one known logarithmic stepped-sine sequence in a recording.
     * The estimate is deliberately approximate: the trimmed steady-state windows cannot
     * distinguish offsets inside their common alignment plateau. Repeated or weak sweeps
     * are rejected instead of silently choosing a start.
     */
    public static OffsetEstimate estimateSweepOffset(Recording recording, double startHz, double endHz, int steps,
                                                       int dwellMs, int maxSearchMs) {
        if (recording == null) throw new IllegalArgumentException("A WAV recording is required");
        if (!Double.isFinite(startHz) || !Double.isFinite(endHz) || startHz < 20 || endHz > 1000 || endHz <= startHz)
            throw new IllegalArgumentException("Sweep frequencies must be increasing and within 20–1000 Hz");
        if (steps < 2 || steps > 16) throw new IllegalArgumentException("Sweep step count must be between 2 and 16");
        if (dwellMs < 50 || dwellMs > 300) throw new IllegalArgumentException("Sweep dwell must be between 50 and 300 ms");
        if (maxSearchMs < 0 || maxSearchMs > 60_000)
            throw new IllegalArgumentException("Offset search must be between 0 and 60000 ms");

        int sampleRate = recording.sampleRate();
        double[] samples = recording.samples();
        int dwellFrames = Math.toIntExact((long) sampleRate * dwellMs / 1000);
        int trimFrames = Math.min(sampleRate / 50, dwellFrames / 10);
        int analysisFrames = dwellFrames - 2 * trimFrames;
        if (startHz * analysisFrames / sampleRate < 2.5)
            throw new IllegalArgumentException("Increase sweep dwell time to capture at least 2.5 cycles at the lowest frequency");
        int sampleStride = Math.max(1, (sampleRate + 23_999) / 24_000);
        int scoreFrames = analysisFrames / sampleStride;
        long scheduleFrames = (long) dwellFrames * steps;
        long lastPossibleFrame = samples.length - scheduleFrames;
        if (lastPossibleFrame < 0) throw new IllegalArgumentException("WAV is shorter than the selected stepped-sine sweep");

        int lastOffsetMs = (int) Math.min(maxSearchMs, lastPossibleFrame * 1000 / sampleRate);
        final int coarseStepMs = 10;
        List<OffsetScore> scores = new ArrayList<>();
        for (int offsetMs = 0; offsetMs <= lastOffsetMs; offsetMs += coarseStepMs) {
            long offsetFrames = (long) sampleRate * offsetMs / 1000;
            scores.add(new OffsetScore(offsetMs, scoreSweepAt(samples, sampleRate, startHz, endHz, steps,
                    dwellFrames, trimFrames, scoreFrames, sampleStride, offsetFrames)));
        }
        if (scores.isEmpty() || scores.getLast().offsetMs() != lastOffsetMs) {
            long offsetFrames = (long) sampleRate * lastOffsetMs / 1000;
            scores.add(new OffsetScore(lastOffsetMs, scoreSweepAt(samples, sampleRate, startHz, endHz, steps,
                    dwellFrames, trimFrames, scoreFrames, sampleStride, offsetFrames)));
        }

        int bestIndex = 0;
        for (int i = 1; i < scores.size(); i++)
            if (scores.get(i).score() > scores.get(bestIndex).score()) bestIndex = i;
        double bestScore = scores.get(bestIndex).score();
        if (bestScore < 0.45)
            throw new IllegalArgumentException("No clear stepped-sine sequence was found in the first " + maxSearchMs
                    + " ms; set the recording offset manually");

        double threshold = bestScore - Math.max(0.02, bestScore * 0.05);
        List<OffsetRange> candidateRanges = new ArrayList<>();
        int index = 0;
        while (index < scores.size()) {
            if (scores.get(index).score() < threshold) { index++; continue; }
            int first = index;
            double rangeBest = scores.get(index).score();
            while (index + 1 < scores.size() && scores.get(index + 1).score() >= threshold) {
                index++;
                rangeBest = Math.max(rangeBest, scores.get(index).score());
            }
            candidateRanges.add(new OffsetRange(scores.get(first).offsetMs(), scores.get(index).offsetMs(), rangeBest));
            index++;
        }
        OffsetRange strongest = candidateRanges.stream().max(java.util.Comparator.comparingDouble(OffsetRange::score))
                .orElseThrow(() -> new IllegalArgumentException("No stepped-sine alignment candidate was found"));
        boolean ambiguous = candidateRanges.stream().anyMatch(range -> range != strongest
                && range.score() >= bestScore - 0.02
                && Math.abs(range.centerMs() - strongest.centerMs()) > dwellMs);
        if (ambiguous)
            throw new IllegalArgumentException("Several stepped-sine sequences match this recording; set the recording offset manually");

        int estimateMs = (int) Math.round(strongest.centerMs());
        int uncertaintyMs = Math.max(coarseStepMs / 2,
                (int) Math.ceil((strongest.lastMs() - strongest.firstMs()) / 2.0 + coarseStepMs / 2.0));
        return new OffsetEstimate(estimateMs, bestScore, uncertaintyMs);
    }

    private static double scoreSweepAt(double[] samples, int sampleRate, double startHz, double endHz, int steps,
                                       int dwellFrames, int trimFrames, int scoreFrames, int sampleStride, long offsetFrames) {
        double score = 0;
        for (int step = 0; step < steps; step++) {
            double fraction = (double) step / (steps - 1);
            double frequency = startHz * Math.pow(endHz / startHz, fraction);
            int first = Math.toIntExact(offsetFrames + (long) step * dwellFrames + trimFrames);
            score += fitSine(samples, first, scoreFrames, sampleRate, frequency, sampleStride).toneFitScore();
        }
        return score / steps;
    }

    public static String toCsv(Result result) {
        StringBuilder csv = new StringBuilder("frequency_hz,amplitude_pcm,relative_db,clipped\n");
        for (Point point : result.points()) {
            csv.append(String.format(java.util.Locale.ROOT, "%.6f,%.9f,%.3f,%s%n", point.frequencyHz(),
                    point.amplitudePcm(), point.relativeDb(), point.clipped()));
        }
        return csv.toString();
    }

    /** Compares independently median-normalized curves on their common log-frequency range. */
    public static Comparison compare(Result before, Result after) {
        validateCurve(before);
        validateCurve(after);
        double minimumHz = Math.max(before.points().getFirst().frequencyHz(), after.points().getFirst().frequencyHz());
        double maximumHz = Math.min(before.points().getLast().frequencyHz(), after.points().getLast().frequencyHz());
        if (!(maximumHz > minimumHz)) throw new IllegalArgumentException("The response curves have no common frequency range");

        TreeSet<Double> frequencies = new TreeSet<>();
        for (Point point : before.points()) if (point.frequencyHz() >= minimumHz && point.frequencyHz() <= maximumHz) frequencies.add(point.frequencyHz());
        for (Point point : after.points()) if (point.frequencyHz() >= minimumHz && point.frequencyHz() <= maximumHz) frequencies.add(point.frequencyHz());
        if (frequencies.size() < 2) throw new IllegalArgumentException("The response curves need at least two shared frequency points");

        List<ComparisonPoint> points = frequencies.stream().map(frequency -> {
            double beforeDb = interpolateLogFrequency(before.points(), frequency);
            double afterDb = interpolateLogFrequency(after.points(), frequency);
            return new ComparisonPoint(frequency, beforeDb, afterDb, afterDb - beforeDb);
        }).toList();
        return new Comparison(points);
    }

    private static void validateCurve(Result result) {
        if (result == null || result.points().size() < 2) throw new IllegalArgumentException("Each response curve needs at least two points");
        double previous = 0;
        for (Point point : result.points()) {
            if (point == null || !Double.isFinite(point.frequencyHz()) || point.frequencyHz() <= previous
                    || !Double.isFinite(point.relativeDb()))
                throw new IllegalArgumentException("Response curve points must have increasing positive frequencies and finite levels");
            previous = point.frequencyHz();
        }
    }

    private static double interpolateLogFrequency(List<Point> points, double frequencyHz) {
        int low = 0;
        int high = points.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            double current = points.get(middle).frequencyHz();
            if (current == frequencyHz) return points.get(middle).relativeDb();
            if (current < frequencyHz) low = middle + 1;
            else high = middle - 1;
        }
        Point left = points.get(high);
        Point right = points.get(low);
        double fraction = (Math.log(frequencyHz) - Math.log(left.frequencyHz()))
                / (Math.log(right.frequencyHz()) - Math.log(left.frequencyHz()));
        return left.relativeDb() + fraction * (right.relativeDb() - left.relativeDb());
    }

    private static Fit fitSine(double[] samples, int first, int count, int sampleRate, double frequency) {
        return fitSine(samples, first, count, sampleRate, frequency, 1);
    }

    private static Fit fitSine(double[] samples, int first, int count, int sampleRate, double frequency, int sampleStride) {
        double[][] normal = new double[3][4];
        double omega = 2.0 * Math.PI * frequency * sampleStride / sampleRate;
        double sine = 0;
        double cosine = 1;
        double sineStep = Math.sin(omega);
        double cosineStep = Math.cos(omega);
        double sumSquares = 0;
        for (int i = 0; i < count; i++) {
            double sample = samples[first + i * sampleStride];
            normal[0][3] += sample;
            normal[1][3] += sine * sample;
            normal[2][3] += cosine * sample;
            normal[0][0] += 1;
            normal[0][1] += sine;
            normal[0][2] += cosine;
            normal[1][1] += sine * sine;
            normal[1][2] += sine * cosine;
            normal[2][2] += cosine * cosine;
            sumSquares += sample * sample;
            double nextSine = sine * cosineStep + cosine * sineStep;
            cosine = cosine * cosineStep - sine * sineStep;
            sine = nextSine;
        }
        normal[1][0] = normal[0][1];
        normal[2][0] = normal[0][2];
        normal[2][1] = normal[1][2];
        double[] rhs = {normal[0][3], normal[1][3], normal[2][3]};
        for (int pivot = 0; pivot < 3; pivot++) {
            int best = pivot;
            for (int row = pivot + 1; row < 3; row++)
                if (Math.abs(normal[row][pivot]) > Math.abs(normal[best][pivot])) best = row;
            if (Math.abs(normal[best][pivot]) < 1e-10) throw new IllegalArgumentException("Sweep window is too short for the selected frequency");
            double[] swap = normal[pivot]; normal[pivot] = normal[best]; normal[best] = swap;
            double scale = normal[pivot][pivot];
            for (int column = pivot; column < 4; column++) normal[pivot][column] /= scale;
            for (int row = 0; row < 3; row++) if (row != pivot) {
                double factor = normal[row][pivot];
                for (int column = pivot; column < 4; column++) normal[row][column] -= factor * normal[pivot][column];
            }
        }
        double mean = rhs[0] / count;
        double totalVariance = Math.max(0, sumSquares - rhs[0] * mean);
        double explainedVariance = normal[0][3] * rhs[0] + normal[1][3] * rhs[1] + normal[2][3] * rhs[2]
                - rhs[0] * mean;
        double toneFitScore = totalVariance <= 1e-15 ? 0 : Math.max(0, Math.min(1, explainedVariance / totalVariance));
        return new Fit(Math.hypot(normal[1][3], normal[2][3]), toneFitScore);
    }

    public record Recording(int sampleRate, int channelIndex, double[] samples) {
        public Recording {
            if (sampleRate < 8_000 || sampleRate > 192_000 || channelIndex < 0 || samples == null || samples.length == 0)
                throw new IllegalArgumentException("Invalid WAV recording");
            samples = samples.clone();
        }
        @Override public double[] samples() { return samples.clone(); }
    }

    public record Point(double frequencyHz, double amplitudePcm, double relativeDb, boolean clipped) {}
    public record ComparisonPoint(double frequencyHz, double beforeRelativeDb, double afterRelativeDb, double deltaDb) {}
    public record Comparison(List<ComparisonPoint> points) {
        public Comparison { points = List.copyOf(points); }
    }
    public record Result(int sampleRate, int channelIndex, double peakPcm, boolean clipped,
                         double referenceAmplitudePcm, List<Point> points) {
        public Result { points = List.copyOf(points); }
    }
    public record OffsetEstimate(int offsetMs, double sequenceScore, int uncertaintyMs) {}
    private record OffsetScore(int offsetMs, double score) {}
    private record OffsetRange(int firstMs, int lastMs, double score) {
        double centerMs() { return (firstMs + lastMs) / 2.0; }
    }
    private record RawPoint(double frequencyHz, double amplitude, boolean clipped) {}
    private record Fit(double amplitude, double toneFitScore) {}
}
