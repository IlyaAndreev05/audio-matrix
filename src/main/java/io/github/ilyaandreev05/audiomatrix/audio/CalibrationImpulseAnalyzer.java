package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.ArrayList;
import java.util.List;

/** Offline ring-down analysis for a sensor recording that contains an impulse and pre-roll. */
public final class CalibrationImpulseAnalyzer {
    private static final double DISPLAY_FLOOR_DB = -120.0;
    private static final double LOG_1000 = Math.log(1000.0);

    private CalibrationImpulseAnalyzer() {}

    /**
     * Finds the strongest response peak shortly after a user-specified impulse offset, then
     * estimates an energy-decay slope and a dominant modal frequency. T60 and Q are projections
     * from the measured -5 to -25 dB segment; they are not physical-unit or certification data.
     */
    public static Result analyze(CalibrationMeasurementAnalyzer.Recording recording,
                                 int impulseOffsetMs, int responseWindowMs) {
        if (recording == null) throw new IllegalArgumentException("A WAV recording is required");
        if (impulseOffsetMs < 20 || impulseOffsetMs > 60_000)
            throw new IllegalArgumentException("Impulse offset must be between 20 and 60000 ms to retain a noise pre-roll");
        if (responseWindowMs < 200 || responseWindowMs > 10_000)
            throw new IllegalArgumentException("Impulse response window must be between 200 and 10000 ms");

        int sampleRate = recording.sampleRate();
        double[] samples = recording.samples();
        int offsetFrame = Math.toIntExact((long) sampleRate * impulseOffsetMs / 1000);
        int windowFrames = Math.toIntExact((long) sampleRate * responseWindowMs / 1000);
        long endLong = (long) offsetFrame + windowFrames;
        if (endLong > samples.length)
            throw new IllegalArgumentException("WAV is shorter than the selected impulse offset and response window");
        int endFrame = (int) endLong;

        int baselineFrames = Math.min(offsetFrame, sampleRate / 10);
        if (baselineFrames < sampleRate / 50)
            throw new IllegalArgumentException("Keep at least 20 ms of sensor pre-roll before the impulse");
        int baselineStart = offsetFrame - baselineFrames;
        double baseline = 0;
        for (int i = baselineStart; i < offsetFrame; i++) baseline += samples[i];
        baseline /= baselineFrames;
        double noiseSquareSum = 0;
        for (int i = baselineStart; i < offsetFrame; i++) {
            double noise = samples[i] - baseline;
            noiseSquareSum += noise * noise;
        }
        double noiseRms = Math.sqrt(noiseSquareSum / baselineFrames);
        double noisePower = noiseRms * noiseRms;

        int peakSearchEnd = Math.min(endFrame, offsetFrame + Math.max(sampleRate / 100, sampleRate / 10));
        int peakFrame = offsetFrame;
        double peakAmplitude = 0;
        for (int i = offsetFrame; i < peakSearchEnd; i++) {
            double amplitude = Math.abs(samples[i] - baseline);
            if (amplitude > peakAmplitude) {
                peakAmplitude = amplitude;
                peakFrame = i;
            }
        }
        if (peakAmplitude <= Math.max(noiseRms * 6.0, 1e-7))
            throw new IllegalArgumentException("No clear impulse response rose above the pre-roll noise");

        int binFrames = Math.max(1, sampleRate / 100);
        int binCount = (endFrame - peakFrame + binFrames - 1) / binFrames;
        double[] binEnergy = new double[binCount];
        double[] binRms = new double[binCount];
        boolean clipped = false;
        for (int i = offsetFrame; i < endFrame; i++) {
            double sample = samples[i];
            clipped |= Math.abs(sample) >= 0.999;
        }
        for (int bin = 0; bin < binCount; bin++) {
            int first = peakFrame + bin * binFrames;
            int last = Math.min(endFrame, first + binFrames);
            double rawSquares = 0;
            double signalEnergy = 0;
            for (int i = first; i < last; i++) {
                double value = samples[i] - baseline;
                rawSquares += value * value;
                signalEnergy += Math.max(0, value * value - noisePower);
            }
            int count = last - first;
            binEnergy[bin] = signalEnergy;
            binRms[bin] = Math.sqrt(rawSquares / count);
        }

        double totalEnergy = 0;
        for (double energy : binEnergy) totalEnergy += energy;
        if (!(totalEnergy > 1e-15)) throw new IllegalArgumentException("Impulse response energy is below the measurable floor");
        double noiseBinEnergy = noisePower * binFrames;
        double noiseFloorDb = noiseBinEnergy <= 1e-15 ? DISPLAY_FLOOR_DB
                : Math.max(DISPLAY_FLOOR_DB, Math.min(0, 10.0 * Math.log10(noiseBinEnergy / totalEnergy)));
        double peakRms = 0;
        for (double rms : binRms) peakRms = Math.max(peakRms, rms);
        double integrated = 0;
        double[] integratedDecayDb = new double[binCount];
        for (int bin = binCount - 1; bin >= 0; bin--) {
            integrated += binEnergy[bin];
            integratedDecayDb[bin] = integrated <= noiseBinEnergy ? noiseFloorDb
                    : Math.max(noiseFloorDb, 10.0 * Math.log10(integrated / totalEnergy));
        }

        List<DecayPoint> points = new ArrayList<>(binCount);
        for (int bin = 0; bin < binCount; bin++) {
            double timeMs = (double) (bin * binFrames + Math.min(binFrames, endFrame - (peakFrame + bin * binFrames)) / 2)
                    * 1000.0 / sampleRate;
            double envelopeDb = binRms[bin] <= 1e-12 ? DISPLAY_FLOOR_DB
                    : Math.max(DISPLAY_FLOOR_DB, 20.0 * Math.log10(binRms[bin] / peakRms));
            points.add(new DecayPoint(timeMs, envelopeDb, integratedDecayDb[bin]));
        }

        DecayFit decay = fitDecay(points);
        Double dominantFrequency = dominantFrequency(samples, baseline, peakFrame, endFrame, sampleRate);
        Double qualityFactor = decay == null || dominantFrequency == null ? null
                : Math.PI * dominantFrequency * (decay.projectedT60Ms() / 1000.0) / LOG_1000;
        return new Result(sampleRate, recording.channelIndex(), impulseOffsetMs,
                (int) Math.round((double) peakFrame * 1000.0 / sampleRate), peakAmplitude, noiseRms,
                clipped, dominantFrequency, qualityFactor, decay == null ? null : decay.t20Ms(),
                decay == null ? null : decay.projectedT60Ms(), points);
    }

