package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteManager.Link;

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
    private static final int SAMPLE_RATE = 48_000;
    private static final int CALIBRATION_SWEEP_LEAD_MS = 500;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService io = Executors.newFixedThreadPool(3, r -> daemon(r, "audio-matrix-pipewire-io"));
    private final ScheduledExecutorService changes = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "audio-matrix-pipewire-events"));
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final AtomicBoolean calibrationCaptureActive = new AtomicBoolean();
    private final AtomicBoolean calibrationCaptureAborted = new AtomicBoolean();
    private final AtomicLong connectionGeneration = new AtomicLong();
    private final AtomicReference<Process> calibrationRecorder = new AtomicReference<>();
    private final AtomicReference<Process> calibrationPlayback = new AtomicReference<>();
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

    public CompletableFuture<Void> setVolume(int nodeId, double volume) {
        return execute("wpctl", "set-volume", Integer.toString(nodeId), Double.toString(clamp(volume, 0, 1)));
    }
    public CompletableFuture<Void> setMute(int nodeId, boolean muted) {
        return execute("wpctl", "set-mute", Integer.toString(nodeId), muted ? "1" : "0");
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
        return CompletableFuture.runAsync(() -> {
            Path wav = null;
            try {
                if (!executable("pw-play")) throw new IOException("pw-play is unavailable");
                final double safeFrequency = clamp(frequencyHz, 20, 1000);
                final int safeDuration = (int) clamp(durationMs, 100, 3000);
                wav = Files.createTempFile("audio-matrix-tone-", ".wav");
                writeTone(wav, safeFrequency, safeDuration, 0.04);
                run(List.of("pw-play", "--target", sink.name(), "--volume", "0.15", wav.toString()), Duration.ofSeconds(5));
            } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
            finally { if (wav != null) try { Files.deleteIfExists(wav); } catch (IOException ignored) {} }
        }, io);
    }

    public CompletableFuture<Void> playSteppedSweep(AudioNode sink, double startHz, double endHz, int steps) {
        return CompletableFuture.runAsync(() -> {
            Path wav = null;
            try {
                if (!executable("pw-play")) throw new IOException("pw-play is unavailable");
                wav = Files.createTempFile("audio-matrix-sweep-", ".wav");
                CalibrationSignal.writeSteppedSine(wav, startHz, endHz, steps, 200);
                run(List.of("pw-play", "--target", sink.name(), "--volume", "0.10", wav.toString()), Duration.ofSeconds(8));
            } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
            finally { if (wav != null) try { Files.deleteIfExists(wav); } catch (IOException ignored) {} }
        }, io);
    }

    public CompletableFuture<Void> playManualImpulse(AudioNode sink) {
        return CompletableFuture.runAsync(() -> {
            Path wav = null;
            try {
                if (!executable("pw-play")) throw new IOException("pw-play is unavailable");
                wav = Files.createTempFile("audio-matrix-impulse-", ".wav");
                CalibrationSignal.writeManualImpulse(wav);
                run(PipeWireSweepCommands.playManualImpulse(sink, wav), Duration.ofSeconds(2));
            } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
            finally { if (wav != null) try { Files.deleteIfExists(wav); } catch (IOException ignored) {} }
        }, io);
    }

    public CompletableFuture<SweepCapture> captureSteppedSweep(AudioNode source, AudioNode sink,
                                                                 double startHz, double endHz, int steps) {
        if (closed.get())
            return CompletableFuture.failedFuture(new IOException("Calibration capture is stopping"));
        final List<String> recorderCommand;
        final List<String> playerCommand;
        try {
            recorderCommand = PipeWireSweepCommands.record(source, Path.of("capture.wav"));
            playerCommand = PipeWireSweepCommands.play(sink, Path.of("sweep.wav"));
            CalibrationSignal.validate(startHz, endHz, steps, 200);
        } catch (Exception invalid) { return CompletableFuture.failedFuture(invalid); }
        if (!executable("pw-record") || !executable("pw-play"))
            return CompletableFuture.failedFuture(new IOException("pw-record and pw-play are required for synchronized sensor capture"));
        if (!calibrationCaptureActive.compareAndSet(false, true))
            return CompletableFuture.failedFuture(new IllegalStateException("A calibration capture is already running"));
        calibrationCaptureAborted.set(false);
        if (closed.get()) {
            calibrationCaptureAborted.set(true);
            calibrationCaptureActive.set(false);
            return CompletableFuture.failedFuture(new IOException("Calibration capture is stopping"));
        }
        return CompletableFuture.supplyAsync(() -> {
            Path directory = null;
            Process recorder = null;
            Process player = null;
            boolean keepRecording = false;
            try {
                if (calibrationCaptureAborted.get() || closed.get()) throw new IOException("Calibration capture was cancelled");
                directory = Files.createTempDirectory("audio-matrix-calibration-");
                Path recording = directory.resolve("sensor.wav");
                Path sweep = directory.resolve("sweep.wav");
                CalibrationSignal.writeSteppedSine(sweep, startHz, endHz, steps, 200);
                List<String> recorderArgs = new ArrayList<>(recorderCommand);
                recorderArgs.set(recorderArgs.size() - 1, recording.toString());
                List<String> playerArgs = new ArrayList<>(playerCommand);
                playerArgs.set(playerArgs.size() - 1, sweep.toString());

                recorder = new ProcessBuilder(recorderArgs).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!calibrationRecorder.compareAndSet(null, recorder)) throw new IOException("A calibration recorder is already active");
                if (recorder.waitFor(100, TimeUnit.MILLISECONDS)) throw new IOException("pw-record exited before capture began");
                Thread.sleep(400);
                if (calibrationCaptureAborted.get() || !recorder.isAlive()) throw new IOException("Sensor recording stopped before the sweep started");

                player = new ProcessBuilder(playerArgs).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!calibrationPlayback.compareAndSet(null, player)) throw new IOException("A calibration sweep is already playing");
                long timeoutMs = Math.multiplyExact((long) steps, 200L) + 5_000L;
                if (!player.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) throw new IOException("Calibration sweep playback timed out");
                if (player.exitValue() != 0) throw new IOException("pw-play could not play the sweep to the selected output");
                if (calibrationCaptureAborted.get() || !recorder.isAlive()) throw new IOException("Sensor recording stopped during the sweep");
                Thread.sleep(100);
                stopCalibrationProcess(recorder);
                if (!Files.isRegularFile(recording) || Files.size(recording) <= 44 || Files.size(recording) > 64L * 1024 * 1024)
                    throw new IOException("Sensor recording is missing, empty, or exceeds the analysis limit");
                CalibrationMeasurementAnalyzer.readPcm16Wav(recording, 0);
                keepRecording = true;
                return new SweepCapture(directory, recording, CALIBRATION_SWEEP_LEAD_MS);
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            } finally {
                stopCalibrationProcess(player);
                stopCalibrationProcess(recorder);
                if (player != null) calibrationPlayback.compareAndSet(player, null);
                if (recorder != null) calibrationRecorder.compareAndSet(recorder, null);
                calibrationCaptureActive.set(false);
                if (!keepRecording && directory != null) deleteTemporaryDirectory(directory);
            }
        }, io);
    }

    public void cancelCalibrationCapture() {
        calibrationCaptureAborted.set(true);
        stopCalibrationCaptureProcesses();
    }

    public void stopCalibrationCapture() {
        calibrationCaptureAborted.set(true);
        stopCalibrationCaptureProcesses();
    }

    private void stopCalibrationCaptureProcesses() {
        stopCalibrationProcess(calibrationPlayback.get());
        stopCalibrationProcess(calibrationRecorder.get());
    }

    private static void stopCalibrationProcess(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void deleteTemporaryDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) {}
    }

    public record SweepCapture(Path temporaryDirectory, Path recording, int sweepOffsetMs) {}

    private Snapshot readSnapshot() throws Exception {
        if (!executable("pw-dump") || !executable("wpctl")) throw new IOException("pw-dump or wpctl is not installed");
        String output = run(List.of("pw-dump", "-N"), Duration.ofSeconds(6));
        String controlStatus = run(List.of("wpctl", "status"), Duration.ofSeconds(3));
        Map<Integer, LevelState> levels = levelStates(controlStatus);
        JsonNode root = json.readTree(output);
        if (!root.isArray()) throw new IOException("pw-dump returned an unexpected document");
        Map<Integer, JsonNode> clients = clientProperties(root);
        List<AudioNode> sinks = new ArrayList<>(), sources = new ArrayList<>(), streams = new ArrayList<>();
        for (JsonNode object : root) {
            JsonNode info = object.path("info");
            JsonNode props = info.path("props");
            String mediaClass = props.path("media.class").asText("");
            String name = props.path("node.name").asText("");
            if (!isSnapshotAudioNode(mediaClass, name)) continue;
            int id = object.path("id").asInt(-1);
            String description = props.path("node.description").asText(name);
            String state = info.path("state").asText("unknown").toLowerCase();
            ApplicationIdentity identity = applicationIdentity(props, clients);
            String app = identity.applicationName();
            String process = identity.processName();
            String appId = identity.applicationId();
            int channels = props.path("audio.channels").asInt(2);
            List<Integer> rates = sampleRates(info.path("params").path("EnumFormat"));
            Integer pulseId = props.path("pulse.id").isInt() ? props.path("pulse.id").asInt() : null;
            JsonNode stateProps = activeProps(info.path("params").path("Props"));
            LevelState level = levels.get(id);
            double volume = level == null ? Double.NaN : level.volume();
            boolean muted = level != null ? level.muted() : stateProps.path("mute").asBoolean(false);
            AudioNode node = new AudioNode(id, name, description, mediaClass, state, app, process, appId, channels, rates, volume, muted, pulseId);
            if (mediaClass.startsWith("Stream/Output/Audio")) streams.add(node);
            else if (mediaClass.startsWith("Audio/Sink")) sinks.add(node);
            else if (mediaClass.startsWith("Audio/Source")) sources.add(node);
        }
        Map<String, AudioNode> recent = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        List<AudioNode> previous = new ArrayList<>(last.streams());
        previous.addAll(last.recentlyEnded());
        for (AudioNode old : previous) if (streams.stream().noneMatch(n -> n.name().equals(old.name()))) recent.put(old.name(), old);
        recent.values().removeIf(node -> now - last.streamLastSeen().getOrDefault(node.name(), now) > 30_000);
        Map<String, Long> seen = new HashMap<>(last.streamLastSeen());
        streams.forEach(n -> seen.put(n.name(), now));
        seen.entrySet().removeIf(e -> now - e.getValue() > 30_000);
        int defaultSinkId = defaultSinkId(controlStatus);
        return new Snapshot(true, "", List.copyOf(sinks), List.copyOf(sources), List.copyOf(streams),
                List.copyOf(recent.values()), Collections.unmodifiableMap(seen), defaultSinkId, System.currentTimeMillis());
    }

    static Map<Integer, JsonNode> clientProperties(JsonNode root) {
        Map<Integer, JsonNode> result = new HashMap<>();
        if (root == null || !root.isArray()) return result;
        for (JsonNode object : root) {
            if (!"PipeWire:Interface:Client".equals(object.path("type").asText())) continue;
            int id = object.path("id").asInt(-1);
            JsonNode properties = object.path("info").path("props");
            if (id >= 0 && properties.isObject()) result.put(id, properties);
        }
        return result;
    }

    static ApplicationIdentity applicationIdentity(JsonNode nodeProperties, Map<Integer, JsonNode> clients) {
        int clientId = nodeProperties.path("client.id").asInt(-1);
        JsonNode clientProperties = clients.get(clientId);
        return new ApplicationIdentity(
                property(nodeProperties, clientProperties, "application.name"),
                property(nodeProperties, clientProperties, "application.process.binary"),
                property(nodeProperties, clientProperties, "application.id"));
    }

    private static String property(JsonNode nodeProperties, JsonNode clientProperties, String name) {
        String value = nodeProperties.path(name).asText("");
        if (!value.isBlank()) return value;
        return clientProperties == null ? "" : clientProperties.path(name).asText("");
    }

    record ApplicationIdentity(String applicationName, String processName, String applicationId) {}

    static boolean isSnapshotAudioNode(String mediaClass, String name) {
        if (mediaClass == null || name == null || name.isBlank()) return false;
        if (!(mediaClass.startsWith("Audio/Sink") || mediaClass.startsWith("Audio/Source")
                || mediaClass.startsWith("Stream/Output/Audio"))) return false;
        return !name.startsWith("audiomatrix_") || SYSTEM_MIXER_SINK_NAME.equals(name);
    }

    static int defaultSinkId(String status) {
        boolean inSinks = false;
        java.util.regex.Pattern idPattern = java.util.regex.Pattern.compile("\\b(\\d+)\\.");
        for (String line : status.split("\\R")) {
            if (line.contains("Sinks:")) { inSinks = true; continue; }
            if (inSinks && (line.contains("Sources:") || line.contains("Filters:") || line.contains("Streams:") || line.contains("Video:") || line.contains("Settings:"))) break;
            if (!inSinks) continue;
            java.util.regex.Matcher id = idPattern.matcher(line);
            if (id.find() && line.substring(0, id.start()).contains("*")) return Integer.parseInt(id.group(1));
        }
        return -1;
    }

    static Map<Integer, LevelState> levelStates(String status) {
        Map<Integer, LevelState> result = new HashMap<>();
        java.util.regex.Pattern idPattern = java.util.regex.Pattern.compile("\\b(\\d+)\\.");
        java.util.regex.Pattern volumePattern = java.util.regex.Pattern.compile("\\[vol:\\s*([0-9.]+)([^\\]]*)\\]", java.util.regex.Pattern.CASE_INSENSITIVE);
        for (String line : status.split("\\R")) {
            java.util.regex.Matcher id = idPattern.matcher(line); java.util.regex.Matcher volumeText = volumePattern.matcher(line);
            if (!id.find() || !volumeText.find()) continue;
            int nodeId = Integer.parseInt(id.group(1));
            double volume = clamp(Double.parseDouble(volumeText.group(1)), 0, 1.5);
            boolean muted = volumeText.group(2).toLowerCase(java.util.Locale.ROOT).contains("muted");
            result.put(nodeId, new LevelState(volume, muted));
        }
        return result;
    }
    record LevelState(double volume, boolean muted) {}

    private double actualVolume(int id) {
        try {
            String text = run(List.of("wpctl", "get-volume", Integer.toString(id)), Duration.ofSeconds(2));
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("Volume:\\s*([0-9.]+)").matcher(text);
            return matcher.find() ? clamp(Double.parseDouble(matcher.group(1)), 0, 1.5) : Double.NaN;
        } catch (Exception ignored) { return Double.NaN; }
    }
    private boolean actualMute(int id, JsonNode props) {
        try {
            String text = run(List.of("wpctl", "get-volume", Integer.toString(id)), Duration.ofSeconds(2));
            return text.toLowerCase().contains("muted");
        } catch (Exception ignored) {
            JsonNode first = props.isArray() && !props.isEmpty() ? props.get(0) : null;
            return first != null && first.path("mute").asBoolean(false);
        }
    }
    private static JsonNode activeProps(JsonNode props) {
        if (!props.isArray()) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        for (JsonNode item : props) if (item.has("volume") || item.has("mute")) return item;
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
    private static List<Integer> sampleRates(JsonNode formats) {
        List<Integer> result = new ArrayList<>();
        if (formats.isArray()) for (JsonNode format : formats) {
            JsonNode rate = format.path("rate");
            if (rate.isInt()) result.add(rate.asInt());
            else if (rate.isObject()) {
                for (String key : List.of("default", "min", "max")) if (rate.path(key).isInt()) result.add(rate.path(key).asInt());
            }
        }
        return result.stream().distinct().sorted().toList();
    }

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
    private static String run(List<String> command, Duration timeout) throws Exception {
        Path output = Files.createTempFile("audio-matrix-command-", ".out");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly(); throw new IOException("Command timed out: " + command.getFirst());
            }
            String text = Files.readString(output);
            if (process.exitValue() != 0) throw new IOException(text.isBlank() ? command.getFirst() + " exited with " + process.exitValue() : text.trim());
            return text;
        } finally { Files.deleteIfExists(output); }
    }
    private static boolean executable(String name) {
        String path = System.getenv().getOrDefault("PATH", "");
        for (String dir : path.split(java.io.File.pathSeparator)) if (Files.isExecutable(Path.of(dir, name))) return true;
        return false;
    }
    private static String safeMessage(Throwable e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    private static void writeTone(Path path, double frequency, int durationMs, double amplitude) throws IOException {
        int frames = SAMPLE_RATE * durationMs / 1000;
        byte[] pcm = new byte[frames * 2 * 2];
        ByteBuffer buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            double edge = Math.min(1.0, Math.min(i / 240.0, (frames - i) / 480.0));
            short value = (short) (Math.sin(2.0 * Math.PI * frequency * i / SAMPLE_RATE) * amplitude * edge * Short.MAX_VALUE);
            buffer.putShort(value).putShort(value);
        }
        int dataLength = pcm.length, riffLength = 36 + dataLength;
        ByteBuffer wav = ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(riffLength).put(new byte[]{'W','A','V','E'});
        wav.put(new byte[]{'f','m','t',' '}).putInt(16).putShort((short)1).putShort((short)2).putInt(SAMPLE_RATE);
        wav.putInt(SAMPLE_RATE * 4).putShort((short)4).putShort((short)16);
        wav.put(new byte[]{'d','a','t','a'}).putInt(dataLength).put(pcm);
        Files.write(path, wav.array());
    }

    @Override public void close() {
        closed.set(true);
        cancelCalibrationCapture();
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
