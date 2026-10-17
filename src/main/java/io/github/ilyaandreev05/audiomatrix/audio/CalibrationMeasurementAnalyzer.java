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
            CalibrationSignalFit.Fit fit = CalibrationSignalFit.fit(samples, first, analysisFrames, sampleRate, frequency);
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
        return SweepOffsetEstimator.estimate(recording, startHz, endHz, steps, dwellMs, maxSearchMs);
    }

    public static String toCsv(Result result) { return CalibrationComparison.toCsv(result); }

    /** Compares independently median-normalized curves on their common log-frequency range. */
    public static Comparison compare(Result before, Result after) {
        return CalibrationComparison.compare(before, after);
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
    private record RawPoint(double frequencyHz, double amplitude, boolean clipped) {}
}
