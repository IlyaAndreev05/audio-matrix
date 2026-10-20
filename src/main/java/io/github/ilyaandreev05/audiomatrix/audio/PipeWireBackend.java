package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.Link;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.CRC32;

public final class PipeWireBackend implements AutoCloseable {
    public static final String SYSTEM_MIXER_SINK_NAME = "audiomatrix_system";
    private final ExecutorService io = Executors.newFixedThreadPool(3, r -> daemon(r, "audio-matrix-pipewire-io"));
    private final ScheduledExecutorService changes = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "audio-matrix-pipewire-events"));
    private final PipeWireCalibrationController calibration = new PipeWireCalibrationController(io);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final AtomicLong connectionGeneration = new AtomicLong();
    private final PipeWireLevelMonitor outputLevelMonitor = new PipeWireLevelMonitor();
    private final PipeWireLevelMonitor inputLevelMonitor = new PipeWireLevelMonitor();
    private volatile Process monitor;
    private volatile Consumer<Snapshot> listener = ignored -> {};
    private volatile Snapshot last = Snapshot.offline("Not connected to PipeWire yet");
    private volatile String backendMessage = "PipeWire not checked";

    private static Thread daemon(Runnable runnable, String name) { Thread t = new Thread(runnable, name); t.setDaemon(true); return t; }

    public void start(Consumer<Snapshot> listener) {
        this.listener = listener == null ? ignored -> {} : listener;
        refresh().thenAccept(snapshot -> this.listener.accept(snapshot));
        Thread.ofVirtual().name("audio-matrix-pipewire-monitor").start(this::monitorLoop);
    }

    public Snapshot current() { return last; }
    public long connectionGeneration() { return connectionGeneration.get(); }
    public String backendMessage() { return backendMessage; }
    public boolean canMoveStreams() { return executable("pactl") || executable("pw-link"); }
    public void monitorOutput(AudioNode sink) { outputLevelMonitor.setTarget(sink); }
    public PipeWireLevelMonitor.Reading outputLevel() { return outputLevelMonitor.reading(); }
    public void monitorInput(AudioNode source) { inputLevelMonitor.setInputTarget(source); }
    public PipeWireLevelMonitor.Reading inputLevel() { return inputLevelMonitor.reading(); }

    public CompletableFuture<Snapshot> refresh() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Snapshot snapshot = readSnapshot();
                last = snapshot;
                backendMessage = "PipeWire connected — live state from pw-dump and wpctl";
                return snapshot;
            } catch (Exception e) {
                Snapshot snapshot = Snapshot.offline(safeMessage(e));
                last = snapshot;
                backendMessage = "PipeWire unavailable: " + safeMessage(e);
                return snapshot;
            }
        }, io);
    }

    public CompletableFuture<Void> setVolume(int nodeId, double volume) { return WirePlumberMixerControl.setVolumeAsync(nodeId, clamp(volume, 0, 1), io); }
    public CompletableFuture<Void> setMute(int nodeId, boolean muted) { return WirePlumberMixerControl.setMutedAsync(nodeId, muted, io); }
    public CompletableFuture<Void> setMute(int nodeId, boolean muted, double savedLevel) {
        return WirePlumberMixerControl.setMutedAsync(nodeId, muted, savedLevel, io);
    }
    public CompletableFuture<Void> setDefaultOutput(int nodeId) {
        return execute("wpctl", "set-default", Integer.toString(nodeId));
    }

    public CompletableFuture<Void> moveStream(AudioNode stream, AudioNode sink) {
        if (stream == null || sink == null || !"stream".equals(stream.kind()) || !"output".equals(sink.kind()))
            return CompletableFuture.failedFuture(new IllegalArgumentException("A live playback stream and an output sink are required"));
        if (executable("pactl") && stream.pulseId() != null)
            return execute("pactl", "move-sink-input", stream.pulseId().toString(), sink.name());
        if (!executable("pw-link")) return CompletableFuture.failedFuture(new IOException("Neither pactl nor pw-link is available for stream transfer"));
        return CompletableFuture.runAsync(() -> {
            List<Link> previous = List.of(), connected = new ArrayList<>();
            try {
                List<String> sourcePorts = PipeWireLinkOperations.portsForNode(run(List.of("pw-link", "-o"), Duration.ofSeconds(3)), stream.name());
                List<String> targetPorts = PipeWireLinkOperations.portsForNode(run(List.of("pw-link", "-i"), Duration.ofSeconds(3)), sink.name());
                List<Link> replacement = PipeWireRouteManager.channelLinks(sourcePorts, targetPorts, "stereo", false);
                if (replacement.isEmpty()) throw new IOException("No compatible audio ports are available on the selected sink");
                previous = PipeWireLinkOperations.linksFromNode(run(List.of("pw-link", "-l"), Duration.ofSeconds(3)), stream.name());
                if (previous.equals(replacement)) return;
                for (Link link : previous) run(List.of("pw-link", "-d", link.output(), link.input()), Duration.ofSeconds(3));
                for (Link link : replacement) {
                    run(List.of("pw-link", "--wait", link.output(), link.input()), Duration.ofSeconds(3));
                    connected.add(link);
                }
            } catch (Exception failure) {
                for (Link link : connected) try { run(List.of("pw-link", "-d", link.output(), link.input()), Duration.ofSeconds(2)); }
                catch (Exception ignored) {}
                for (Link link : previous) try { run(List.of("pw-link", "--wait", link.output(), link.input()), Duration.ofSeconds(3)); }
                catch (Exception restoreFailure) { failure.addSuppressed(restoreFailure); }
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, io);
    }

    public CompletableFuture<Void> playTestTone(AudioNode sink, double frequencyHz, int durationMs) {
        return calibration.playTestTone(sink, frequencyHz, durationMs);
    }

    public CompletableFuture<Void> playSteppedSweep(AudioNode sink, double startHz, double endHz, int steps) {
        return calibration.playSteppedSweep(sink, startHz, endHz, steps);
    }

    public CompletableFuture<Void> playManualImpulse(AudioNode sink) {
        return calibration.playManualImpulse(sink);
    }

    public CompletableFuture<SweepCapture> captureSteppedSweep(AudioNode source, AudioNode sink,
                                                                double startHz, double endHz, int steps) {
        return calibration.captureSteppedSweep(source, sink, startHz, endHz, steps);
    }

    public void cancelCalibrationCapture() { calibration.cancel(); }
    public void stopCalibrationCapture() { calibration.cancel(); }

    public record SweepCapture(Path temporaryDirectory, Path recording, int sweepOffsetMs) {}

    private Snapshot readSnapshot() throws Exception {
        return new PipeWireSnapshotReader().read(last);
    }

    static Map<Integer, JsonNode> clientProperties(JsonNode root) {
        return PipeWireSnapshotReader.clientProperties(root);
    }

    static ApplicationIdentity applicationIdentity(JsonNode properties, Map<Integer, JsonNode> clients) {
        return PipeWireSnapshotReader.applicationIdentity(properties, clients);
    }

    record ApplicationIdentity(String applicationName, String processName, String applicationId) {}

    static boolean isSnapshotAudioNode(String mediaClass, String name) {
        return PipeWireSnapshotReader.isSnapshotAudioNode(mediaClass, name);
    }

    static int defaultSinkId(String status) {
        return PipeWireSnapshotReader.defaultSinkId(status);
    }

    static Map<Integer, LevelState> levelStates(String status) {
        return PipeWireSnapshotReader.levelStates(status);
    }

    record LevelState(double volume, boolean muted) {}

    private void monitorLoop() {
        PipeWireMonitorReconnect reconnect = new PipeWireMonitorReconnect();
        while (!closed.get()) {
            try {
                if (!executable("pw-dump")) throw new IOException("pw-dump is unavailable");
                monitor = new ProcessBuilder("pw-dump", "--monitor", "--no-colors").redirectErrorStream(true).start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(monitor.getInputStream()))) {
                    boolean initial = false; int depth = 0; boolean inString = false, escaped = false;
                    String line;
                    while (!closed.get() && (line = reader.readLine()) != null) {
                        if (initial) {
                            if (!line.isBlank()) queueRefresh();
                            continue;
                        }
                        for (int i = 0; i < line.length(); i++) {
                            char c = line.charAt(i);
                            if (inString) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') inString = false; continue; }
                            if (c == '"') inString = true;
                            else if (c == '[') depth++;
                            else if (c == ']') {
                                depth--;
                                if (depth == 0) {
                                    initial = true;
                                    if (reconnect.initialSnapshotReceived()) {
                                        connectionGeneration.incrementAndGet();
                                        queueReconnectRefresh();
                                    }
                                }
                            }
                        }
                    }
                }
                if (!closed.get()) {
                    reconnect.monitorUnavailable();
                    backendMessage = "PipeWire monitor restarted; reconnecting";
                    Thread.sleep(1200);
                }
            } catch (Exception e) {
                if (!closed.get()) {
                    reconnect.monitorUnavailable();
                    backendMessage = "PipeWire monitor unavailable: " + safeMessage(e);
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        }
    }
    private void queueRefresh() {
        if (!refreshQueued.compareAndSet(false, true)) return;
        changes.schedule(() -> refresh().thenAccept(snapshot -> listener.accept(snapshot)).whenComplete((ignored, error) -> refreshQueued.set(false)), 250, TimeUnit.MILLISECONDS);
    }
    private void queueReconnectRefresh() {
        if (closed.get()) return;
        changes.schedule(() -> refresh().thenAccept(snapshot -> listener.accept(snapshot)), 250, TimeUnit.MILLISECONDS);
    }
    private CompletableFuture<Void> execute(String... command) {
        return CompletableFuture.runAsync(() -> {
            try { run(List.of(command), Duration.ofSeconds(4)); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }, io);
    }
    static String run(List<String> command, Duration timeout) throws Exception {
        return PipeWireCommandRunner.run(command, timeout);
    }
    static boolean executable(String name) {
        return PipeWireCommandRunner.executable(name);
    }
    private static String safeMessage(Throwable e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    static double clamp(double value, double min, double max) { return PipeWireCommandRunner.clamp(value, min, max); }

    @Override public void close() {
        closed.set(true);
        calibration.close();
        outputLevelMonitor.close();
        inputLevelMonitor.close();
        Process current = monitor;
        if (current != null) current.destroyForcibly();
        io.shutdownNow(); changes.shutdownNow();
    }

    public record Snapshot(boolean connected, String error, List<AudioNode> sinks, List<AudioNode> sources,
                           List<AudioNode> streams, List<AudioNode> recentlyEnded, Map<String, Long> streamLastSeen,
                           int defaultSinkId, long capturedAtMillis) {
        public static Snapshot offline(String message) { return new Snapshot(false, message, List.of(), List.of(), List.of(), List.of(), Map.of(), -1, System.currentTimeMillis()); }
    }
}
