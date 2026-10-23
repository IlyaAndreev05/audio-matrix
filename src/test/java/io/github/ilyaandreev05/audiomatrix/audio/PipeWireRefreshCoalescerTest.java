package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireRefreshCoalescerTest {
    @Test
    void aLevelEventShortensAnAlreadyScheduledTopologyDebounce() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch refreshed = new CountDownLatch(1);
        try (PipeWireRefreshCoalescer coalescer = new PipeWireRefreshCoalescer(scheduler,
                () -> CompletableFuture.completedFuture(PipeWireBackend.Snapshot.offline("refresh")),
                snapshot -> refreshed.countDown())) {
            long started = System.nanoTime();
            coalescer.request(false);
            coalescer.request(true);
            assertTrue(refreshed.await(150, TimeUnit.MILLISECONDS),
                    "A volume event must not wait for the 250 ms topology debounce");
            assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(150));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void aBurstRunsOneRefreshAndDeliversTheLatestSnapshot() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger refreshes = new AtomicInteger();
        List<PipeWireBackend.Snapshot> delivered = new ArrayList<>();
        try (PipeWireRefreshCoalescer coalescer = new PipeWireRefreshCoalescer(scheduler,
                () -> CompletableFuture.completedFuture(PipeWireBackend.Snapshot.offline("refresh")),
                snapshot -> { refreshes.incrementAndGet(); delivered.add(snapshot); })) {
            for (int index = 0; index < 20; index++) coalescer.request(true);
            Thread.sleep(80);
            assertEquals(1, refreshes.get(), "A level burst must coalesce into one snapshot read");
            assertNotNull(delivered.getFirst());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void aFailingListenerDoesNotStopTrailingRefreshes() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger refreshes = new AtomicInteger();
        try (PipeWireRefreshCoalescer coalescer = new PipeWireRefreshCoalescer(scheduler,
                () -> CompletableFuture.completedFuture(PipeWireBackend.Snapshot.offline("refresh")),
                snapshot -> { refreshes.incrementAndGet(); throw new IllegalStateException("listener"); })) {
            coalescer.request(true);
            Thread.sleep(40);
            coalescer.request(true);
            Thread.sleep(80);
            assertTrue(refreshes.get() >= 2, "Scheduling must survive a failing listener");
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void onlyNodeParameterChangesAreClassifiedAsLevels() {
        assertTrue(PipeWireMonitorChange.levelOnly("""
                [{"id":42,"type":"PipeWire:Interface:Node","info":{"change-mask":["params"],
                  "params":{"Props":[{"volume":0.25,"mute":false}]}}}]"""));
        assertFalse(PipeWireMonitorChange.levelOnly("""
                [{"id":42,"type":"PipeWire:Interface:Node","info":{"change-mask":["input-ports","props"],
                  "props":{"media.class":"Stream/Output/Audio"}}}]"""));
        assertFalse(PipeWireMonitorChange.levelOnly("""
                [{"id":42,"type":"PipeWire:Interface:Node","info":null}]"""),
                "A removed node is topology churn");
        assertFalse(PipeWireMonitorChange.levelOnly("""
                [{"id":42,"type":"PipeWire:Interface:Client","info":{"change-mask":["props"]}}]"""),
                "Client-only churn must not be mistaken for a level change");
        assertFalse(PipeWireMonitorChange.levelOnly("not json"));
    }

    @Test
    void monitorSplitsTheInitialDumpAndEachFollowingUpdate() {
        PipeWireMonitorChange change = new PipeWireMonitorChange();
        assertNull(change.accept("["));
        assertNull(change.accept("{\"id\":1}"));
        PipeWireMonitorChange.Update completed = change.accept("]");
        assertNotNull(completed);
        assertTrue(completed.initialCompleted());
        assertNull(completed.payload());
        PipeWireMonitorChange.Update update = change.accept("[{\"id\":42}]");
        assertNotNull(update);
        assertFalse(update.initialCompleted());
        assertEquals("[{\"id\":42}]\n", update.payload());
    }
}
