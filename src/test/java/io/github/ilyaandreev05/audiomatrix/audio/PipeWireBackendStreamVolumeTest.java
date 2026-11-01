package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireBackendStreamVolumeTest {
    @Test
    void streamVolumeUsesTheLinearValueAndWirePlumberCubicConversion() {
        AtomicReference<Double> receivedLinear = new AtomicReference<>();
        try (PipeWireBackend backend = new PipeWireBackend(
                (id, volume) -> receivedLinear.set(volume), (id, muted) -> {}, snapshot(List.of(), List.of(stream(17))))) {
            backend.setStreamVolume(17, 0.35).join();
        }

        assertEquals(0.35, receivedLinear.get(), 1e-12);
        assertEquals(Math.cbrt(0.35), WirePlumberMixerControl.wpctlCubicValue(0.35), 1e-12);
    }

    @Test
    void missingStreamDoesNotFallBackToTheSinkMixerApi() {
        AtomicInteger streamWrites = new AtomicInteger();
        AudioNode sinkWithRequestedId = new AudioNode(17, "sink", "Output", "Audio/Sink", "running",
                "", "", "", 2, List.of(48000), 0.8, false, null);
        try (PipeWireBackend backend = new PipeWireBackend(
                (id, volume) -> streamWrites.incrementAndGet(), (id, muted) -> {},
                snapshot(List.of(sinkWithRequestedId), List.of()))) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> backend.setStreamVolume(17, 0.35).join());
            assertEquals("Playback stream is unavailable: 17", failure.getCause().getMessage());
        }
        assertEquals(0, streamWrites.get());
    }

    @Test
    void streamMuteIsAlsoLiveValidatedAndStreamOnly() {
        AtomicReference<Boolean> muted = new AtomicReference<>();
        try (PipeWireBackend backend = new PipeWireBackend(
                (id, volume) -> {}, (id, value) -> muted.set(value), snapshot(List.of(), List.of(stream(17))))) {
            backend.setStreamMute(17, true).join();
        }
        assertEquals(Boolean.TRUE, muted.get());
    }

    @Test
    void missingStreamMuteDoesNotFallBackToTheSinkMixerApi() {
        AtomicInteger muteWrites = new AtomicInteger();
        AudioNode sinkWithRequestedId = new AudioNode(17, "sink", "Output", "Audio/Sink", "running",
                "", "", "", 2, List.of(48000), 0.8, false, null);
        try (PipeWireBackend backend = new PipeWireBackend(
                (id, volume) -> {}, (id, muted) -> muteWrites.incrementAndGet(),
                snapshot(List.of(sinkWithRequestedId), List.of()))) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> backend.setStreamMute(17, false).join());
            assertEquals("Playback stream is unavailable: 17", failure.getCause().getMessage());
        }
        assertEquals(0, muteWrites.get());
    }

    @Test
    void disconnectedSnapshotsCannotAuthorizeWritesToPreviouslyObservedStreams() {
        AtomicInteger writes = new AtomicInteger();
        PipeWireBackend.Snapshot offline = new PipeWireBackend.Snapshot(false, "disconnected",
                List.of(), List.of(), List.of(stream(17)), List.of(), Map.of(), -1, 1L);
        try (PipeWireBackend backend = new PipeWireBackend(
                (id, volume) -> writes.incrementAndGet(), (id, muted) -> writes.incrementAndGet(), offline)) {
            assertThrows(CompletionException.class, () -> backend.setStreamVolume(17, 0.35).join());
            assertThrows(CompletionException.class, () -> backend.setStreamMute(17, false).join());
        }
        assertEquals(0, writes.get());
    }

    @Test
    void streamControlsCannotOvertakeAnOlderSnapshotAndReadbackWaitsForBothWrites() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Double> liveVolume = new AtomicReference<>(0.8);
        List<String> completed = new ArrayList<>();
        try (PipeWireBackend backend = new PipeWireBackend(
                (id, volume) -> { liveVolume.set(volume); completed.add("volume:" + volume); },
                (id, muted) -> completed.add("mute:" + muted), snapshot(List.of(), List.of(stream(17))))) {
            var oldSnapshot = backend.control(() -> {
                double observed = liveVolume.get();
                reading.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Snapshot was not released");
                completed.add("snapshot:" + observed);
                return observed;
            });
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            var unmute = backend.setStreamMute(17, false);
            var volume = backend.setStreamVolume(17, 0.35);
            var readback = backend.control(() -> {
                double observed = liveVolume.get();
                completed.add("readback:" + observed);
                return observed;
            });
            assertFalse(unmute.isDone(), "an older snapshot and controls must use the same lane");
            assertFalse(volume.isDone());
            assertFalse(readback.isDone());
            release.countDown();

            assertEquals(0.8, oldSnapshot.get(5, TimeUnit.SECONDS), 1e-12);
            unmute.get(5, TimeUnit.SECONDS);
            volume.get(5, TimeUnit.SECONDS);
            assertEquals(0.35, readback.get(5, TimeUnit.SECONDS), 1e-12);
            assertEquals(List.of("snapshot:0.8", "mute:false", "volume:0.35", "readback:0.35"), completed);
        } finally {
            release.countDown();
        }
    }

    private static PipeWireBackend.Snapshot snapshot(List<AudioNode> sinks, List<AudioNode> streams) {
        return new PipeWireBackend.Snapshot(true, "", sinks, List.of(), streams, List.of(), Map.of(), -1, 1L);
    }

    private static AudioNode stream(int id) {
        return new AudioNode(id, "player", "Player", "Stream/Output/Audio", "running", "Player", "Player",
                "org.example.player", 2, List.of(48000), 0.8, false, null);
    }
}
