package io.github.ilyaandreev05.audiomatrix.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Measures reference-to-routed frame offsets in one simultaneous stereo f32 capture. */
final class EventLatencyPcmSupport {
    private static final int FRAME_BYTES = 2 * Float.BYTES;
    private static final int RINGING_FRAMES = 8;
    private static final double ONSET_FRACTION = 0.5;
    private static final double QUIET_FRACTION = 0.05;
    private static final double RINGING_FRACTION = 0.25;

    private EventLatencyPcmSupport() {}

    /**
     * FL is the reference and FR the routed signal, both little-endian on the same capture clock.
     * Each onset is one sample at least half its channel's absolute peak. Activity above 5% of
     * that peak must be within eight frames of exactly one onset and at most 25% of its amplitude;
     * this tolerates minor ringing without merging broad pulses or secondary peaks into an event.
     * Percentiles use nearest rank: sorted[ceil(percent * count / 100) - 1].
     */
    static Result analyze(byte[] pcm, int sampleRate, int expectedImpulseCount,
                          int minSeparationFrames, int maxDelayFrames) {
        if (pcm == null || pcm.length == 0)
            throw new IllegalArgumentException("The PCM capture is missing or empty");
        if (pcm.length % FRAME_BYTES != 0)
            throw new IllegalArgumentException("The PCM capture ends with a partial stereo f32 frame");
        if (sampleRate <= 0 || expectedImpulseCount <= 0 || minSeparationFrames <= 0 || maxDelayFrames < 0)
            throw new IllegalArgumentException("Rate, count and separation must be positive; maximum delay must be nonnegative");
        int frames = pcm.length / FRAME_BYTES;
        if (expectedImpulseCount > frames)
            throw new IllegalArgumentException("The expected impulse count exceeds the captured frame count");
        ByteBuffer samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        double referencePeak = 0, routedPeak = 0;
        for (int frame = 0; frame < frames; frame++) {
            float reference = samples.getFloat();
            float routed = samples.getFloat();
            if (!Float.isFinite(reference) || !Float.isFinite(routed))
                throw new IllegalArgumentException("Non-finite PCM sample at frame " + frame);
            referencePeak = Math.max(referencePeak, Math.abs((double) reference));
            routedPeak = Math.max(routedPeak, Math.abs((double) routed));
        }
        if (referencePeak == 0) throw new IllegalArgumentException("The reference channel has no signal");
        if (routedPeak == 0) throw new IllegalArgumentException("The routed channel has no signal");
        int[] reference = onsets(samples, frames, 0, referencePeak, expectedImpulseCount, minSeparationFrames);
        int[] routed = onsets(samples, frames, 1, routedPeak, expectedImpulseCount, minSeparationFrames);
        List<Integer> delays = new ArrayList<>(expectedImpulseCount);
        int[] sortedDelays = new int[expectedImpulseCount];
        for (int index = 0; index < expectedImpulseCount; index++) {
            int delay = routed[index] - reference[index];
            if (delay < 0 || delay > maxDelayFrames)
                throw new IllegalArgumentException("Impulse " + index + " has delay " + delay
                        + " frames, outside [0, " + maxDelayFrames + "]");
            delays.add(delay);
            sortedDelays[index] = delay;
        }
        Arrays.sort(sortedDelays);
        double millisecondsPerFrame = 1000.0 / sampleRate;
        return new Result(percentile(sortedDelays, 50) * millisecondsPerFrame,
                percentile(sortedDelays, 95) * millisecondsPerFrame,
                sortedDelays[sortedDelays.length - 1] * millisecondsPerFrame,
                expectedImpulseCount, delays);
    }

    private static int[] onsets(ByteBuffer samples, int frames, int channel, double peak,
                                int expected, int minSeparationFrames) {
        String name = channel == 0 ? "Reference" : "Routed";
        int[] onsets = new int[expected];
        int count = 0;
        double threshold = peak * ONSET_FRACTION;
        for (int frame = 0; frame < frames; frame++) {
            if (amplitude(samples, frame, channel) < threshold) continue;
            if (count > 0 && frame - onsets[count - 1] < Math.max(2, minSeparationFrames))
                throw new IllegalArgumentException(name + " has broad, ambiguous or too-close impulse onsets");
            if (count == expected)
                throw new IllegalArgumentException(name + " has more than " + expected + " impulses");
            onsets[count++] = frame;
        }
        if (count != expected)
            throw new IllegalArgumentException(name + " has " + count + " impulses; expected " + expected);
        int next = 0;
        double quietThreshold = peak * QUIET_FRACTION;
        for (int frame = 0; frame < frames; frame++) {
            while (next < count && onsets[next] < frame) next++;
            if (next < count && onsets[next] == frame) continue;
            double value = amplitude(samples, frame, channel);
            if (value <= quietThreshold) continue;
            boolean nearPrevious = next > 0 && frame - onsets[next - 1] <= RINGING_FRAMES;
            boolean nearNext = next < count && onsets[next] - frame <= RINGING_FRAMES;
            if (nearPrevious == nearNext)
                throw new IllegalArgumentException(name + " has broad or ambiguous activity outside an isolated onset");
            int onset = nearPrevious ? onsets[next - 1] : onsets[next];
            if (value > amplitude(samples, onset, channel) * RINGING_FRACTION)
                throw new IllegalArgumentException(name + " has an ambiguous secondary peak near frame " + onset);
        }
        return onsets;
    }

    private static double amplitude(ByteBuffer samples, int frame, int channel) {
        return Math.abs((double) samples.getFloat(frame * FRAME_BYTES + channel * Float.BYTES));
    }

    private static int percentile(int[] sorted, int percent) {
        return sorted[(int) (((long) percent * sorted.length + 99) / 100) - 1];
    }

    record Result(double latencyMsP50, double latencyMsP95, double latencyMsMax,
                  int sampleCount, List<Integer> delayFrames) {
        Result {
            delayFrames = List.copyOf(delayFrames);
        }
    }
}
