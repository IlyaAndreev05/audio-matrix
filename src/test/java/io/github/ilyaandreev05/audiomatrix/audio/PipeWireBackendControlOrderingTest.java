package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the actual backend control lane without starting a PipeWire client. */
class PipeWireBackendControlOrderingTest {
    @Test
    void olderObservationCannotFinishAfterWriteAndReadbackBarrier() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger liveLevel = new AtomicInteger(100);
        List<String> completed = new ArrayList<>();
        try (PipeWireBackend backend = new PipeWireBackend()) {
            var oldObservation = backend.control(() -> {
                int observed = liveLevel.get();
                reading.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Observation was not released");
                completed.add("old:" + observed);
                return observed;
            });
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            var write = backend.control(() -> {
                liveLevel.set(25);
                completed.add("write:25");
                return null;
            });
            var readback = backend.control(() -> {
                int observed = liveLevel.get();
                completed.add("readback:" + observed);
                return observed;
            });
            assertFalse(write.isDone(), "A mixer write must not overtake an already reading snapshot");
            assertFalse(readback.isDone(), "The post-write observation is a completion barrier");
            release.countDown();

            assertEquals(100, oldObservation.get(5, TimeUnit.SECONDS));
            write.get(5, TimeUnit.SECONDS);
            assertEquals(25, readback.get(5, TimeUnit.SECONDS));
            assertEquals(List.of("old:100", "write:25", "readback:25"), completed);
        } finally {
            release.countDown();
        }
    }

    @Test
    void queuedReversalsAreAppliedInSubmissionOrder() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger liveLevel = new AtomicInteger(60);
        try (PipeWireBackend backend = new PipeWireBackend()) {
            var first = backend.control(() -> {
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Write was not released");
                liveLevel.set(20);
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var reversal = backend.control(() -> { liveLevel.set(60); return null; });
            var readback = backend.control(liveLevel::get);
            release.countDown();

            first.get(5, TimeUnit.SECONDS);
            reversal.get(5, TimeUnit.SECONDS);
            assertEquals(60, readback.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void failedControlDoesNotPoisonLaterObservation() throws Exception {
        try (PipeWireBackend backend = new PipeWireBackend()) {
            var failed = backend.control(() -> { throw new IOException("Disconnected node"); });
            var later = backend.control(() -> 42);

            CompletionException failure = assertThrows(CompletionException.class, failed::join);
            assertInstanceOf(IOException.class, failure.getCause());
            assertEquals(42, later.get(5, TimeUnit.SECONDS));
        }
    }
}
