package io.github.ilyaandreev05.audiomatrix.config;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shares one process-wide WatchService and dispatches directory events to interested stores. */
final class ConfigWatchRegistry {
    private static final ConfigWatchRegistry INSTANCE = new ConfigWatchRegistry();

    private final Map<Path, DirectoryRegistration> directories = new HashMap<>();
    private final Map<WatchKey, DirectoryRegistration> directoriesByKey = new HashMap<>();
    private final Map<ConfigWatcher, FileRegistration> registrations = new HashMap<>();
    private WatchService watchService;
    private IOException watchServiceFailure;

    private ConfigWatchRegistry() {}

    static ConfigWatchRegistry instance() { return INSTANCE; }

    synchronized void register(Path file, ConfigWatcher watcher) throws IOException {
        IOException nativeFailure = watchServiceFailure;
        if (nativeFailure == null) {
            try {
                ensureWatchService();
                registerNative(file, watcher);
                return;
            } catch (IOException failure) {
                nativeFailure = failure;
                if (watchService == null) watchServiceFailure = failure;
            }
        }
        ConfigWatchPoller.register(watcher, nativeFailure);
    }

    private void registerNative(Path file, ConfigWatcher watcher) throws IOException {
        Path directoryPath = file.getParent();
        DirectoryRegistration registration = directories.get(directoryPath);
        if (registration != null && !registration.key.isValid()) {
            throw new IOException("Configuration watch registration is no longer valid for " + directoryPath);
        }

        Path fileName = file.getFileName();
        WatchKey newKey = null;
        boolean newDirectory = false;
        try {
            if (registration == null) {
                newKey = directoryPath.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_CREATE);
                if (!newKey.isValid()) {
                    throw new IOException("Configuration watch registration was invalid for " + directoryPath);
                }
                registration = new DirectoryRegistration(directoryPath, newKey);
                newDirectory = true;
                directories.put(directoryPath, registration);
                directoriesByKey.put(newKey, registration);
            }

            registration.files.computeIfAbsent(fileName, ignored -> new HashSet<>()).add(watcher);
            registrations.put(watcher, new FileRegistration(registration, fileName));
        } catch (IOException | RuntimeException | Error failure) {
            rollbackRegistration(registration, fileName, watcher, newDirectory, newKey);
            throw failure;
        }
    }

    private void rollbackRegistration(DirectoryRegistration registration, Path fileName,
                                      ConfigWatcher watcher, boolean newDirectory, WatchKey newKey) {
        if (registration == null) {
            if (newKey != null) newKey.cancel();
            return;
        }
        registrations.remove(watcher);
        Set<ConfigWatcher> watchers = registration.files.get(fileName);
        if (watchers != null) {
            watchers.remove(watcher);
            if (watchers.isEmpty()) registration.files.remove(fileName);
        }
        if (newDirectory) {
            directories.remove(registration.path, registration);
            directoriesByKey.remove(registration.key, registration);
            registration.key.cancel();
            registration.files.clear();
        } else if (registration.files.isEmpty()) {
            removeDirectory(registration);
        }
    }

    synchronized void unregister(ConfigWatcher watcher) {
        ConfigWatchPoller.unregister(watcher);
        FileRegistration fileRegistration = registrations.remove(watcher);
        if (fileRegistration == null) return;

        DirectoryRegistration directory = fileRegistration.directory;
        Set<ConfigWatcher> watchers = directory.files.get(fileRegistration.fileName);
        if (watchers != null) {
            watchers.remove(watcher);
            if (watchers.isEmpty()) directory.files.remove(fileRegistration.fileName);
        }
        if (directory.files.isEmpty()) removeDirectory(directory);
    }

    private void ensureWatchService() throws IOException {
        if (watchService != null) return;

        WatchService service = FileSystems.getDefault().newWatchService();
        try {
            Thread dispatcher = new Thread(() -> dispatch(service), "audio-matrix-config-watch-dispatch");
            dispatcher.setDaemon(true);
            watchService = service;
            dispatcher.start();
        } catch (RuntimeException | Error failure) {
            watchService = null;
            try { service.close(); }
            catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private void dispatch(WatchService service) {
        try {
            while (true) dispatchKey(service.take());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failService(service, new IOException("Configuration watch dispatcher was interrupted", error));
        } catch (ClosedWatchServiceException error) {
            failService(service, new IOException("Configuration watch service stopped unexpectedly", error));
        } catch (RuntimeException error) {
            failService(service, new IOException("Configuration watch dispatcher failed", error));
        } catch (Error error) {
            failService(service, new IOException("Configuration watch dispatcher failed", error));
            throw error;
        }
    }

    private void dispatchKey(WatchKey key) {
        Set<ConfigWatcher> changed = new LinkedHashSet<>();
        List<ConfigWatcher> invalidated = List.of();
        Path directoryPath = null;
        synchronized (this) {
            DirectoryRegistration directory = directoriesByKey.get(key);
            if (directory == null) {
                key.cancel();
                return;
            }
            directoryPath = directory.path;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    directory.files.values().forEach(changed::addAll);
                } else if (event.context() instanceof Path name) {
                    Set<ConfigWatcher> watchers = directory.files.get(name);
                    if (watchers != null) changed.addAll(watchers);
                }
            }
            if (!key.reset()) invalidated = removeDirectory(directory);
        }

        changed.forEach(ConfigWatcher::signalChanged);
        if (!invalidated.isEmpty()) {
            IOException failure = new IOException("Configuration watch directory is no longer available: " + directoryPath);
            invalidated.forEach(watcher -> watcher.reportStopped(failure));
        }
    }

    private void failService(WatchService service, IOException failure) {
        List<ConfigWatcher> affected;
        List<WatcherFailure> fallbackFailures = new ArrayList<>();
        Error fatal = null;
        synchronized (this) {
            if (watchService != service) return;
            affected = new ArrayList<>(registrations.keySet());
            directories.values().forEach(directory -> directory.key.cancel());
            directories.clear();
            directoriesByKey.clear();
            registrations.clear();
            watchService = null;
            watchServiceFailure = failure;
            for (ConfigWatcher watcher : affected) {
                try {
                    ConfigWatchPoller.register(watcher, failure);
                } catch (IOException fallbackFailure) {
                    fallbackFailures.add(new WatcherFailure(watcher, fallbackFailure));
                } catch (Error fallbackFailure) {
                    fallbackFailures.add(new WatcherFailure(watcher,
                            new IOException("Configuration polling fallback could not start", fallbackFailure)));
                    if (fatal == null) fatal = fallbackFailure;
                }
            }
        }
        try { service.close(); }
        catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
        fallbackFailures.forEach(item -> item.watcher.reportStopped(item.failure));
        if (fatal != null) throw fatal;
    }

    private List<ConfigWatcher> removeDirectory(DirectoryRegistration directory) {
        directories.remove(directory.path, directory);
        directoriesByKey.remove(directory.key, directory);
        directory.key.cancel();
        List<ConfigWatcher> affected = new ArrayList<>();
        for (Set<ConfigWatcher> watchers : directory.files.values()) {
            for (ConfigWatcher watcher : watchers) {
                registrations.remove(watcher);
                affected.add(watcher);
            }
        }
        directory.files.clear();
        return affected;
    }

    private static final class DirectoryRegistration {
        private final Path path;
        private final WatchKey key;
        private final Map<Path, Set<ConfigWatcher>> files = new HashMap<>();

        private DirectoryRegistration(Path path, WatchKey key) {
            this.path = path;
            this.key = key;
        }
    }

    private record FileRegistration(DirectoryRegistration directory, Path fileName) {}
    private record WatcherFailure(ConfigWatcher watcher, IOException failure) {}
}
