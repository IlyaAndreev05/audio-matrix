package io.github.ilyaandreev05.audiomatrix.ui;

import org.junit.jupiter.api.Test;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeCommandRevision;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeControlBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class GnomePersistentCommandQueueTest {
    @Test void commandsWaitForPriorCompletionAndTheQueueRecoversAfterFailure() {
        GnomePersistentCommandQueue queue = new GnomePersistentCommandQueue();
        CompletableFuture<Void> firstRuntime = new CompletableFuture<>();
        List<Long> started = new ArrayList<>();

        CompletableFuture<Void> first = queue.submit(sequence -> {
            started.add(sequence);
            return firstRuntime;
        });
        CompletableFuture<Void> second = queue.submit(sequence -> {
            started.add(sequence);
            return CompletableFuture.completedFuture(null);
        });

        assertEquals(List.of(1L), started);
        assertFalse(second.isDone());
        firstRuntime.complete(null);
        first.join();
        second.join();
        assertEquals(List.of(1L, 2L), started);

        CompletableFuture<Void> failed = queue.submit(sequence -> {
            started.add(sequence);

            return CompletableFuture.failedFuture(new IllegalStateException("backend rejected"));
        });
        CompletableFuture<Void> afterFailure = queue.submit(sequence -> {
            started.add(sequence);
            return CompletableFuture.completedFuture(null);
        });
        assertThrows(CompletionException.class, failed::join);
        afterFailure.join();
        assertEquals(List.of(1L, 2L, 3L, 4L), started);
    }

    @Test void sameClientVolumeIntentsForOneTargetWaitForRuntimeInRequestOrder() {
        GnomePersistentCommandQueue queue = new GnomePersistentCommandQueue();
        GnomeNodeCommandHandler.Target target = new GnomeNodeCommandHandler.Target("main", "gain", "");
        GnomeControlBridge.Command first = new GnomeControlBridge.Command(5, "slider:drag-1", "9",
                "setNodeVolume", GnomeNodeCommandHandler.encodeVolume(target, 0.9));
        GnomeControlBridge.Command second = new GnomeControlBridge.Command(5, "slider:drag-2", "9",
                "setNodeVolume", GnomeNodeCommandHandler.encodeVolume(target, 0.5));
        GnomeNodeCommandHandler.PropertyKey expected =
                new GnomeNodeCommandHandler.PropertyKey("main", "gain", "", "volume");
        CompletableFuture<Void> firstRuntime = new CompletableFuture<>();
        List<String> started = new ArrayList<>();
        double[] storedVolume = {0.7};

        queue.submit(sequence -> {
            assertEquals(1L, sequence);
            assertEquals("slider", GnomeCommandRevision.clientId(first));
            assertEquals(expected, GnomeNodeCommandHandler.propertyKey(
                    GnomeNodeCommandHandler.parse(first.name(), first.value())));
            started.add(first.requestId());
            return firstRuntime.thenRun(() -> storedVolume[0] =
                    ((Number) GnomeNodeCommandHandler.intendedProperty(
                            GnomeNodeCommandHandler.parse(first.name(), first.value()))).doubleValue());
        });
        CompletableFuture<Void> secondResult = queue.submit(sequence -> {
            assertEquals(2L, sequence);
            assertEquals("slider", GnomeCommandRevision.clientId(second));
            assertEquals(expected, GnomeNodeCommandHandler.propertyKey(
                    GnomeNodeCommandHandler.parse(second.name(), second.value())));
            assertEquals(0.9, storedVolume[0], 0.0001,
                    "the next same-target/property command waits for prior runtime confirmation");
            started.add(second.requestId());
            storedVolume[0] = ((Number) GnomeNodeCommandHandler.intendedProperty(
                    GnomeNodeCommandHandler.parse(second.name(), second.value()))).doubleValue();
            return CompletableFuture.completedFuture(null);
        });

        assertEquals(List.of(first.requestId()), started);
        firstRuntime.complete(null);
        secondResult.join();
        assertEquals(List.of(first.requestId(), second.requestId()), started);
        assertEquals(0.5, storedVolume[0], 0.0001);
    }
}
