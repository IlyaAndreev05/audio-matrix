package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Observes one PipeWire node's actual Props volume and mute through pw-dump --monitor. */
final class MasterVolumeLatencyMonitor implements AutoCloseable {
    record Sample(double volume, boolean muted, long atNanos) {}

    private final Process process;
    private final int nodeId;
    private final BlockingQueue<Sample> samples = new LinkedBlockingQueue<>();
    private final ObjectMapper json = new ObjectMapper();
    private final Thread reader;
    private volatile boolean running = true;

    private MasterVolumeLatencyMonitor(Process process, int nodeId) {
        this.process = process;
        this.nodeId = nodeId;
        this.reader = Thread.ofVirtual().name("master-latency-monitor").start(this::read);
    }

    static MasterVolumeLatencyMonitor start(int nodeId) throws IOException {
        Process process = new ProcessBuilder("pw-dump", "--monitor", "--no-colors")
                .redirectErrorStream(true).start();
        return new MasterVolumeLatencyMonitor(process, nodeId);
    }

    private void read() {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            PipeWireMonitorChange change = new PipeWireMonitorChange();
            String line;
            while (running && (line = lines.readLine()) != null) {
                PipeWireMonitorChange.Update update = change.accept(line);
                if (update != null && update.payload() != null) inspect(update.payload());
            }
        } catch (IOException ignored) {
            // The monitor is stopped by close(); a broken pipe ends the observation.
        }
    }

    private void inspect(String payload) {
        try {
            for (JsonNode object : json.readTree(payload)) {
                if (object.path("id").asInt(-1) != nodeId) continue;
                for (JsonNode props : object.path("info").path("params").path("Props")) {
                    if (!props.has("volume") && !props.has("channelVolumes")) continue;
                    samples.offer(new Sample(level(props), props.path("mute").asBoolean(false),
                            System.nanoTime()));
                    break;
                }
            }
        } catch (IOException malformed) {
            // A partial or unrelated monitor payload is not a node observation.
        }
    }

    /** The Mixer API stores the linear level in channelVolumes; volume alone is only a fallback. */
    private static double level(JsonNode props) {
        JsonNode channels = props.path("channelVolumes");
        if (channels.isArray() && !channels.isEmpty()) {
            double level = 0;
            for (JsonNode channel : channels) level = Math.max(level, channel.asDouble());
            return level;
        }
        return props.path("volume").asDouble();
    }

    void drain() { samples.clear(); }

    Sample awaitVolume(double expected, double tolerance, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return null;
            Sample sample = samples.poll(remaining, TimeUnit.NANOSECONDS);
            if (sample == null) return null;
            if (Math.abs(sample.volume() - expected) <= tolerance) return sample;
        }
    }

    Sample awaitMuted(boolean muted, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return null;
            Sample sample = samples.poll(remaining, TimeUnit.NANOSECONDS);
            if (sample == null) return null;
            if (sample.muted() == muted) return sample;
        }
    }

    @Override public void close() {
        running = false;
        process.destroyForcibly();
        try { reader.join(1000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
