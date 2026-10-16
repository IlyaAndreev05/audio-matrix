package io.github.ilyaandreev05.audiomatrix.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ConfigStoreTest {
    @TempDir Path temp;
    @Test void invalidExternalYamlDoesNotReplaceLastGoodState() throws Exception {
        Path file = temp.resolve("config.yaml");
        try (ConfigStore store = new ConfigStore(file)) {
            double original = (double) ((Map<String, Object>) store.snapshot().get("mixer")).get("global_volume");
            Files.writeString(file, "schema_version: 1\napp: audio-matrix\n");
            assertThrows(ConfigStore.ConfigValidationException.class, store::reload);
            assertEquals(original, ((Map<String, Object>) store.snapshot().get("mixer")).get("global_volume"));
        }
    }
    @Test void concurrentExternalEditIsNotOverwritten() throws Exception {
        Path file = temp.resolve("config.yaml");
        try (ConfigStore store = new ConfigStore(file)) {
            Map<String, Object> next = store.snapshot();
            String external = Files.readString(file) + "# external edit\n";
            Files.writeString(file, external);
            assertThrows(ConfigStore.ConfigConflictException.class, () -> store.save(next));
            assertEquals(external, Files.readString(file));
        }
    }

    @Test void staleSnapshotIsRejectedAfterValidatedExternalReload() throws Exception {
        Path file = temp.resolve("config.yaml");
        try (ConfigStore store = new ConfigStore(file)) {
            Map<String, Object> stale = store.snapshot();
            String external = Files.readString(file).replace("language: \"ru\"", "language: \"en\"");
            assertNotEquals(Files.readString(file), external);
            Files.writeString(file, external);
            store.reload();

            assertThrows(ConfigStore.ConfigConflictException.class, () -> store.save(stale));
            assertEquals(external, Files.readString(file));
            assertEquals("en", ((Map<String, Object>) store.snapshot().get("ui")).get("language"));
        }
    }

    @Test void guiUpdateAppliesToLatestValidatedExternalConfig() throws Exception {
        Path file = temp.resolve("config.yaml");
        try (ConfigStore store = new ConfigStore(file)) {
            String external = Files.readString(file).replace("language: \"ru\"", "language: \"en\"");
            assertNotEquals(Files.readString(file), external);
            Files.writeString(file, external);
            store.reload();

            store.updateAsync(config -> ((Map<String, Object>) config.get("mixer")).put("global_volume", 0.37)).join();
            Map<String, Object> latest = store.snapshot();
            assertEquals("en", ((Map<String, Object>) latest.get("ui")).get("language"));
            assertEquals(0.37, ((Map<String, Object>) latest.get("mixer")).get("global_volume"));
        }
    }

    @Test void routeHistorySnapshotCanBeRestoredWhileRevisionMatches() throws Exception {
        Path file = temp.resolve("config.yaml");
        try (ConfigStore store = new ConfigStore(file)) {
            double original = ((Number) ((Map<String, Object>) store.snapshot().get("mixer")).get("global_volume")).doubleValue();
            ConfigStore.ConfigChange change = store.updateWithHistoryAsync(config ->
                    ((Map<String, Object>) config.get("mixer")).put("global_volume", 0.37)).join();

            assertEquals(original, ((Number) ((Map<String, Object>) change.previousSnapshot().get("mixer")).get("global_volume")).doubleValue());
            assertEquals(change.beforeRevision() + 1, change.afterRevision());
            long restoredRevision = store.restoreIfRevisionAsync(change.previousSnapshot(), change.afterRevision()).join();

            assertEquals(change.afterRevision() + 1, restoredRevision);
            assertEquals(original, ((Number) ((Map<String, Object>) store.snapshot().get("mixer")).get("global_volume")).doubleValue());
        }
    }

    @Test void guardedHistoryRestoreRejectsNewerConfiguration() throws Exception {
        Path file = temp.resolve("config.yaml");
        try (ConfigStore store = new ConfigStore(file)) {
            ConfigStore.ConfigChange change = store.updateWithHistoryAsync(config ->
                    ((Map<String, Object>) config.get("mixer")).put("global_volume", 0.37)).join();
            store.updateAsync(config -> ((Map<String, Object>) config.get("mixer")).put("global_volume", 0.52)).join();

            var undo = store.restoreIfRevisionAsync(change.previousSnapshot(), change.afterRevision());
            java.util.concurrent.CompletionException error = assertThrows(java.util.concurrent.CompletionException.class, undo::join);
            assertInstanceOf(ConfigStore.ConfigConflictException.class, error.getCause());
            assertEquals(0.52, ((Number) ((Map<String, Object>) store.snapshot().get("mixer")).get("global_volume")).doubleValue());
        }
    }

    @Test void watcherIgnoresOwnAtomicSaveAndStillReloadsExternalEdit() throws Exception {
        Path file = temp.resolve("config.yaml");
        try (ConfigStore store = new ConfigStore(file)) {
            LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
            store.addListener(event -> messages.add(event.message()));

            store.updateAsync(config -> ((Map<String, Object>) config.get("mixer")).put("global_volume", 0.42)).join();
            assertEquals("Configuration saved", messages.poll(1, TimeUnit.SECONDS));
            assertNull(messages.poll(450, TimeUnit.MILLISECONDS), "the watcher must not report the app's own save as an external edit");

            String external = Files.readString(file).replace("language: \"ru\"", "language: \"en\"");
            assertNotEquals(Files.readString(file), external);
            Files.writeString(file, external);

            assertEquals("External configuration reloaded", messages.poll(2, TimeUnit.SECONDS));
            assertEquals("en", ((Map<String, Object>) store.snapshot().get("ui")).get("language"));
        }
    }
}
