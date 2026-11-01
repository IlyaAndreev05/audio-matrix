package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireRefreshCoalescerTest {
    @Test
    void aLevelEventShortensAnAlreadyScheduledTopologyDebounce() {
        PipeWireRefreshTestScheduler scheduler = new PipeWireRefreshTestScheduler();
        AtomicInteger refreshes = new AtomicInteger();
        try (PipeWireRefreshCoalescer coalescer = new PipeWireRefreshCoalescer(scheduler,
                () -> CompletableFuture.completedFuture(PipeWireBackend.Snapshot.offline("refresh")),
                snapshot -> refreshes.incrementAndGet())) {
            coalescer.request(false);
            scheduler.advanceBy(50);
            coalescer.request(true);
            assertEquals(1L, scheduler.pendingCount(), "the superseded topology timer must be cancelled");
            scheduler.advanceBy(79);
            assertEquals(0, refreshes.get(), "level changes wait the full 80 ms coalescing window");
            scheduler.advanceBy(1);
            assertEquals(1, refreshes.get(), "a level change must not wait for the 250 ms topology debounce");
            scheduler.advanceBy(120);
            assertEquals(1, refreshes.get(), "the cancelled topology timer must not refresh again");
        }
    }

    @Test
    void aLateLevelEventCannotPostponeAnEarlierTopologyDeadline() {
        PipeWireRefreshTestScheduler scheduler = new PipeWireRefreshTestScheduler();
        AtomicInteger refreshes = new AtomicInteger();
        try (PipeWireRefreshCoalescer coalescer = new PipeWireRefreshCoalescer(scheduler,
                () -> CompletableFuture.completedFuture(PipeWireBackend.Snapshot.offline("refresh")),
                snapshot -> refreshes.incrementAndGet())) {
            coalescer.request(false);
            scheduler.advanceBy(200);
            coalescer.request(true);
            scheduler.advanceBy(49);
            assertEquals(0, refreshes.get());
            scheduler.advanceBy(1);
            assertEquals(1, refreshes.get(), "50 ms remaining on topology is sooner than another 80 ms delay");
            scheduler.advanceBy(100);
            assertEquals(1, refreshes.get());
        }
    }

    @Test
    void aBurstRunsOneRefreshAfter80MillisecondsAndDeliversTheLatestSnapshot() {
        PipeWireRefreshTestScheduler scheduler = new PipeWireRefreshTestScheduler();
        AtomicInteger refreshes = new AtomicInteger();
        AtomicReference<PipeWireBackend.Snapshot> current =
                new AtomicReference<>(PipeWireBackend.Snapshot.offline("original"));
        List<PipeWireBackend.Snapshot> delivered = new ArrayList<>();
        try (PipeWireRefreshCoalescer coalescer = new PipeWireRefreshCoalescer(scheduler,
                () -> { refreshes.incrementAndGet(); return CompletableFuture.completedFuture(current.get()); },
                delivered::add)) {
            for (int index = 0; index < 20; index++) coalescer.request(true);
            scheduler.advanceBy(40);
            PipeWireBackend.Snapshot latest = PipeWireBackend.Snapshot.offline("latest");
            current.set(latest);
            for (int index = 0; index < 20; index++) coalescer.request(true);
            assertEquals(1L, scheduler.pendingCount());
            scheduler.advanceBy(39);
            assertEquals(0, refreshes.get(), "refreshing before 80 ms defeats level-event coalescing");
            scheduler.advanceBy(1);
            assertEquals(1, refreshes.get(), "later requests must not keep postponing the first refresh");
            assertEquals(List.of(latest), delivered);
            scheduler.advanceBy(1000);
            assertEquals(1, refreshes.get());
        }
    }

    @Test
    void requestsDuringAnInflightRefreshProduceAtMostOneDelayedTrailingRefresh() {
        PipeWireRefreshTestScheduler scheduler = new PipeWireRefreshTestScheduler();
        List<CompletableFuture<PipeWireBackend.Snapshot>> refreshes = new ArrayList<>();
        List<PipeWireBackend.Snapshot> delivered = new ArrayList<>();
        try (PipeWireRefreshCoalescer coalescer = new PipeWireRefreshCoalescer(scheduler,
                () -> {
                    CompletableFuture<PipeWireBackend.Snapshot> pending = new CompletableFuture<>();
                    refreshes.add(pending);
                    return pending;
                }, delivered::add)) {
            coalescer.request(true);
            scheduler.advanceBy(80);
            assertEquals(1, refreshes.size());
            for (int index = 0; index < 100; index++) coalescer.request(index % 2 == 0);
            scheduler.advanceBy(1000);
            assertEquals(1, refreshes.size(), "snapshot reads must never overlap");
            assertEquals(0L, scheduler.pendingCount(), "a trailing timer starts only after in-flight completion");
            PipeWireBackend.Snapshot first = PipeWireBackend.Snapshot.offline("first");
            refreshes.getFirst().complete(first);
            assertEquals(List.of(first), delivered);
            assertEquals(1L, scheduler.pendingCount());
            scheduler.advanceBy(79);
            assertEquals(1, refreshes.size());
            scheduler.advanceBy(1);
            assertEquals(2, refreshes.size());
            PipeWireBackend.Snapshot trailing = PipeWireBackend.Snapshot.offline("trailing");
            refreshes.get(1).complete(trailing);
            scheduler.advanceBy(1000);
            assertEquals(2, refreshes.size(), "a burst must not accumulate more than one trailing read");
            assertEquals(List.of(first, trailing), delivered);
            assertEquals(0L, scheduler.pendingCount());
        }
    }

    @Test
    void aFailingListenerDoesNotStopTrailingRefreshes() {
        PipeWireRefreshTestScheduler scheduler = new PipeWireRefreshTestScheduler();
        CompletableFuture<PipeWireBackend.Snapshot> first = new CompletableFuture<>();
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger deliveries = new AtomicInteger();
        try (PipeWireRefreshCoalescer coalescer = new PipeWireRefreshCoalescer(scheduler,
                () -> refreshes.getAndIncrement() == 0 ? first
                        : CompletableFuture.completedFuture(PipeWireBackend.Snapshot.offline("trailing")),
                snapshot -> { deliveries.incrementAndGet(); throw new IllegalStateException("listener"); })) {
            coalescer.request(true);
            scheduler.advanceBy(80);
            coalescer.request(true);
            first.complete(PipeWireBackend.Snapshot.offline("first"));
            assertEquals(1, deliveries.get());
            scheduler.advanceBy(79);
            assertEquals(1, deliveries.get());
            scheduler.advanceBy(1);
            assertEquals(2, deliveries.get(), "scheduling must survive a failing listener");
            assertEquals(2, refreshes.get());
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
