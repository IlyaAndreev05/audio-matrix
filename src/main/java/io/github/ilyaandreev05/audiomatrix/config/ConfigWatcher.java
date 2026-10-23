package io.github.ilyaandreev05.audiomatrix.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owns one configuration file subscription to the shared filesystem watcher. */
final class ConfigWatcher implements AutoCloseable {
    @FunctionalInterface interface DiskCheck { void inspect(DiskState state) throws IOException; }
    @FunctionalInterface interface Reload { void reload() throws IOException, ConfigStore.ConfigValidationException; }
    static final class DiskState {
        private boolean matches;
        private String fingerprint;
        void update(boolean matches, String fingerprint) {
            this.matches = matches;
            this.fingerprint = fingerprint;
        }
        boolean matches() { return matches; }
        String fingerprint() { return fingerprint; }
    }

    private final Path file;
    private final BooleanSupplier storeClosed;
    private final DiskCheck diskCheck;
    private final Reload reload;
    private final Consumer<Exception> rejected;
    private final Consumer<IOException> stopped;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean pendingChange = new AtomicBoolean();
    private final AtomicBoolean processing = new AtomicBoolean();
    private final AtomicBoolean pollFailureReported = new AtomicBoolean();
    private final DiskState reloadDiskState = new DiskState();
    private final DiskState pollDiskState = new DiskState();
    private String lastPolledFingerprint;

    private ConfigWatcher(Path file, BooleanSupplier storeClosed, DiskCheck diskCheck, Reload reload,
                          Consumer<Exception> rejected, Consumer<IOException> stopped,
                          ExecutorService executor) {
        this.file = file;
        this.storeClosed = storeClosed;
        this.diskCheck = diskCheck;
        this.reload = reload;
        this.rejected = rejected;
        this.stopped = stopped;
        this.executor = executor;
    }

    static ConfigWatcher start(Path file, BooleanSupplier storeClosed, DiskCheck diskCheck, Reload reload,
                               Consumer<Exception> rejected, Consumer<IOException> stopped) throws IOException {
        Path directory = file.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IOException("Configuration watch directory is unavailable: " + directory);
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "audio-matrix-config-watch");
            thread.setDaemon(true);
            return thread;
        });
        ConfigWatcher watcher = new ConfigWatcher(file, storeClosed, diskCheck, reload, rejected, stopped, executor);
        try {
            ConfigWatchRegistry.instance().register(file, watcher);
            return watcher;
        } catch (IOException | RuntimeException | Error failure) {
            watcher.close();
            throw failure;
        }
    }

    void signalChanged() {
        if (closed.get() || storeClosed.getAsBoolean()) return;
        pendingChange.set(true);
        schedule();
    }

    void reportStopped(IOException failure) {
        if (!closed.get() && !storeClosed.getAsBoolean()) stopped.accept(failure);
    }
    synchronized void pollForExternalChange() {
        if (closed.get() || storeClosed.getAsBoolean()) return;
        try {
            diskCheck.inspect(pollDiskState);
            pollFailureReported.set(false);
            if (pollDiskState.matches()) {
                lastPolledFingerprint = null;
                return;
            }
            String fingerprint = pollDiskState.fingerprint();
            if (!fingerprint.equals(lastPolledFingerprint)) {
                lastPolledFingerprint = fingerprint;
                signalChanged();
            }
        } catch (IOException error) {
            if (pollFailureReported.compareAndSet(false, true)) reportStopped(error);
        } catch (RuntimeException error) {
            if (pollFailureReported.compareAndSet(false, true)) {
                reportStopped(new IOException("Could not inspect configuration file " + file, error));
            }
        }
    }

    private void schedule() {
        if (!processing.compareAndSet(false, true)) return;
        try {
            executor.execute(this::processChanges);
        } catch (RejectedExecutionException failure) {
            processing.set(false);
            reportStopped(new IOException("Could not schedule configuration reload for " + file, failure));
        }
    }

    private void processChanges() {
        try {
            while (!closed.get() && !storeClosed.getAsBoolean()) {
                pendingChange.set(false);
                TimeUnit.MILLISECONDS.sleep(180);
                if (pendingChange.get()) continue;
                diskCheck.inspect(reloadDiskState);
                if (!reloadDiskState.matches()) {
                    try { reload.reload(); }
                    catch (ConfigStore.ConfigValidationException | IOException error) { rejected.accept(error); }
                }
                if (!pendingChange.get()) break;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            reportStopped(error);
        } catch (RuntimeException error) {
            reportStopped(new IOException("Configuration watcher stopped for " + file, error));
        } catch (Error error) {
            reportStopped(new IOException("Configuration watcher failed for " + file, error));
            throw error;
        } finally {
            processing.set(false);
            if (pendingChange.get() && !closed.get() && !storeClosed.getAsBoolean()) schedule();
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ConfigWatchRegistry.instance().unregister(this);
        executor.shutdownNow();
    }
}
