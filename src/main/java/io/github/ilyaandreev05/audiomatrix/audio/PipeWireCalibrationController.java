package io.github.ilyaandreev05.audiomatrix.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Owns calibration capture and explicit preview playback processes. */
final class PipeWireCalibrationController {
    private static final int SAMPLE_RATE = 48_000;
    private static final int SWEEP_LEAD_MS = 500;
    private final Executor io;
    private final AtomicBoolean captureActive = new AtomicBoolean();
    private final AtomicBoolean captureAborted = new AtomicBoolean();
    private final AtomicReference<Process> recorder = new AtomicReference<>();
    private final AtomicReference<Process> playback = new AtomicReference<>();
    private volatile boolean closed;

    PipeWireCalibrationController(Executor io) { this.io = io; }

    CompletableFuture<Void> playTestTone(AudioNode sink, double frequencyHz, int durationMs) {
        return playGenerated(sink, "audio-matrix-tone-", Duration.ofSeconds(5), path ->
                writeTone(path, PipeWireBackend.clamp(frequencyHz, 20, 1000),
                        (int) PipeWireBackend.clamp(durationMs, 100, 3000), 0.04),
                (target, path) -> List.of("pw-play", "--target", target.name(), "--volume", "0.15", path.toString()));
    }

    CompletableFuture<Void> playSteppedSweep(AudioNode sink, double startHz, double endHz, int steps) {
        return playGenerated(sink, "audio-matrix-sweep-", Duration.ofSeconds(8), path ->
                CalibrationSignal.writeSteppedSine(path, startHz, endHz, steps, 200),
                (target, path) -> List.of("pw-play", "--target", target.name(), "--volume", "0.10", path.toString()));
    }

    CompletableFuture<Void> playManualImpulse(AudioNode sink) {
        return playGenerated(sink, "audio-matrix-impulse-", Duration.ofSeconds(2),
                CalibrationSignal::writeManualImpulse, PipeWireSweepCommands::playManualImpulse);
    }

    private CompletableFuture<Void> playGenerated(AudioNode sink, String prefix, Duration timeout,
                                                   FileWriter writer, PlaybackCommand commandBuilder) {
        return CompletableFuture.runAsync(() -> {
            Path wav = null;
            try {
                if (closed) throw new IOException("Calibration playback is stopping");
                if (!PipeWireBackend.executable("pw-play")) throw new IOException("pw-play is unavailable");
                wav = Files.createTempFile(prefix, ".wav");
                writer.write(wav);
                PipeWireBackend.run(commandBuilder.build(sink, wav), timeout);
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            } finally {
                deleteFile(wav);
            }
        }, io);
    }

    CompletableFuture<PipeWireBackend.SweepCapture> captureSteppedSweep(AudioNode source, AudioNode sink,
                                                                          double startHz, double endHz, int steps) {
        if (closed) return failed("Calibration capture is stopping");
        List<String> recorderCommand, playerCommand;
        try {
            recorderCommand = PipeWireSweepCommands.record(source, Path.of("capture.wav"));
            playerCommand = PipeWireSweepCommands.play(sink, Path.of("sweep.wav"));
            CalibrationSignal.validate(startHz, endHz, steps, 200);
        } catch (Exception error) { return CompletableFuture.failedFuture(error); }
        if (!PipeWireBackend.executable("pw-record") || !PipeWireBackend.executable("pw-play"))
            return failed("pw-record and pw-play are required for synchronized sensor capture");
        if (!captureActive.compareAndSet(false, true))
            return CompletableFuture.failedFuture(new IllegalStateException("A calibration capture is already running"));
        captureAborted.set(false);
        if (closed) return finishWhileStopping();
        return CompletableFuture.supplyAsync(() -> capture(recorderCommand, playerCommand, startHz, endHz, steps), io);
    }

    private CompletableFuture<PipeWireBackend.SweepCapture> failed(String message) {
        return CompletableFuture.failedFuture(new IOException(message));
    }

    private CompletableFuture<PipeWireBackend.SweepCapture> finishWhileStopping() {
        captureAborted.set(true);
        captureActive.set(false);
        return failed("Calibration capture is stopping");
    }

