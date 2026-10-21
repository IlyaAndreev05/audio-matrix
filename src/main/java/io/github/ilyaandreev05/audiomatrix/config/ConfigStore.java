package io.github.ilyaandreev05.audiomatrix.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class ConfigStore implements AutoCloseable {
    private final Path file;
    private final Path legacyFile;
    private final ObjectMapper mapper;
    private final ExecutorService watcherExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audio-matrix-config-watch"); t.setDaemon(true); return t;
    });
    private final ExecutorService storageExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audio-matrix-config-storage"); t.setDaemon(true); return t;
    });
    private final List<Consumer<ConfigEvent>> listeners = new CopyOnWriteArrayList<>();
    private final ConfigStoreDocument document = new ConfigStoreDocument();
    private volatile boolean closed;

    public ConfigStore(Path file) throws IOException, ConfigValidationException {
        this(file, file.toAbsolutePath().normalize().equals(defaultPath()) ? legacyPath() : null);
    }

    ConfigStore(Path file, Path legacyFile) throws IOException, ConfigValidationException {
        this.file = file.toAbsolutePath().normalize();
        this.legacyFile = legacyFile == null ? null : legacyFile.toAbsolutePath().normalize();
        Files.createDirectories(this.file.getParent());
        this.mapper = this.file.getFileName().toString().endsWith(".json")
                ? new ObjectMapper() : new ObjectMapper(new YAMLFactory());
        migrateLegacyYaml();
        if (!Files.exists(this.file)) atomicWrite(mapper.writeValueAsBytes(DefaultConfig.create()));
        loadAndPublish(false);
        startWatcher();
    }

    public static Path defaultPath() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg == null || xdg.isBlank() ? Path.of(System.getProperty("user.home"), ".config") : Path.of(xdg);
        return base.resolve("audio-matrix").resolve("config.json");
    }
    public static Path legacyPath() {
        return defaultPath().resolveSibling("config.yaml");
    }
    public Path path() { return file; }
    public synchronized VersionedConfig snapshot() {
        return new VersionedConfig(document.copy(), document.revision());
    }
    /** Returns a defensive copy of a retained revision, or {@code null} when it was evicted. */
    public synchronized Map<String, Object> snapshotAtRevision(long requestedRevision) {
        return document.snapshotAtRevision(requestedRevision);
    }
    public synchronized long revision() { return document.revision(); }
    public String diagnostic() { return document.diagnostic(); }
    public void addListener(Consumer<ConfigEvent> listener) { listeners.add(listener); }
    public void removeListener(Consumer<ConfigEvent> listener) { listeners.remove(listener); }

    public synchronized void save(Map<String, Object> next) throws IOException, ConfigConflictException, ConfigValidationException {
        long expectedRevision = next instanceof VersionedConfig versioned ? versioned.revision() : document.revision();
        save(next, expectedRevision);
    }

    private synchronized void save(Map<String, Object> next, long expectedRevision)
            throws IOException, ConfigConflictException, ConfigValidationException {
        if (expectedRevision != document.revision())
            throw new ConfigConflictException("Configuration changed since this snapshot was read; reload and resolve the edits before saving.");
        List<String> errors = ConfigValidator.validate(next);
        if (!errors.isEmpty()) throw new ConfigValidationException(errors);
        String diskFingerprint = hash(Files.readAllBytes(file));
        if (!diskFingerprint.equals(document.fingerprint())) throw new ConfigConflictException("Configuration changed on disk; reload and resolve the edits before saving.");
        byte[] data = mapper.writeValueAsBytes(next);
        atomicWrite(data);
        document.commit(next, hash(data));
        publish(new ConfigEvent(document.current(), "Configuration saved"));
    }

    public synchronized void requireRevision(long expectedRevision) throws IOException, ConfigConflictException {
        if (expectedRevision != document.revision())
            throw new ConfigConflictException("Configuration changed since the import preview; review it again before applying.");
        String diskFingerprint = hash(Files.readAllBytes(file));
        if (!diskFingerprint.equals(document.fingerprint()))
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
                Map<String, Object> next = document.copy();
                change.accept(next);
                try { save(next, document.revision()); }
                catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
            }
        }, storageExecutor);
    }

    /** Applies a focused update and returns the exact prior snapshot for a guarded undo. */
    public CompletableFuture<ConfigChange> updateWithHistoryAsync(Consumer<Map<String, Object>> change) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (this) {
                long beforeRevision = document.revision();
                Map<String, Object> before = document.copy();
                Map<String, Object> next = document.copy();
                change.accept(next);
                try { save(next, beforeRevision); }
                catch (Exception error) { throw new CompletionException(error); }
                return new ConfigChange(before, beforeRevision, document.revision());
            }
        }, storageExecutor);
    }

    /** Saves a versioned edit and returns the prior snapshot if the edit commits. */
    public CompletableFuture<ConfigChange> saveWithHistoryAsync(Map<String, Object> next) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (this) {
                long beforeRevision = document.revision();
                Map<String, Object> before = document.copy();
                try { save(next); }
                catch (Exception error) { throw new CompletionException(error); }
                return new ConfigChange(before, beforeRevision, document.revision());
            }
        }, storageExecutor);
    }

    /** Restores a prior snapshot only while the caller's expected revision is still current. */
    public CompletableFuture<Long> restoreIfRevisionAsync(Map<String, Object> previous, long expectedRevision) {
        Map<String, Object> snapshot = ConfigFileIO.deepCopy(previous);
        return CompletableFuture.supplyAsync(() -> {
            synchronized (this) {
                try { save(snapshot, expectedRevision); }
                catch (Exception error) { throw new CompletionException(error); }
                return document.revision();
            }
        }, storageExecutor);
    }

    public record ConfigChange(Map<String, Object> previousSnapshot, long beforeRevision, long afterRevision) {}

    public synchronized void reload() throws IOException, ConfigValidationException {
        loadAndPublish(true);
    }

    private synchronized void loadAndPublish(boolean notify) throws IOException, ConfigValidationException {
        byte[] bytes = Files.readAllBytes(file);
        ConfigStoreLoader.Prepared prepared;
        try { prepared = ConfigStoreLoader.prepare(mapper, bytes); }
        catch (ConfigValidationException invalid) { document.diagnostic(invalid.getMessage()); throw invalid; }
        if (prepared.upgraded()) {
            backupBeforeSchemaUpgrade(DefaultConfig.SCHEMA_VERSION);
            bytes = mapper.writeValueAsBytes(prepared.config());
            atomicWrite(bytes);
        }
        document.commit(prepared.config(), hash(bytes));
        if (notify) publish(new ConfigEvent(document.current(), "External configuration reloaded"));
    }

    private void backupBeforeSchemaUpgrade(int schema) throws IOException {
        ConfigMigration.backupBeforeSchemaUpgrade(file, schema);
    }

    private void migrateLegacyYaml() throws IOException, ConfigValidationException {
        ConfigMigration.migrateLegacyYaml(file, legacyFile, mapper);
    }

    @SuppressWarnings("unchecked")
    static void upgradeConfig(Map<String,Object> config) {
        ConfigMigration.upgradeConfig(config);
    }

    /** Applies the schema upgrade in memory, exactly like loading the file, without writing it. */
    public static Map<String, Object> upgradedInMemory(Map<String, Object> config) {
        ConfigMigration.upgradeConfig(config);
        return config;
    }

    private void startWatcher() {
        ConfigWatcher.start(file, watcherExecutor, () -> closed, this::diskMatchesCurrent, this::reload,
                error -> { document.diagnostic(error.getMessage()); publish(new ConfigEvent(snapshot(), "Configuration rejected: " + document.diagnostic())); },
                error -> document.diagnostic("Configuration watcher stopped: " + error.getMessage()));
    }

    private synchronized boolean diskMatchesCurrent() throws IOException {
        return document.fingerprint() != null && hash(Files.readAllBytes(file)).equals(document.fingerprint());
    }

    private void atomicWrite(byte[] data) throws IOException {
        ConfigFileIO.atomicWrite(file, data);
    }
    private static String hash(byte[] bytes) { return ConfigFileIO.hash(bytes); }
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
