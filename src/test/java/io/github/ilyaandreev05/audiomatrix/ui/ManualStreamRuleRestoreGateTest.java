package io.github.ilyaandreev05.audiomatrix.ui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ManualStreamRuleRestoreGateTest {
    @Test
    void failureReleaseDrainsAStartupRestoreAndASecondPendingRestoreBeforeSealing() {
        ManualStreamRuleRestoreGate restoreGate = new ManualStreamRuleRestoreGate();
        CompletableFuture<Void> startupRestore = new CompletableFuture<>();
        CompletableFuture<Void> secondRestore = new CompletableFuture<>();
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        AtomicInteger immediateRestores = new AtomicInteger();
        Supplier<CompletableFuture<Void>> restore = () ->
                restores.incrementAndGet() == 1 ? startupRestore : secondRestore;
        restoreGate.restoreOrDefer(true, restore).join();

        CompletableFuture<Void> released = restoreGate.releaseAfterFailure(
                () -> releases.incrementAndGet(), restore);
        assertFalse(released.isDone(), "The release must wait for the already-started deferred restore");
        assertEquals(1, restores.get());
        restoreGate.restoreOrDefer(false, restore).join();
        assertEquals(1, restores.get(), "A restore deferred while the barrier is open must wait");

        startupRestore.complete(null);
        assertEquals(2, restores.get(), "The pending restore starts after the first tail completes");
        assertFalse(released.isDone(), "The release must wait for the second restore tail too");
        secondRestore.complete(null);
        released.join();

        assertEquals(1, releases.get());
        assertEquals(2, restores.get());
        restoreGate.restoreOrDefer(false, () -> {
            immediateRestores.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }).join();
        assertEquals(1, immediateRestores.get(), "The barrier must be sealed after every restore tail");
    }

    @Test
    void failureReleaseDrainsDeferredRestoreOnceAndSealsTheBarrierForTheNextCommand() {
        ManualStreamRuleRestoreGate restoreGate = new ManualStreamRuleRestoreGate();
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger deferredRestores = new AtomicInteger();
        AtomicInteger immediateRestores = new AtomicInteger();
        restoreGate.restoreOrDefer(true, () -> {
            deferredRestores.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }).join();
        assertEquals(0, deferredRestores.get());

        restoreGate.releaseAfterFailure(() -> releases.incrementAndGet(), () -> {
            deferredRestores.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }).join();
        assertEquals(1, releases.get());
        assertEquals(1, deferredRestores.get(), "A deferred manual restore is drained once on release");

        restoreGate.restoreOrDefer(false, () -> {
            immediateRestores.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }).join();
        assertEquals(1, immediateRestores.get(), "The next command must not stay deferred behind the failed one");
    }
}
