package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.GraphProfileLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class GnomeNodeCommandHandlerTest {
    @TempDir Path temp;

    @Test void parsesExactApiV5PayloadsAndPreservesTheStableTargetTuple() {
        GnomeNodeCommandHandler.Target target = new GnomeNodeCommandHandler.Target("route-1", "generator-2", "app:music");

        assertEquals(new GnomeNodeCommandHandler.SetVolume(target, 0.4), GnomeNodeCommandHandler.parse(
                "setNodeVolume", "{\"target\":{\"routeId\":\"route-1\",\"nodeId\":\"generator-2\",\"instanceKey\":\"app:music\"},\"value\":0.4}"));
        assertEquals(new GnomeNodeCommandHandler.SetMuted(target, true), GnomeNodeCommandHandler.parse(
                "setNodeMuted", "{\"target\":{\"routeId\":\"route-1\",\"nodeId\":\"generator-2\",\"instanceKey\":\"app:music\"},\"muted\":true}"));
        assertEquals(new GnomeNodeCommandHandler.SetRouteEnabled("route-1", "switch-2", "in->out", false),
                GnomeNodeCommandHandler.parse("setRouteEnabled",
                        "{\"routeId\":\"route-1\",\"switchNodeId\":\"switch-2\",\"linkId\":\"in->out\",\"enabled\":false}"));
        assertEquals(new GnomeNodeCommandHandler.SelectProfile("profile-1"),
                GnomeNodeCommandHandler.parse("select_profile", "{\"profileId\":\"profile-1\"}"));
        GnomeNodeCommandHandler.Target savedNode =
                new GnomeNodeCommandHandler.Target("route-1", "gain-1", "");
        assertEquals(new GnomeNodeCommandHandler.SetVolume(savedNode, 0.25),
                GnomeNodeCommandHandler.parse("setNodeVolume",
                        GnomeNodeCommandHandler.encodeVolume(savedNode, 0.25)));
        assertEquals(new GnomeNodeCommandHandler.SetMuted(savedNode, false),
                GnomeNodeCommandHandler.parse("setNodeMuted",
                        GnomeNodeCommandHandler.encodeMuted(savedNode, false)));
    }

    @Test void profileSelectionPersistsItsRememberedRouteOnlyAfterRuntimeConfirmation() throws Exception {
        Map<String, Object> config = DefaultConfig.create();
        Map<String, Object> secondProfile = GraphProfileLibrary.createProfile(config, "main", "Second");
        String profileId = String.valueOf(secondProfile.get("id"));
        GraphRouteLibrary.duplicateRoute(config, "main", "First route", profileId, "");
        String rememberedRoute = GraphRouteLibrary.duplicateRoute(config, "main", "Remembered route",
                profileId, "").id();
        GraphProfileLibrary.activateProfile(config, profileId);
        GraphRouteLibrary.activate(config, rememberedRoute);
        GraphProfileLibrary.activateProfile(config, "main");

        try (ConfigStore store = new ConfigStore(temp.resolve("profiles.json"))) {
            store.save(config);
            GnomeNodeCommandHandler.Payload parsed = GnomeNodeCommandHandler.parse(
                    "select_profile", "{\"profileId\":\"" + profileId + "\"}");
            assertEquals(new GnomeNodeCommandHandler.SelectProfile(profileId), parsed);
            CompletableFuture<Void> runtime = new CompletableFuture<>();
            CompletableFuture<Void> command = GnomeNodeCommandHandler.applyRuntimeThenStore(store,
                    candidate -> GraphProfileLibrary.activateProfile(candidate, profileId), true,
                    candidate -> {
                        assertEquals(rememberedRoute, candidate.get("active_route"));
                        return runtime;
                    },
                    ignored -> CompletableFuture.completedFuture(null),
                    error -> fail(error), () -> {});

            assertFalse(command.isDone());
            assertEquals("main", store.snapshot().get("active_route"));
            runtime.complete(null);
            command.join();

            Map<String, Object> saved = store.snapshot();
            assertEquals(profileId, saved.get("active_profile"));
            assertEquals(rememberedRoute, saved.get("active_route"));
            @SuppressWarnings("unchecked") List<Map<String, Object>> profiles =
                    (List<Map<String, Object>>) saved.get("profiles");
            assertEquals(rememberedRoute, profiles.stream()
                    .filter(profile -> profileId.equals(profile.get("id"))).findFirst().orElseThrow()
                    .get("selected_route_id"));
        }
    }

    @Test void rejectsMalformedMissingUnsupportedWrongTypeAndOutOfRangePayloadFields() {
        assertInvalid("setNodeVolume", "not-json");
        assertInvalid("setNodeVolume", "{\"target\":{},\"value\":0.5}");
        assertInvalid("setNodeVolume", "{\"target\":{\"routeId\":\"r\",\"nodeId\":\"g\",\"instanceKey\":\"\"},\"value\":\"0.5\"}");
        assertInvalid("setNodeVolume", "{\"target\":{\"routeId\":\"r\",\"nodeId\":\"g\",\"instanceKey\":\"\"},\"value\":1.01}");
        assertInvalid("setNodeVolume", "{\"target\":{\"routeId\":\"r\",\"nodeId\":\"g\",\"instanceKey\":\"\"},\"value\":0.2,\"extra\":true}");
        assertInvalid("setNodeVolume", "{\"target\":{\"routeId\":\"r\",\"nodeId\":\"g\",\"instanceKey\":\"\"},\"value\":0.3,\"value\":0.4}");
        assertInvalid("setNodeVolume", "{\"target\":{\"routeId\":\"r\",\"nodeId\":\"g\",\"instanceKey\":\"\"},\"value\":0.4} {}");
        assertInvalid("setNodeMuted", "{\"target\":{\"routeId\":\"r\",\"nodeId\":\"g\",\"instanceKey\":\"\"},\"muted\":\"false\"}");
        assertInvalid("setRouteEnabled", "{\"routeId\":\"r\",\"switchNodeId\":\"s\",\"linkId\":\"a->b\"}");
        assertInvalid("select_profile", "{\"profileId\":\"p\",\"fallback\":\"default\"}");
        assertInvalid("unsupported", "{}");
    }

    @Test void waitsForRuntimeAndStoreAndRollsRuntimeBackWhenPersistenceFails() throws Exception {
        try (ConfigStore store = new ConfigStore(temp.resolve("config.json"))) {
            CompletableFuture<Void> runtime = new CompletableFuture<>();
            AtomicBoolean saved = new AtomicBoolean();
            CompletableFuture<Void> command = GnomeNodeCommandHandler.applyRuntimeThenStore(store,
                    config -> ui(config).put("language", "en"), true,
                    config -> runtime, error -> fail(error), () -> saved.set(true));

            assertFalse(command.isDone());
            assertEquals("ru", ui(store.snapshot()).get("language"));
            runtime.complete(null);
            command.join();
            assertEquals("en", ui(store.snapshot()).get("language"));
            assertTrue(saved.get());
            AtomicBoolean runtimeRolledBack = new AtomicBoolean();
            AtomicBoolean failedSaveNotice = new AtomicBoolean();
            CompletableFuture<Void> runtimeFailure = GnomeNodeCommandHandler.applyRuntimeThenStore(store,
                    config -> ui(config).put("language", "ru"),
                    true,
                    config -> {
                        if ("ru".equals(ui(config).get("language")))
                            return CompletableFuture.failedFuture(new IllegalStateException("backend rejected"));
                        runtimeRolledBack.set(true);
                        return CompletableFuture.completedFuture(null);
                    }, error -> failedSaveNotice.set(true), () -> fail("Failed runtime must not be reported as saved"));
            assertThrows(CompletionException.class, runtimeFailure::join);
            assertTrue(runtimeRolledBack.get());
            assertFalse(failedSaveNotice.get());
            assertEquals("en", ui(store.snapshot()).get("language"));


            AtomicBoolean rolledBack = new AtomicBoolean();
            AtomicBoolean saveFailed = new AtomicBoolean();
            CompletableFuture<Void> failed = GnomeNodeCommandHandler.applyRuntimeThenStore(store,
                    config -> ui(config).put("language", "ru"), true,
                    config -> {
                        if ("ru".equals(ui(config).get("language"))) {
                            FilesConflict.forceExternalChange(store.path());
                            return CompletableFuture.completedFuture(null);
                        }
                        rolledBack.set(true);
                        return CompletableFuture.completedFuture(null);
                    }, error -> saveFailed.set(true), () -> fail("Failed save must not be reported as saved"));

            assertThrows(CompletionException.class, failed::join);
            assertTrue(saveFailed.get());
            assertTrue(rolledBack.get());
            assertEquals("en", ui(store.snapshot()).get("language"));
        }
    }

    @Test void awaitsAsynchronousRollbackAfterAnExternalFingerprintConflict() throws Exception {
        try (ConfigStore store = new ConfigStore(temp.resolve("delayed-rollback.json"))) {
            CompletableFuture<Void> rollback = new CompletableFuture<>();
            CountDownLatch rollbackStarted = new CountDownLatch(1);
            AtomicBoolean saveFailed = new AtomicBoolean();
            CompletableFuture<Void> command = GnomeNodeCommandHandler.applyRuntimeThenStore(store,
                    config -> ui(config).put("language", "en"), true,
                    candidate -> {
                        FilesConflict.forceExternalChange(store.path());
                        return CompletableFuture.completedFuture(null);
                    },
                    candidate -> {
                        assertEquals("ru", ui(candidate).get("language"));
                        rollbackStarted.countDown();
                        return rollback;
                    },
                    error -> saveFailed.set(true), () -> fail("Failed save must not be reported as saved"));

            assertTrue(rollbackStarted.await(5, TimeUnit.SECONDS));
            assertTrue(saveFailed.get());
            assertFalse(command.isDone());
            assertEquals("ru", ui(store.snapshot()).get("language"));
            rollback.complete(null);
            assertThrows(CompletionException.class, command::join);
            assertEquals("ru", ui(store.snapshot()).get("language"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ui(Map<String, Object> config) {
        return (Map<String, Object>) config.get("ui");
    }

    private static void assertInvalid(String name, String payload) {
        assertThrows(IllegalArgumentException.class, () -> GnomeNodeCommandHandler.parse(name, payload));
    }

    private static final class FilesConflict {
        static void forceExternalChange(Path file) {
            try {
                java.nio.file.Files.writeString(file, "external change");
            } catch (java.io.IOException error) {
                throw new RuntimeException(error);
            }
        }
    }
}
