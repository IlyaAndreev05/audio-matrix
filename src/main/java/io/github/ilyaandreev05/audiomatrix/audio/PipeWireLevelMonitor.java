package io.github.ilyaandreev05.audiomatrix.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Reads only a selected sink's PipeWire monitor; PCM is reduced to short-term levels and never stored. */
public final class PipeWireLevelMonitor implements AutoCloseable {
    private static final int RATE = 48_000;
    private static final int WINDOW_FRAMES = RATE / 20;
    private final AtomicReference<Reading> reading = new AtomicReference<>(Reading.waiting());
    private volatile Process process;
    private volatile Target target;
    private volatile boolean closed;

    public synchronized void setTarget(AudioNode sink) { setTarget(sink, "output"); }
    public synchronized void setInputTarget(AudioNode source) { setTarget(source, "input"); }

    private synchronized void setTarget(AudioNode node, String expectedKind) {
        Target next = node == null || !expectedKind.equals(node.kind()) ? null
                : new Target(node.id(), node.name(), Math.max(1, Math.min(8, node.channels())), expectedKind);
        if (next == null) {
            stopCapture();
            target = null;
            reading.set(Reading.unavailable("No " + expectedKind + " selected"));
            return;
        }
        if (closed || next.equals(target) && process != null && process.isAlive()) return;
        stopCapture();
        target = next;
        reading.set(Reading.waiting());
        Thread.ofVirtual().name("audio-matrix-output-meter-" + next.nodeId()).start(() -> capture(next));
    }

    public Reading reading() {
        Reading current = reading.get();
        if (current.available() && System.currentTimeMillis() - current.updatedAtMillis() > 1_500)
            return Reading.unavailable("No recent samples from the output monitor");
        return current;
    }

    private void capture(Target selected) {
        Process started = null;
        try {
            if (!executable("pw-cat")) throw new IOException("pw-cat is not installed");
            List<String> command = List.of("pw-cat", "--record", "--target", Integer.toString(selected.nodeId()),
                    "--raw", "--format", "f32", "--rate", Integer.toString(RATE),
                    "--channels", Integer.toString(selected.channels()), "--channel-map", defaultMap(selected.channels()), "-");
            started = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            synchronized (this) {
                if (closed || !selected.equals(target)) { started.destroyForcibly(); return; }
                process = started;
            }
            readSamples(started.getInputStream(), selected);
            int exit = started.waitFor();
            if (!closed && selected.equals(target)) reading.set(Reading.unavailable(label(selected.kind()) + " monitor stopped (pw-cat " + exit + ")"));
        } catch (Exception e) {
            if (!closed && selected.equals(target)) reading.set(Reading.unavailable(label(selected.kind()) + " monitor unavailable: " + safeMessage(e)));
        } finally {
            if (started != null) started.destroy();
            synchronized (this) { if (process == started) process = null; }
        }
    }

    private void readSamples(InputStream input, Target selected) throws IOException {
        byte[] bytes = new byte[16 * 1024];
        byte[] partial = new byte[selected.channels() * Float.BYTES];
        int partialSize = 0;
        int framesInWindow = 0;
        double peak = 0;
        double sumSquares = 0;
        long samplesInWindow = 0;
        while (!closed && selected.equals(target)) {
            int count = input.read(bytes);
            if (count < 0) break;
            if (count == 0) continue;
            int offset = 0;
            if (partialSize > 0) {
                int needed = partial.length - partialSize;
                int copied = Math.min(needed, count);
                System.arraycopy(bytes, 0, partial, partialSize, copied);
                partialSize += copied;
                offset += copied;
                if (partialSize < partial.length) continue;
                ByteBuffer frame = ByteBuffer.wrap(partial).order(ByteOrder.LITTLE_ENDIAN);
                while (frame.hasRemaining()) {
                    float sample = frame.getFloat();
                    if (Float.isFinite(sample)) { peak = Math.max(peak, Math.abs(sample)); sumSquares += sample * (double) sample; samplesInWindow++; }
                }
                framesInWindow++;
                partialSize = 0;
                if (framesInWindow >= WINDOW_FRAMES) {
                    publish(peak, sumSquares, samplesInWindow, selected);
                    framesInWindow = 0; peak = 0; sumSquares = 0; samplesInWindow = 0;
                }
            }
            int frameBytes = partial.length;
            int completeBytes = ((count - offset) / frameBytes) * frameBytes;
            ByteBuffer data = ByteBuffer.wrap(bytes, offset, completeBytes).order(ByteOrder.LITTLE_ENDIAN);
            while (data.remaining() >= frameBytes) {
                for (int channel = 0; channel < selected.channels(); channel++) {
                    float sample = data.getFloat();
                    if (!Float.isFinite(sample)) continue;
                    peak = Math.max(peak, Math.abs(sample));
                    sumSquares += sample * (double) sample;
                    samplesInWindow++;
                }
                framesInWindow++;
                if (framesInWindow >= WINDOW_FRAMES) {
                    publish(peak, sumSquares, samplesInWindow, selected);
                    framesInWindow = 0; peak = 0; sumSquares = 0; samplesInWindow = 0;
                }
            }
            offset += completeBytes;
            partialSize = count - offset;
            if (partialSize > 0) System.arraycopy(bytes, offset, partial, 0, partialSize);
        }
    }

    private void publish(double peak, double sumSquares, long samples, Target selected) {
        double rms = samples == 0 ? 0 : Math.sqrt(sumSquares / samples);
        reading.set(new Reading(true, "", AudioMeterMath.dbfs(peak), AudioMeterMath.dbfs(rms), peak >= 1.0,
                System.currentTimeMillis(), selected.name(), selected.kind()));
    }

    private static String label(String kind) { return "input".equals(kind) ? "Input" : "Output"; }

    private static String defaultMap(int channels) {
        return switch (channels) {
            case 1 -> "MONO";
            case 2 -> "FL,FR";
            case 3 -> "FL,FR,FC";
            case 4 -> "FL,FR,RL,RR";
            case 5 -> "FL,FR,FC,RL,RR";
            case 6 -> "FL,FR,FC,LFE,SL,SR";
            case 7 -> "FL,FR,FC,LFE,RC,SL,SR";
            default -> "FL,FR,FC,LFE,RL,RR,SL,SR";
        };
    }

    private static boolean executable(String name) {
        try { return new ProcessBuilder("sh", "-lc", "command -v " + name).start().waitFor() == 0; }
        catch (Exception ignored) { return false; }
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
    }

    private synchronized void stopCapture() {
        Process current = process;
        process = null;
        if (current != null) current.destroyForcibly();
    }

    @Override public synchronized void close() {
        closed = true;
        target = null;
        stopCapture();
        reading.set(Reading.unavailable("Output meter stopped"));
    }

    public record Reading(boolean available, String message, double peakDbfs, double rmsDbfs,
                         boolean clipped, long updatedAtMillis, String nodeName, String nodeKind) {
        static Reading waiting() { return new Reading(false, "Waiting for monitor samples", Double.NaN, Double.NaN, false, 0, "", ""); }
        static Reading unavailable(String message) { return new Reading(false, message, Double.NaN, Double.NaN, false, 0, "", ""); }
    }

    private record Target(int nodeId, String name, int channels, String kind) {}
}
