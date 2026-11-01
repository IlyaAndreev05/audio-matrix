package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.ilyaandreev05.audiomatrix.gnome.GnomeBridgeTestSupport.*;
import static io.github.ilyaandreev05.audiomatrix.gnome.GnomeBridgeVolumeTestSupport.*;

class GnomeBridgeVolumeFailureRecoveryTest {
    @Test void failedThrownOrMissingActionAndConfirmationResultsDoNotPoisonTheQueue() throws Exception {
        List<Failure> failures = List.of(
                new Failure("failed action", () -> CompletableFuture.failedFuture(new IllegalStateException("Runtime failed")),
                        GnomeBridgeVolumeTestSupport::done, "Runtime failed"),
                new Failure("thrown action", () -> { throw new IllegalStateException("Handler threw"); },
                        GnomeBridgeVolumeTestSupport::done, "Handler threw"),
                new Failure("missing action", () -> null, GnomeBridgeVolumeTestSupport::done,
                        "Command handler returned no completion result"),
                new Failure("wrapped action", () -> CompletableFuture.failedFuture(new CompletionException(
                        new CompletionException(new IllegalStateException("Save conflict")))),
                        GnomeBridgeVolumeTestSupport::done, "Save conflict"),
                new Failure("failed confirmation", GnomeBridgeVolumeTestSupport::done,
                        () -> CompletableFuture.failedFuture(new IllegalStateException("Snapshot failed")),
                        "Could not confirm command state: Snapshot failed"),
                new Failure("thrown confirmation", GnomeBridgeVolumeTestSupport::done,
                        () -> { throw new IllegalStateException("Publisher threw"); },
                        "Could not confirm command state: Publisher threw"),
                new Failure("missing confirmation", GnomeBridgeVolumeTestSupport::done, () -> null,
                        "Could not confirm command state: State publisher returned no completion result"));
        for (Failure failure : failures) {
            CompletableFuture<Void> blocked = new CompletableFuture<>();
            try (Fixture fixture = new Fixture(item -> "client:active".equals(item.requestId()) ? blocked
                    : "client:bad".equals(item.requestId()) ? failure.action().get() : done(),
                    item -> "client:bad".equals(item.requestId()) ? failure.confirmation().get() : done())) {
                GnomeControlBridge.Command active = volume("client:active", 0.1);
                GnomeControlBridge.Command bad = volume("client:bad", 0.2);
                GnomeControlBridge.Command discarded = volume("other-client:discarded", 0.4);
                GnomeControlBridge.Command recovered = volume("other-client:recovered", 0.9);
                CompletableFuture<Void> first = fixture.bridge.dispatch(active);
                CompletableFuture<Void> failed = fixture.bridge.dispatch(bad);
                CompletableFuture<Void> superseded = fixture.bridge.dispatch(discarded);
                CompletableFuture<Void> last = fixture.bridge.dispatch(recovered);
                await(superseded);
                fixture.ack(discarded, "error", SUPERSEDED);
                assertFalse(failed.isDone(), failure.label());
                assertFalse(last.isDone(), failure.label());
                blocked.complete(null);
                await(first);
                await(failed);
                await(last);
                assertEquals(List.of(active, bad, recovered), fixture.handled, failure.label());
                assertEquals(List.of(active, bad, recovered), fixture.confirmed, failure.label());
                fixture.ack(active, "ok", "Applied");
                assertVolumeTarget(fixture.ack(bad, "error", failure.error()), "setNodeVolume");
                assertEquals(0.9, fixture.ack(recovered, "ok", "Applied").path("value").asDouble(), 1e-9);
                assertTrue(fixture.signals.isEmpty(), failure.label());
            }
        }
    }

    @Test void supersededCompletionMayReenterDispatchWithoutLosingTheNewestPendingLevel() throws Exception {
        CompletableFuture<Void> blocked = new CompletableFuture<>();
        AtomicReference<CompletableFuture<Void>> reentrant = new AtomicReference<>();
        try (Fixture fixture = new Fixture(ignored -> blocked, ignored -> done())) {
            GnomeControlBridge.Command active = volume("client:active", 0.1);
            GnomeControlBridge.Command old = volume("client:old", 0.2);
            GnomeControlBridge.Command replacement = volume("client:replacement", 0.5);
            GnomeControlBridge.Command newest = volume("client:reentrant", 0.9);
            CompletableFuture<Void> first = fixture.bridge.dispatch(active);
            CompletableFuture<Void> displaced = fixture.bridge.dispatch(old);
            displaced.thenRun(() -> reentrant.set(fixture.bridge.dispatch(newest)));
            CompletableFuture<Void> replaced = fixture.bridge.dispatch(replacement);
            await(displaced);
            await(replaced);
            assertNotNull(reentrant.get(), "superseded completion callbacks are allowed to dispatch synchronously");
            assertFalse(reentrant.get().isDone());
            fixture.ack(old, "error", SUPERSEDED);
            fixture.ack(replacement, "error", SUPERSEDED);
            assertEquals(List.of(active), fixture.handled);
            blocked.complete(null);
            await(first);
            await(reentrant.get());
            assertEquals(List.of(active, newest), fixture.handled);
            assertEquals(List.of(active, newest), fixture.confirmed);
            fixture.ack(active, "ok", "Applied");
            assertEquals(0.9, fixture.ack(newest, "ok", "Applied").path("value").asDouble(), 1e-9);
            assertTrue(fixture.signals.isEmpty());
        }
    }

    private record Failure(String label, Supplier<CompletableFuture<Void>> action,
            Supplier<CompletableFuture<Void>> confirmation, String error) {}
}
