package io.github.ilyaandreev05.audiomatrix.audio;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Keeps a linear-scale WirePlumber Mixer API connection for addressed runtime controls. */
final class WirePlumberMixerControl {
    private static final Object LOCK = new Object();
    private static final ExecutorService READERS = Executors.newVirtualThreadPerTaskExecutor();
    private static Process helper;
    private static BufferedWriter commands;
    private static BufferedReader results;
    private static Path executable;
    private static final VolumeWriteQueue VOLUME_WRITES =
            new VolumeWriteQueue(WirePlumberMixerControl::writeVolume);

    static {
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("audio-matrix-mixer-shutdown")
                .unstarted(WirePlumberMixerControl::stop));
    }

    private WirePlumberMixerControl() {}

    @FunctionalInterface
    interface VolumeWriter {
        void write(int nodeId, double level) throws Exception;
    }

    static final class VolumeWriteQueue {
        private final VolumeWriter writer;
        private final Object statesLock = new Object();
        private final Map<Integer, VolumeState> states = new HashMap<>();

        VolumeWriteQueue(VolumeWriter writer) {
            this.writer = writer;
        }

        void setVolume(int nodeId, double level) throws Exception {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            VolumeState state;
            boolean drain;
            synchronized (statesLock) {
                state = states.computeIfAbsent(nodeId, ignored -> new VolumeState());
                synchronized (state) {
                    state.pendingLevel = level;
                    state.pendingWrites.add(completion);
                    drain = !state.draining;
                    if (drain) {
                        state.scheduled = false;
                        state.scheduleToken++;
                        state.draining = true;
                    }
                }
            }
            if (drain) drain(nodeId, state);
            awaitCompletion(completion);
        }

        CompletableFuture<Void> setVolumeAsync(int nodeId, double level, ExecutorService executor) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            VolumeState state;
            boolean schedule = false;
            long token = 0;
            synchronized (statesLock) {
                state = states.computeIfAbsent(nodeId, ignored -> new VolumeState());
                synchronized (state) {
                    state.pendingLevel = level;
                    state.pendingWrites.add(completion);
                    if (!state.draining && !state.scheduled) {
                        state.scheduled = true;
                        token = ++state.scheduleToken;
                        schedule = true;
                    }
                }
            }
            if (schedule) {
                long scheduledToken = token;
                try {
                    executor.execute(() -> startScheduledDrain(nodeId, state, scheduledToken));
                } catch (RuntimeException rejection) {
                    if (failScheduledWrites(nodeId, state, scheduledToken, rejection)) throw rejection;
                }
            }
            return completion;
        }

        private void startScheduledDrain(int nodeId, VolumeState state, long token) {
            synchronized (state) {
                if (!state.scheduled || state.scheduleToken != token || state.draining) return;
                state.scheduled = false;
                state.draining = true;
            }
            drain(nodeId, state);
        }

        private void drain(int nodeId, VolumeState state) {
            Error fatalError = null;
            while (true) {
                double level;
                List<CompletableFuture<Void>> completions;
                synchronized (state) {
                    if (state.pendingWrites.isEmpty()) {
                        state.draining = false;
                        break;
                    }
                    level = state.pendingLevel;
                    completions = state.pendingWrites;
                    state.pendingWrites = new ArrayList<>();
                }

                Throwable failure = null;
                try {
                    writer.write(nodeId, level);
                } catch (Throwable error) {
                    failure = error;
                    if (error instanceof Error errorValue && fatalError == null) fatalError = errorValue;
                }
                for (CompletableFuture<Void> completion : completions) {
                    if (failure == null) completion.complete(null);
                    else completion.completeExceptionally(failure);
                }
            }
            removeIfIdle(nodeId, state);
            if (fatalError != null) throw fatalError;
        }

        private boolean failScheduledWrites(int nodeId, VolumeState state, long token, RuntimeException failure) {
            List<CompletableFuture<Void>> completions;
            synchronized (state) {
                if (!state.scheduled || state.scheduleToken != token) return false;
                state.scheduled = false;
                completions = state.pendingWrites;
                state.pendingWrites = new ArrayList<>();
            }
            for (CompletableFuture<Void> completion : completions) completion.completeExceptionally(failure);
            removeIfIdle(nodeId, state);
            return true;
        }

        private void removeIfIdle(int nodeId, VolumeState state) {
            synchronized (statesLock) {
                synchronized (state) {
                    if (!state.draining && !state.scheduled && state.pendingWrites.isEmpty()) {
                        states.remove(nodeId, state);
                    }
                }
            }
        }

        private static void awaitCompletion(CompletableFuture<Void> completion) throws Exception {
            try {
                completion.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof Exception exception) throw exception;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(cause);
            }
        }

        private static final class VolumeState {
            private double pendingLevel;
            private List<CompletableFuture<Void>> pendingWrites = new ArrayList<>();
            private boolean draining;
            private boolean scheduled;
            private long scheduleToken;
        }
    }

    static void setVolume(int nodeId, double gainLinear) throws Exception {
        VOLUME_WRITES.setVolume(nodeId, gainLinear);
    }

    private static void writeVolume(int nodeId, double gainLinear) throws Exception {
        double level = Math.max(0, Math.min(1, gainLinear));
        try {
            send("V " + nodeId + " " + String.format(Locale.ROOT, "%.9f", level));
        } catch (IOException helperError) {
            try {
                PipeWireCommandRunner.run(java.util.List.of("wpctl", "set-volume", Integer.toString(nodeId),
                        Double.toString(wpctlCubicValue(level))), Duration.ofSeconds(3));
            } catch (Exception fallbackError) {
                fallbackError.addSuppressed(helperError);
                throw fallbackError;
            }
        }
    }

    static void setMuted(int nodeId, boolean muted) throws Exception {
        setMuted(nodeId, muted, -1.0);
    }

    static void setMuted(int nodeId, boolean muted, double savedLinearLevel) throws Exception {
        String saved = Double.isFinite(savedLinearLevel) && savedLinearLevel >= 0 && savedLinearLevel <= 1
                ? String.format(Locale.ROOT, " %.9f", savedLinearLevel) : "";
        try {
            send("M " + nodeId + " " + (muted ? 1 : 0) + saved);
        } catch (IOException helperError) {
            try {
                PipeWireCommandRunner.run(java.util.List.of("wpctl", "set-mute", Integer.toString(nodeId),
                        muted ? "1" : "0"), Duration.ofSeconds(3));
            } catch (Exception fallbackError) {
                fallbackError.addSuppressed(helperError);
                throw fallbackError;
            }
        }
    }

    static CompletableFuture<Void> setVolumeAsync(int nodeId, double level, ExecutorService executor) {
        return VOLUME_WRITES.setVolumeAsync(nodeId, level, executor);
    }

    static CompletableFuture<Void> setMutedAsync(int nodeId, boolean muted, ExecutorService executor) {
        return CompletableFuture.runAsync(() -> runMute(nodeId, muted), executor);
    }

    static CompletableFuture<Void> setMutedAsync(int nodeId, boolean muted, double savedLevel,
                                                  ExecutorService executor) {
        return CompletableFuture.runAsync(() -> {
            try { setMuted(nodeId, muted, savedLevel); }
            catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        }, executor);
    }


    private static void runMute(int nodeId, boolean muted) {
        try { setMuted(nodeId, muted); }
        catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
    }

    static double wpctlCubicValue(double gainLinear) {
        return Math.cbrt(Math.max(0, Math.min(1, gainLinear)));
    }

    static double linearFromWpctlCubic(double wpctlValue) {
        double clamped = Math.max(0, Math.min(1, wpctlValue));
        return clamped * clamped * clamped;
    }

    private static void send(String command) throws IOException {
        synchronized (LOCK) {
            try {
                ensureStarted();
                commands.write(command);
                commands.newLine();
                commands.flush();
                String result = await(READERS.submit(results::readLine), 3);
                if (!"OK".equals(result)) throw new IOException(result == null ? "Mixer helper stopped" : result);
            } catch (Exception failure) {
                stopLocked();
                if (failure instanceof IOException io) throw io;
                throw new IOException("WirePlumber mixer command failed", failure);
            }
        }
    }

    private static void ensureStarted() throws IOException {
        if (helper != null && helper.isAlive()) return;
        InputStream resource = WirePlumberMixerControl.class.getResourceAsStream("/native/audiomatrix_mixer_helper");
        if (resource == null) throw new IOException("WirePlumber Mixer API helper is not packaged");
        try (resource) {
            executable = Files.createTempFile("audio-matrix-mixer-", "");
            Files.copy(resource, executable, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            executable.toFile().setExecutable(true, true);
            helper = new ProcessBuilder(executable.toString()).start();
            commands = new BufferedWriter(new OutputStreamWriter(helper.getOutputStream(), StandardCharsets.UTF_8));
            results = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8));
            Process started = helper;
            Thread.ofVirtual().name("audio-matrix-mixer-stderr").start(() -> {
                try (BufferedReader errors = new BufferedReader(new InputStreamReader(started.getErrorStream(), StandardCharsets.UTF_8))) {
                    while (errors.readLine() != null) { /* Drain diagnostics so the native process cannot block. */ }
                } catch (IOException ignored) { }
            });
            String ready = await(READERS.submit(results::readLine), 4);
            if (!"READY".equals(ready)) throw new IOException(ready == null ? "Mixer helper did not start" : ready);
        } catch (IOException failure) {
            stopLocked();
            throw failure;
        } catch (Exception failure) {
            stopLocked();
            throw new IOException("Could not start WirePlumber Mixer API helper", failure);
        }
    }

    private static String await(Future<String> future, int seconds) throws Exception {
        try { return future.get(seconds, TimeUnit.SECONDS); }
        catch (ExecutionException failure) { throw new IOException("Mixer helper response failed", failure.getCause()); }
        catch (TimeoutException failure) { future.cancel(true); throw new IOException("Mixer helper response timed out", failure); }
    }

    private static void stop() {
        synchronized (LOCK) { stopLocked(); }
    }

    private static void stopLocked() {
        if (commands != null) try { commands.write("Q"); commands.newLine(); commands.flush(); } catch (IOException ignored) { }
        if (helper != null && helper.isAlive()) {
            try {
                if (!helper.waitFor(250, TimeUnit.MILLISECONDS)) helper.destroyForcibly();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                helper.destroyForcibly();
            }
        }
        try { if (commands != null) commands.close(); } catch (IOException ignored) { }
        try { if (results != null) results.close(); } catch (IOException ignored) { }
        if (executable != null) try { Files.deleteIfExists(executable); } catch (IOException ignored) { }
        helper = null;
        commands = null;
        results = null;
        executable = null;
    }
}
