package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeControlBridge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class GnomeApplicationLevelCommandsTest {
    @Test void appAndProcessSaveOneUnmutedRuleBeforeConfirmationAppliesRuntime() {
        for (String key : List.of("app:org.player", "process:player")) {
            Controls controls = new Controls();
            CompletableFuture<Void> action = GnomeLevelCommands.setApplicationLevel(controls, key + "\t0.35", false);
            CompletableFuture<Void> command = confirmSavedVolume(controls, action, key);

            assertEquals(List.of(key), controls.ensuredKeys);
            assertEquals(List.of(Map.of("volume", 0.35, "mute", false)), controls.rules);
            assertEquals(List.of("save"), controls.events);
            assertFalse(action.isDone());
            assertFalse(command.isDone());

            controls.persistence.complete(null);
            assertTrue(action.isDone());
            assertEquals(List.of("save", "release", "apply", "volume:10:0.35"), controls.events);
            assertFalse(command.isDone());
            controls.runtime.complete(null);
            assertTrue(command.isDone());
            command.join();
            assertEquals(List.of("save", "release", "apply", "volume:10:0.35", "publish"), controls.events);
            assertEquals(1, controls.rules.size());
        }
    }

    @Test void failedPersistenceNeverWritesRuntimeOrStartsConfirmation() {
        for (String key : List.of("app:org.player", "process:player")) {
            Controls controls = new Controls();
            CompletableFuture<Void> action = GnomeLevelCommands.setApplicationLevel(controls, key + "\t0.35", false);
            CompletableFuture<Void> command = confirmSavedVolume(controls, action, key);
            IllegalStateException failure = new IllegalStateException("Could not persist rule");

            controls.persistence.completeExceptionally(failure);
            assertTrue(command.isDone());
            assertSame(failure, assertThrows(CompletionException.class, command::join).getCause());
            assertEquals(List.of("save"), controls.events);
            assertEquals(List.of(key), controls.ensuredKeys);
        }
    }

    @Test void savedMuteTogglesUseAllMatchingStreamsWithoutDirectRuntimeWrites() {
        for (String key : List.of("app:org.player", "process:player")) {
            for (boolean allMuted : List.of(false, true)) {
                Controls controls = new Controls(List.of(stream(10, "music", true), stream(11, "speech", allMuted)));
                CompletableFuture<Void> action = GnomeLevelCommands.setApplicationLevel(controls, key, true);

                assertEquals(List.of(key), controls.ensuredKeys);
                assertEquals(List.of(Map.of("mute", !allMuted)), controls.rules);
                assertEquals(List.of("save"), controls.events);
                assertFalse(action.isDone());
                controls.persistence.complete(null);
                assertTrue(action.isDone());
                action.join();
                assertEquals(List.of("save"), controls.events);
            }
        }
    }

    @Test void streamKeysOnlyWriteRuntimeAndWaitForItsCompletion() {
        for (boolean toggleMute : List.of(false, true)) {
            Controls controls = new Controls();
            CompletableFuture<Void> action = GnomeLevelCommands.setApplicationLevel(controls,
                    toggleMute ? "stream:music" : "stream:music\t1.5", toggleMute);

            assertEquals(List.of(toggleMute ? "mute:10:false" : "volume:10:1.0"), controls.events);
            assertTrue(controls.rules.isEmpty());
            assertTrue(controls.ensuredKeys.isEmpty());
            assertFalse(action.isDone());
            controls.runtime.complete(null);
            assertTrue(action.isDone());
            action.join();
            assertEquals(Map.of("applications", List.of()), controls.config);
        }
    }

    @Test void malformedMissingAndNonfiniteValuesKeepTheirErrorsWithoutSideEffects() {
        for (boolean toggleMute : List.of(false, true)) {
            for (String missing : List.of("", "\t0.4", " \t0.4"))
                assertRejected(missing, toggleMute, "Application id is missing");
            for (String unavailable : List.of("app:missing", "process:missing", "stream:missing"))
                assertRejected(unavailable + "\t0.4", toggleMute, "Application stream is unavailable");
        }
        for (String key : List.of("app:org.player", "process:player", "stream:music")) {
            assertRejected(key, false, "Application volume is missing");
            for (String invalid : List.of("", "invalid", "NaN", "Infinity", "-Infinity", "1e309"))
                assertRejected(key + "\t" + invalid, false, "Application volume is invalid");
        }
    }

    private static CompletableFuture<Void> confirmSavedVolume(Controls controls, CompletableFuture<Void> action, String key) {
        return action.thenCompose(ignored -> GnomeCommandConfirmation.confirm(
                new GnomeControlBridge.Command("set_app_volume", key + "\t0.35"), () -> {
                    controls.events.add("apply");
                    return controls.volume(controls.nodes.get(0), (double) controls.rules.get(0).get("volume"));
                }, () -> { throw new AssertionError("Saved rules must not use runtime-only refresh"); }, () -> {
                    controls.events.add("publish");
                    return CompletableFuture.completedFuture(null);
                }, () -> controls.events.add("release")));
    }

    private static void assertRejected(String value, boolean toggleMute, String message) {
        Controls controls = new Controls();
        CompletableFuture<Void> result = GnomeLevelCommands.setApplicationLevel(controls, value, toggleMute);
        assertTrue(result.isDone());
        Throwable failure = assertThrows(CompletionException.class, result::join).getCause();
        assertInstanceOf(IllegalArgumentException.class, failure);
        assertEquals(message, failure.getMessage());
        assertTrue(controls.events.isEmpty());
        assertTrue(controls.rules.isEmpty());
        assertTrue(controls.ensuredKeys.isEmpty());
    }

    private static AudioNode stream(int id, String name, boolean muted) {
        return new AudioNode(id, name, name, "Stream/Output/Audio", "running", "Player", "player", "org.player",
                2, List.of(48_000), 0.8, muted, null);
    }

    private static final class Controls implements GnomeLevelCommands.ApplicationControls {
        final List<AudioNode> nodes;
        final List<String> events = new ArrayList<>();
        final List<String> ensuredKeys = new ArrayList<>();
        final List<Map<String, Object>> rules = new ArrayList<>();
        final Map<String, Object> config = new LinkedHashMap<>();
        final CompletableFuture<Void> persistence = new CompletableFuture<>();
        final CompletableFuture<Void> runtime = new CompletableFuture<>();

        Controls() { this(List.of(stream(10, "music", true), stream(11, "speech", false))); }
        Controls(List<AudioNode> nodes) {
            this.nodes = nodes;
            config.put("applications", rules);
        }
        public List<AudioNode> streams() { return nodes; }
        public CompletableFuture<Void> save(Consumer<Map<String, Object>> change) {
            events.add("save");
            change.accept(config);
            return persistence;
        }
        public Map<String, Object> ensureRule(Map<String, Object> config, String key) {
            assertSame(this.config, config);
            ensuredKeys.add(key);
            Map<String, Object> rule = new LinkedHashMap<>(Map.of("mute", true));
            rules.add(rule);
            return rule;
        }
        public CompletableFuture<Void> volume(AudioNode stream, double level) {
            events.add("volume:" + stream.id() + ":" + level);
            return runtime;
        }
        public CompletableFuture<Void> mute(AudioNode stream, boolean muted) {
            events.add("mute:" + stream.id() + ":" + muted);
            return runtime;
        }
    }
}
