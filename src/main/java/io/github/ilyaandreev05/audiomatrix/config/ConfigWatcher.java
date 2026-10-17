package io.github.ilyaandreev05.audiomatrix.config;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owns filesystem observation without coupling the store to watch-service details. */
final class ConfigWatcher {
    @FunctionalInterface interface DiskCheck { boolean matches() throws IOException; }
    @FunctionalInterface interface Reload { void reload() throws IOException, ConfigStore.ConfigValidationException; }

    private ConfigWatcher() {}

    static void start(Path file, ExecutorService executor, BooleanSupplier closed, DiskCheck diskCheck,
                      Reload reload, Consumer<Exception> rejected, Consumer<IOException> stopped) {
        executor.submit(() -> watch(file, closed, diskCheck, reload, rejected, stopped));
    }

    private static void watch(Path file, BooleanSupplier closed, DiskCheck diskCheck, Reload reload,
                              Consumer<Exception> rejected, Consumer<IOException> stopped) {
        try (var watch = FileSystems.getDefault().newWatchService()) {
            file.getParent().register(watch, java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
                    java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
            while (!closed.getAsBoolean()) {
                var key = watch.take();
                boolean changed = key.pollEvents().stream().anyMatch(event ->
                        event.context() instanceof Path path && path.equals(file.getFileName()));
                key.reset();
                if (!changed) continue;
                TimeUnit.MILLISECONDS.sleep(180);
                if (diskCheck.matches()) continue;
                try { reload.reload(); }
                catch (ConfigStore.ConfigValidationException | IOException error) { rejected.accept(error); }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            stopped.accept(error);
        }
    }
}
