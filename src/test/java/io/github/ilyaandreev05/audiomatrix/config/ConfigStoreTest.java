package io.github.ilyaandreev05.audiomatrix.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ConfigStoreTest {
    @TempDir Path temp;

    @Test void jsonStoreMigratesYamlWithAnExactBackup() throws Exception {
        Path legacy=temp.resolve("config.yaml"), json=temp.resolve("config.json");
        Map<String,Object> old=DefaultConfig.create();old.put("schema_version",1);
        byte[] original=new ObjectMapper(new YAMLFactory()).writeValueAsBytes(old);Files.write(legacy,original);
        try(ConfigStore store=new ConfigStore(json,legacy)){
            assertEquals(5,((Number)store.snapshot().get("schema_version")).intValue());
            assertTrue(Files.readString(json).startsWith("{"));
            assertTrue(java.util.Arrays.equals(original,Files.readAllBytes(temp.resolve("config.yaml.before-json"))));
            store.updateAsync(config->((Map<String,Object>)config.get("mixer")).put("global_volume",0.37)).join();
            assertTrue(Files.readString(json).startsWith("{"));
        }
    }

    @Test void schemaTwoRoutesAndGroupsAreAssignedToTheirOwningProfile() throws Exception {
        Path file=temp.resolve("config.json");
        Map<String,Object> old=DefaultConfig.create();old.put("schema_version",2);
        @SuppressWarnings("unchecked") Map<String,Object> profile=(Map<String,Object>)((java.util.List<?>)old.get("profiles")).getFirst();
        profile.remove("selected_route_id");
        String group=io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary.createGroup(old,"desk","Desk");
        var route=new io.github.ilyaandreev05.audiomatrix.graph.AudioGraph("desk-route","Desk route",java.util.List.of(),java.util.List.of());
        io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary.saveRoute(old,route,group);
        old.put("active_route",route.id());
        @SuppressWarnings("unchecked") java.util.List<Map<String,Object>> groups=(java.util.List<Map<String,Object>>)old.get("route_groups");groups.getFirst().remove("profile_id");
        @SuppressWarnings("unchecked") java.util.List<Map<String,Object>> routes=(java.util.List<Map<String,Object>>)old.get("route_library");routes.getFirst().remove("profile_id");
        byte[] schemaTwo= new ObjectMapper().writeValueAsBytes(old);Files.write(file,schemaTwo);

        try(ConfigStore store=new ConfigStore(file)) {
            Map<String,Object> migrated=store.snapshot();
            assertEquals(5,((Number)migrated.get("schema_version")).intValue());
            assertEquals("desk",groups(migrated).getFirst().get("profile_id"));
            assertEquals("desk",routes(migrated).getFirst().get("profile_id"));
            assertEquals(route.id(),profile(migrated).get("selected_route_id"));
            assertEquals(java.util.List.of(),ConfigValidator.validate(migrated));
            assertTrue(java.util.Arrays.equals(schemaTwo,Files.readAllBytes(temp.resolve("config.json.before-schema-5"))));
        }
    }

    @Test void migrationRefusesToOverwriteAnExistingBackup() throws Exception {
        Path legacy=temp.resolve("config.yaml"),json=temp.resolve("config.json"),backup=temp.resolve("config.yaml.before-json");
        Map<String,Object> old=DefaultConfig.create();old.put("schema_version",1);Files.write(legacy,new ObjectMapper(new YAMLFactory()).writeValueAsBytes(old));
        Files.writeString(backup,"keep");
        assertThrows(java.io.IOException.class,()->new ConfigStore(json,legacy));
        assertFalse(Files.exists(json));assertEquals("keep",Files.readString(backup));assertTrue(Files.exists(legacy));
    }

    @SuppressWarnings("unchecked") private static java.util.List<Map<String,Object>> groups(Map<String,Object> config){return (java.util.List<Map<String,Object>>)config.get("route_groups");}
    @SuppressWarnings("unchecked") private static java.util.List<Map<String,Object>> routes(Map<String,Object> config){return (java.util.List<Map<String,Object>>)config.get("route_library");}
    @SuppressWarnings("unchecked") private static Map<String,Object> profile(Map<String,Object> config){return (Map<String,Object>)((java.util.List<?>)config.get("profiles")).getFirst();}
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
