package io.github.ilyaandreev05.audiomatrix.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Generates deliberately short, low-level signals for manual listening checks. */
final class CalibrationSignal {
    static final int SAMPLE_RATE = 48_000;
    static final double AMPLITUDE = 0.025;
    static final int IMPULSE_DURATION_MS = 10;
    static final double IMPULSE_FREQUENCY_HZ = 100.0;
    static final double IMPULSE_AMPLITUDE = 0.005;
    private static final int CHANNELS = 2;
    private static final int BYTES_PER_SAMPLE = 2;

    private CalibrationSignal() {}

    static void writeSteppedSine(Path path, double startHz, double endHz, int steps, int dwellMs) throws IOException {
        Files.write(path, steppedSineWave(startHz, endHz, steps, dwellMs));
    }

    static void writeManualImpulse(Path path) throws IOException {
        Files.write(path, manualImpulseWave());
    }

    static byte[] manualImpulseWave() {
        int frames = SAMPLE_RATE * IMPULSE_DURATION_MS / 1000;
        byte[] pcm = new byte[Math.multiplyExact(frames, 4)];
        ByteBuffer samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < frames; frame++) {
            double position = (double) frame / (frames - 1);
            double envelope = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * position);
            double phase = 2.0 * Math.PI * IMPULSE_FREQUENCY_HZ * frame / SAMPLE_RATE;
            short value = (short) Math.round(Math.sin(phase) * envelope * IMPULSE_AMPLITUDE * Short.MAX_VALUE);
            samples.putShort(value).putShort(value);
        }
        return wav(pcm, SAMPLE_RATE, CHANNELS, BYTES_PER_SAMPLE);
    }

    static byte[] steppedSineWave(double startHz, double endHz, int steps, int dwellMs) {
        validate(startHz, endHz, steps, dwellMs);
        int framesPerStep = SAMPLE_RATE * dwellMs / 1000;
        int frames = Math.multiplyExact(framesPerStep, steps);
        int dataLength = Math.multiplyExact(Math.multiplyExact(frames, CHANNELS), BYTES_PER_SAMPLE);
        byte[] pcm = new byte[dataLength];
        ByteBuffer samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        int rampFrames = Math.min(SAMPLE_RATE / 100, framesPerStep / 2);

        for (int step = 0; step < steps; step++) {
            double position = steps == 1 ? 0 : (double) step / (steps - 1);
            double frequency = startHz * Math.pow(endHz / startHz, position);
            for (int frame = 0; frame < framesPerStep; frame++) {
                double envelope = 1.0;
                if (rampFrames > 0) envelope = Math.min(1.0, Math.min((double) frame / rampFrames,
                        (double) (framesPerStep - 1 - frame) / rampFrames));
                double phase = 2.0 * Math.PI * frequency * frame / SAMPLE_RATE;
                short value = (short) Math.round(Math.sin(phase) * AMPLITUDE * envelope * Short.MAX_VALUE);
                samples.putShort(value).putShort(value);
            }
        }
        return wav(pcm, SAMPLE_RATE, CHANNELS, BYTES_PER_SAMPLE);
    }

    static void validate(double startHz, double endHz, int steps, int dwellMs) {
        if (!Double.isFinite(startHz) || !Double.isFinite(endHz) || startHz < 20 || endHz > 1000 || endHz <= startHz)
            throw new IllegalArgumentException("Sweep frequencies must be increasing and within 20–1000 Hz");
        if (steps < 2 || steps > 16) throw new IllegalArgumentException("Sweep step count must be between 2 and 16");
        if (dwellMs < 50 || dwellMs > 300) throw new IllegalArgumentException("Sweep dwell must be between 50 and 300 ms");
    }

    private static byte[] wav(byte[] pcm, int sampleRate, int channels, int bytesPerSample) {
        int blockAlign = channels * bytesPerSample;
        ByteBuffer wav = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36 + pcm.length).put(new byte[]{'W','A','V','E'});
        wav.put(new byte[]{'f','m','t',' '}).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(sampleRate).putInt(sampleRate * blockAlign).putShort((short) blockAlign).putShort((short) (bytesPerSample * 8));
        wav.put(new byte[]{'d','a','t','a'}).putInt(pcm.length).put(pcm);
        return wav.array();
    }
}
