package io.github.ilyaandreev05.audiomatrix.audio;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.CapturedStreamBinding;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationStreamRuleApplierTest {
    private static final String OWNED_APP_ID = "io.github.ilyaandreev05.audiomatrix";

    @Test
    void capturedStreamUsesExactNameAndCombinesCanonicalAppAndTabLevelsOnce() {
        List<Write> writes = new ArrayList<>();
        ApplicationStreamRuleApplier applier = new ApplicationStreamRuleApplier(
                (id, value) -> { writes.add(new Write(id, "volume", value)); return CompletableFuture.completedFuture(null); },
                (id, value) -> { writes.add(new Write(id, "mute", value)); return CompletableFuture.completedFuture(null); });
        String exactName = "audio-matrix-chrome-capture-key";
        AudioNode capture = stream(10, exactName, OWNED_APP_ID, "audiomatrix");
        AudioNode unboundOwnedCapture = stream(11, "audio-matrix-chrome-other-key", OWNED_APP_ID, "audiomatrix");
        AudioNode ordinaryChromeStream = stream(12, "chrome-audio-output", "com.google.Chrome", "chrome");
        PipeWireBackend.Snapshot snapshot = snapshot(List.of(capture, unboundOwnedCapture, ordinaryChromeStream));
        List<Map<String, Object>> rules = List.of(Map.of(
                "id", "chrome-volume", "match", Map.of("app_id", "com.google.Chrome"),
                "volume", 0.8, "mute", false));
        CapturedStreamBinding binding = new CapturedStreamBinding(exactName, "tab-source", "chrome-volume",
                "com.google.Chrome", "chrome", "tab-instance", 0.5, true);

        applier.apply(snapshot, rules, Map.of(exactName, binding), (stream, error) -> {
            throw new AssertionError(error);
        }).join();

        assertEquals(List.of(new Write(10, "volume", 0.4), new Write(10, "mute", true),
                new Write(12, "volume", 0.8)), writes);
        assertFalse(writes.stream().anyMatch(write -> write.nodeId() == unboundOwnedCapture.id()));
    }

    @Test
    void forcedRollbackReassertsSavedLevelDespiteAStaleSnapshot() {
        List<Write> writes = new ArrayList<>();
        ApplicationStreamRuleApplier applier = new ApplicationStreamRuleApplier(
                (id, value) -> { writes.add(new Write(id, "volume", value)); return CompletableFuture.completedFuture(null); },
                (id, value) -> { writes.add(new Write(id, "mute", value)); return CompletableFuture.completedFuture(null); });
        AudioNode stream = stream(41, "chrome-output", "com.google.Chrome", "chrome");
        PipeWireBackend.Snapshot stale = snapshot(List.of(stream));
        List<Map<String, Object>> candidate = List.of(applicationRule(0.3));
        List<Map<String, Object>> restored = List.of(applicationRule(0.8));

        applier.apply(stale, candidate, Map.of(), (node, error) -> {}).join();
        applier.apply(stale, restored, Map.of(), true, (node, error) -> {}).join();

        assertTrue(writes.contains(new Write(41, "volume", 0.3)));
        assertTrue(writes.contains(new Write(41, "volume", 0.8)));
        assertEquals(new Write(41, "volume", 0.8), writes.get(writes.size() - 2));
        assertEquals(new Write(41, "mute", false), writes.getLast());
    }

    private static Map<String, Object> applicationRule(double volume) {
        return Map.of("id", "chrome-volume", "match", Map.of("app_id", "com.google.Chrome"),
                "volume", volume, "mute", false);
    }

    private static PipeWireBackend.Snapshot snapshot(List<AudioNode> streams) {
        return new PipeWireBackend.Snapshot(true, "", List.of(), List.of(), streams, List.of(), Map.of(), -1,
                System.currentTimeMillis());
    }

    private static AudioNode stream(int id, String name, String applicationId, String processName) {
        return new AudioNode(id, name, name, "Stream/Output/Audio", "running", "", processName,
                applicationId, 2, List.of(48000), 1.0, false, null);
    }

    private record Write(int nodeId, String property, Object value) {}
}
