package io.github.ilyaandreev05.audiomatrix.audio;

/** Small, allocation-free helpers for turning decoded PCM into digital meter values. */
public final class AudioMeterMath {
    private AudioMeterMath() {}

    public static Measurement measure(float[] interleaved, int channels) {
        if (interleaved == null || channels < 1 || interleaved.length == 0 || interleaved.length % channels != 0)
            throw new IllegalArgumentException("Interleaved samples must contain complete channel frames");
        double peak = 0;
        double sumSquares = 0;
        long finiteSamples = 0;
        for (float sample : interleaved) {
            if (!Float.isFinite(sample)) continue;
            double magnitude = Math.abs(sample);
            peak = Math.max(peak, magnitude);
            sumSquares += sample * (double) sample;
            finiteSamples++;
        }
        double rms = finiteSamples == 0 ? 0 : Math.sqrt(sumSquares / finiteSamples);
        return new Measurement(dbfs(peak), dbfs(rms), peak >= 1.0, finiteSamples);
    }

    public static double dbfs(double linear) {
        if (!Double.isFinite(linear) || linear <= 0) return -120.0;
        return 20.0 * Math.log10(linear);
    }

    public record Measurement(double peakDbfs, double rmsDbfs, boolean clipped, long sampleCount) {}
}
