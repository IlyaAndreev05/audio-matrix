package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

abstract class ApplicationStreamRuleFixture {
    final List<Write> writes = new ArrayList<>();
    final ApplicationStreamRuleApplier applier = new ApplicationStreamRuleApplier(
            (id, volume) -> record(id, volume, null),
            (id, muted, savedVolume) -> record(id, savedVolume, muted));

    CompletableFuture<Void> apply(PipeWireBackend.Snapshot snapshot, double volume, boolean muted) {
        return applier.apply(snapshot, rules(volume, muted), null);
    }
    static List<Map<String, Object>> rules(double volume, boolean muted) {
        return List.of(Map.of("id", "player-rule", "match", Map.of("process", "player"), "volume", volume, "mute", muted));
    }
    static PipeWireBackend.Snapshot snapshot(AudioNode... streams) { return snapshotAt(1L, streams); }
    static PipeWireBackend.Snapshot snapshotAt(long capturedAtMillis, AudioNode... streams) {
        return new PipeWireBackend.Snapshot(true, "", List.of(), List.of(), List.of(streams), List.of(), Map.of(), -1, capturedAtMillis);
    }
    static AudioNode stream(int id, String name, double volume, boolean muted) {
        return new AudioNode(id, name, "Player", "Stream/Output/Audio", "running", "Player", "player",
                "org.example.player", 2, List.of(48000), volume, muted, null);
    }
    private CompletableFuture<Void> record(int id, double volume, Boolean muted) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        writes.add(new Write(id, volume, muted, completion));
        return completion;
    }
    void assertVolume(int index, double volume) {
        Write write = writes.get(index);
        assertEquals(17, write.nodeId());
        assertNull(write.muted());
        assertEquals(volume, write.volume(), 0.000001);
    }
    void assertMute(int index, boolean muted, double savedVolume) {
        Write write = writes.get(index);
        assertEquals(17, write.nodeId());
        assertEquals(Boolean.valueOf(muted), write.muted());
        assertEquals(savedVolume, write.volume(), 0.000001);
    }
    void complete(int index) { writes.get(index).completion().complete(null); }
    static void assertSucceeded(CompletableFuture<Void> result) {
        assertTrue(result.isDone(), "the exact intent must have completed without another backend operation");
        result.join();
    }
    record Write(int nodeId, double volume, Boolean muted, CompletableFuture<Void> completion) {}
}