    private PipeWireBackend.SweepCapture capture(List<String> recorderCommand, List<String> playerCommand,
                                                  double startHz, double endHz, int steps) {
        Path directory = null;
        Process activeRecorder = null, activePlayback = null;
        boolean keepRecording = false;
        try {
            if (captureAborted.get() || closed) throw new IOException("Calibration capture was cancelled");
            directory = Files.createTempDirectory("audio-matrix-calibration-");
            Path recording = directory.resolve("sensor.wav"), sweep = directory.resolve("sweep.wav");
            CalibrationSignal.writeSteppedSine(sweep, startHz, endHz, steps, 200);
            activeRecorder = startRecorder(recorderCommand, recording);
            Thread.sleep(400);
            if (captureAborted.get() || !activeRecorder.isAlive())
                throw new IOException("Sensor recording stopped before the sweep started");
            activePlayback = startPlayback(playerCommand, sweep);
            long timeoutMs = Math.multiplyExact((long) steps, 200L) + 5_000L;
            if (!activePlayback.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) throw new IOException("Calibration sweep playback timed out");
            if (activePlayback.exitValue() != 0) throw new IOException("pw-play could not play the sweep to the selected output");
            if (captureAborted.get() || !activeRecorder.isAlive()) throw new IOException("Sensor recording stopped during the sweep");
            Thread.sleep(100);
            stopProcess(activeRecorder);
            verifyRecording(recording);
            keepRecording = true;
            return new PipeWireBackend.SweepCapture(directory, recording, SWEEP_LEAD_MS);
        } catch (Exception failure) {
            throw new java.util.concurrent.CompletionException(failure);
        } finally {
            stopProcess(activePlayback);
            stopProcess(activeRecorder);
            if (activePlayback != null) playback.compareAndSet(activePlayback, null);
            if (activeRecorder != null) recorder.compareAndSet(activeRecorder, null);
            captureActive.set(false);
            if (!keepRecording && directory != null) deleteTree(directory);
        }
    }

    private Process startRecorder(List<String> command, Path recording) throws Exception {
        List<String> args = withLastPath(command, recording);
        Process process = new ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!recorder.compareAndSet(null, process)) throw new IOException("A calibration recorder is already active");
        if (process.waitFor(100, TimeUnit.MILLISECONDS)) throw new IOException("pw-record exited before capture began");
        return process;
    }

    private Process startPlayback(List<String> command, Path sweep) throws Exception {
        Process process = new ProcessBuilder(withLastPath(command, sweep)).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!playback.compareAndSet(null, process)) throw new IOException("A calibration sweep is already playing");
        return process;
    }

    private static List<String> withLastPath(List<String> command, Path path) {
        List<String> args = new ArrayList<>(command);
        args.set(args.size() - 1, path.toString());
        return args;
    }

    private static void verifyRecording(Path recording) throws IOException {
        if (!Files.isRegularFile(recording) || Files.size(recording) <= 44 || Files.size(recording) > 64L * 1024 * 1024)
            throw new IOException("Sensor recording is missing, empty, or exceeds the analysis limit");
        CalibrationMeasurementAnalyzer.readPcm16Wav(recording, 0);
    }

    void cancel() {
        captureAborted.set(true);
        stopProcess(playback.get());
        stopProcess(recorder.get());
    }

    void close() { closed = true; cancel(); }

    private static void stopProcess(Process process) {
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

    private static void writeTone(Path path, double frequency, int durationMs, double amplitude) throws IOException {
        int frames = SAMPLE_RATE * durationMs / 1000;
        byte[] pcm = new byte[frames * 4];
        ByteBuffer buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            double edge = Math.min(1.0, Math.min(i / 240.0, (frames - i) / 480.0));
            short value = (short) (Math.sin(2.0 * Math.PI * frequency * i / SAMPLE_RATE)
                    * amplitude * edge * Short.MAX_VALUE);
            buffer.putShort(value).putShort(value);
        }
        writeWave(path, pcm, frames);
    }

    private static void writeWave(Path path, byte[] pcm, int frames) throws IOException {
        int dataLength = pcm.length;
        ByteBuffer wav = ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36 + dataLength).put(new byte[]{'W','A','V','E'});
        wav.put(new byte[]{'f','m','t',' '}).putInt(16).putShort((short)1).putShort((short)2).putInt(SAMPLE_RATE);
        wav.putInt(SAMPLE_RATE * 4).putShort((short)4).putShort((short)16);
        wav.put(new byte[]{'d','a','t','a'}).putInt(dataLength).put(pcm);
        Files.write(path, wav.array());
    }

    private static void deleteFile(Path path) {
        if (path != null) try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private static void deleteTree(Path directory) {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { }
    }

    @FunctionalInterface
    private interface FileWriter { void write(Path path) throws Exception; }

    @FunctionalInterface
    private interface PlaybackCommand { List<String> build(AudioNode sink, Path wav) throws IOException; }
}