    public static String toCsv(Result result) {
        StringBuilder csv = new StringBuilder("time_ms,envelope_relative_db,integrated_decay_db\n");
        for (DecayPoint point : result.points())
            csv.append(String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f%n", point.timeMs(),
                    point.envelopeRelativeDb(), point.integratedDecayDb()));
        return csv.toString();
    }

    private static DecayFit fitDecay(List<DecayPoint> points) {
        double sumX = 0, sumY = 0, sumXX = 0, sumXY = 0;
        int count = 0;
        for (DecayPoint point : points) {
            double y = point.integratedDecayDb();
            if (y < -25.0 || y > -5.0) continue;
            double x = point.timeMs() / 1000.0;
            sumX += x; sumY += y; sumXX += x * x; sumXY += x * y; count++;
        }
        double denominator = count * sumXX - sumX * sumX;
        if (count < 4 || Math.abs(denominator) < 1e-12) return null;
        double slopeDbPerSecond = (count * sumXY - sumX * sumY) / denominator;
        if (!(slopeDbPerSecond < -0.1)) return null;
        return new DecayFit(-20_000.0 / slopeDbPerSecond, -60_000.0 / slopeDbPerSecond);
    }

    private static Double dominantFrequency(double[] samples, double baseline, int peakFrame, int endFrame, int sampleRate) {
        int first = Math.min(endFrame, peakFrame + sampleRate / 100);
        int available = endFrame - first;
        if (available < sampleRate / 5) return null;
        int stride = Math.max(1, (sampleRate + 23_999) / 24_000);
        int count = available / stride;
        int effectiveSampleRate = sampleRate / stride;
        if (count < effectiveSampleRate / 5) return null;

        double mean = 0;
        for (int i = 0; i < count; i++) mean += samples[first + i * stride] - baseline;
        mean /= count;
        double[] windowed = new double[count];
        for (int i = 0; i < count; i++) {
            double window = count == 1 ? 1 : 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (count - 1));
            windowed[i] = (samples[first + i * stride] - baseline - mean) * window;
        }
        double[] spectrum = new double[981];
        int peakBin = -1;
        double peakPower = 0;
        for (int frequency = 20; frequency <= 1000; frequency++) {
            double omega = 2.0 * Math.PI * frequency * stride / sampleRate;
            double coefficient = 2.0 * Math.cos(omega);
            double previous = 0, previous2 = 0;
            for (int i = 0; i < count; i++) {
                double current = windowed[i] + coefficient * previous - previous2;
                previous2 = previous;
                previous = current;
            }
            double power = Math.max(0, previous * previous + previous2 * previous2 - coefficient * previous * previous2);
            spectrum[frequency - 20] = power;
            if (power > peakPower) { peakPower = power; peakBin = frequency - 20; }
        }
        return peakBin < 0 || peakPower <= 1e-12 ? null : peakBin + 20.0;
    }

    public record DecayPoint(double timeMs, double envelopeRelativeDb, double integratedDecayDb) {}
    public record Result(int sampleRate, int channelIndex, int impulseOffsetMs, int detectedPeakOffsetMs,
                         double peakPcm, double noiseRmsPcm, boolean clipped, Double dominantFrequencyHz,
                         Double qualityFactor, Double decayT20Ms, Double projectedT60Ms, List<DecayPoint> points) {
        public Result { points = List.copyOf(points); }
    }
    private record DecayFit(double t20Ms, double projectedT60Ms) {}
}
