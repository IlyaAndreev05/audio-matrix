package io.github.ilyaandreev05.audiomatrix.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class ConfigStore implements AutoCloseable {
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    private final ExecutorService watcherExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audio-matrix-config-watch"); t.setDaemon(true); return t;
    });
    private final ExecutorService storageExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audio-matrix-config-storage"); t.setDaemon(true); return t;
    });
    private final List<Consumer<ConfigEvent>> listeners = new CopyOnWriteArrayList<>();
    private volatile Map<String, Object> current;
    private volatile String fingerprint;
    private volatile long revision;
    private volatile String diagnostic = "";
    private volatile boolean closed;

    public ConfigStore(Path file) throws IOException, ConfigValidationException {
        this.file = file.toAbsolutePath().normalize();
        Files.createDirectories(this.file.getParent());
        if (!Files.exists(this.file)) atomicWrite(mapper.writeValueAsBytes(DefaultConfig.create()));
        loadAndPublish(false);
        startWatcher();
    }

    public static Path defaultPath() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg == null || xdg.isBlank() ? Path.of(System.getProperty("user.home"), ".config") : Path.of(xdg);
        return base.resolve("audio-matrix").resolve("config.yaml");
    }
    public Path path() { return file; }
    public synchronized Map<String, Object> snapshot() {
        return new VersionedConfig(deepCopy(current), revision);
    }
    public synchronized long revision() { return revision; }
    public String diagnostic() { return diagnostic; }
    public void addListener(Consumer<ConfigEvent> listener) { listeners.add(listener); }
    public void removeListener(Consumer<ConfigEvent> listener) { listeners.remove(listener); }

    public synchronized void save(Map<String, Object> next) throws IOException, ConfigConflictException, ConfigValidationException {
        long expectedRevision = next instanceof VersionedConfig versioned ? versioned.revision : revision;
        save(next, expectedRevision);
    }

    private synchronized void save(Map<String, Object> next, long expectedRevision)
            throws IOException, ConfigConflictException, ConfigValidationException {
        if (expectedRevision != revision)
            throw new ConfigConflictException("Configuration changed since this snapshot was read; reload and resolve the edits before saving.");
        List<String> errors = ConfigValidator.validate(next);
        if (!errors.isEmpty()) throw new ConfigValidationException(errors);
        String diskFingerprint = hash(Files.readAllBytes(file));
        if (!diskFingerprint.equals(fingerprint)) throw new ConfigConflictException("Configuration changed on disk; reload and resolve the edits before saving.");
        byte[] data = mapper.writeValueAsBytes(next);
        atomicWrite(data);
        current = deepCopy(next);
        fingerprint = hash(data);
        revision++;
        diagnostic = "";
        publish(new ConfigEvent(current, "Configuration saved"));
    }

    public synchronized void requireRevision(long expectedRevision) throws IOException, ConfigConflictException {
        if (expectedRevision != revision)
            throw new ConfigConflictException("Configuration changed since the import preview; review it again before applying.");
        String diskFingerprint = hash(Files.readAllBytes(file));
        if (!diskFingerprint.equals(fingerprint))
            throw new ConfigConflictException("Configuration changed on disk after the import preview; reload and review it before applying.");
    }

    public synchronized void saveIfRevision(Map<String, Object> next, long expectedRevision)
            throws IOException, ConfigConflictException, ConfigValidationException {
        save(next, expectedRevision);
    }

    public CompletableFuture<Void> saveAsync(Map<String, Object> next) {
        return CompletableFuture.runAsync(() -> {
            try { save(next); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }, storageExecutor);
    }

    /** Applies a focused GUI edit to the latest validated config, serialized with other writes. */
    public CompletableFuture<Void> updateAsync(Consumer<Map<String, Object>> change) {
        return CompletableFuture.runAsync(() -> {
            synchronized (this) {
                Map<String, Object> next = deepCopy(current);
                change.accept(next);
                try { save(next, revision); }
                catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
            }
        }, storageExecutor);
    }

    /** Applies a focused update and returns the exact prior snapshot for a guarded undo. */
    public CompletableFuture<ConfigChange> updateWithHistoryAsync(Consumer<Map<String, Object>> change) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (this) {
                long beforeRevision = revision;
                Map<String, Object> before = deepCopy(current);
                Map<String, Object> next = deepCopy(current);
                change.accept(next);
                try { save(next, beforeRevision); }
                catch (Exception error) { throw new CompletionException(error); }
                return new ConfigChange(before, beforeRevision, revision);
            }
        }, storageExecutor);
    }

    /** Saves a versioned edit and returns the prior snapshot if the edit commits. */
    public CompletableFuture<ConfigChange> saveWithHistoryAsync(Map<String, Object> next) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (this) {
                long beforeRevision = revision;
                Map<String, Object> before = deepCopy(current);
                try { save(next); }
                catch (Exception error) { throw new CompletionException(error); }
                return new ConfigChange(before, beforeRevision, revision);
            }
        }, storageExecutor);
    }

    /** Restores a prior snapshot only while the caller's expected revision is still current. */
    public CompletableFuture<Long> restoreIfRevisionAsync(Map<String, Object> previous, long expectedRevision) {
        Map<String, Object> snapshot = deepCopy(previous);
        return CompletableFuture.supplyAsync(() -> {
            synchronized (this) {
                try { save(snapshot, expectedRevision); }
                catch (Exception error) { throw new CompletionException(error); }
                return revision;
            }
        }, storageExecutor);
    }

    public record ConfigChange(Map<String, Object> previousSnapshot, long beforeRevision, long afterRevision) {}

    public synchronized void reload() throws IOException, ConfigValidationException {
        loadAndPublish(true);
    }

    private synchronized void loadAndPublish(boolean notify) throws IOException, ConfigValidationException {
        byte[] bytes = Files.readAllBytes(file);
        Map<String, Object> parsed;
        try { parsed = mapper.readValue(bytes, MAP_TYPE); }
        catch (Exception e) {
            diagnostic = "YAML could not be parsed: " + e.getMessage();
            throw new ConfigValidationException(List.of(diagnostic));
        }
        List<String> errors = ConfigValidator.validate(parsed);
        if (!errors.isEmpty()) {
            diagnostic = String.join("; ", errors);
            throw new ConfigValidationException(errors);
        }
        current = deepCopy(parsed);
        fingerprint = hash(bytes);
        revision++;
        diagnostic = "";
        if (notify) publish(new ConfigEvent(current, "External configuration reloaded"));
    }

    private void startWatcher() {
        watcherExecutor.submit(() -> {
            try (var watch = FileSystems.getDefault().newWatchService()) {
                file.getParent().register(watch, java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
                        java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
                while (!closed) {
                    var key = watch.take(); boolean changed = false;
                    for (var event : key.pollEvents()) if (event.context() instanceof Path p && p.equals(file.getFileName())) changed = true;
                    key.reset();
                    if (!changed) continue;
                    Thread.sleep(180);
                    if (diskMatchesCurrent()) continue;
                    try { reload(); }
                    catch (ConfigValidationException | IOException e) {
                        diagnostic = e.getMessage(); publish(new ConfigEvent(snapshot(), "Configuration rejected: " + diagnostic));
                    }
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (IOException e) { diagnostic = "Configuration watcher stopped: " + e.getMessage(); }
        });
    }

    private synchronized boolean diskMatchesCurrent() throws IOException {
        return fingerprint != null && hash(Files.readAllBytes(file)).equals(fingerprint);
    }

    private void atomicWrite(byte[] data) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), ".config-", ".yaml.tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(java.nio.ByteBuffer.wrap(data)); channel.force(true);
            }
            try { Files.setPosixFilePermissions(temp, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
            catch (UnsupportedOperationException ignored) { }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { throw new IOException("The config filesystem does not support atomic replacement", e); }
        } finally { Files.deleteIfExists(temp); }
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> deepCopy(Map<String, Object> source) {
        return (Map<String, Object>) copyValue(source);
    }
    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), copyValue(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(copyValue(item)));
            return copy;
        }
        return value;
    }
    private static final class VersionedConfig extends LinkedHashMap<String, Object> {
        private static final long serialVersionUID = 1L;
        private final long revision;
        private VersionedConfig(Map<String, Object> value, long revision) {
            super(value);
            this.revision = revision;
        }
    }
    private void publish(ConfigEvent event) { listeners.forEach(listener -> { try { listener.accept(event); } catch (RuntimeException ignored) { } }); }
    @Override public void close() { closed = true; watcherExecutor.shutdownNow(); storageExecutor.shutdownNow(); }

    public record ConfigEvent(Map<String, Object> config, String message) {}
    public static class ConfigValidationException extends Exception {
        private final List<String> errors;
        public ConfigValidationException(List<String> errors) { super(String.join("; ", errors)); this.errors = List.copyOf(errors); }
        public List<String> errors() { return errors; }
    }
    public static class ConfigConflictException extends Exception { public ConfigConflictException(String message) { super(message); } }
}
