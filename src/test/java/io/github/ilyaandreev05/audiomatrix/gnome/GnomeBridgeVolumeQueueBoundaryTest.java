package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.ilyaandreev05.audiomatrix.gnome.GnomeBridgeTestSupport.*;
import static io.github.ilyaandreev05.audiomatrix.gnome.GnomeBridgeVolumeTestSupport.*;

class GnomeBridgeVolumeQueueBoundaryTest {
    @Test void oneActivePlus128PendingAllowsTailReplacementAndRejectsAdditionalWorkWithoutMutation() throws Exception {
        assertEquals(128, GnomeAudioCommandQueue.MAX_PENDING);
        CompletableFuture<Void> activeApplied = new CompletableFuture<>();
        CompletableFuture<Void> windowShown = new CompletableFuture<>();
        try (Fixture fixture = new Fixture(item -> "open".equals(item.name()) ? windowShown
                : "client:active".equals(item.requestId()) ? activeApplied : done(), ignored -> done())) {
            GnomeControlBridge.Command active = volume("client:active", 0.1);
            CompletableFuture<Void> first = fixture.bridge.dispatch(active);
            List<GnomeControlBridge.Command> fifo = new ArrayList<>();
            List<CompletableFuture<Void>> pending = new ArrayList<>();
            for (int index = 0; index < 127; index++) {
                GnomeControlBridge.Command item = command("client:barrier-" + index, "select_route", "route-" + index);
                fifo.add(item);
                pending.add(fixture.bridge.dispatch(item));
            }
            GnomeControlBridge.Command old = volume("client:old-tail", 0.2);
            CompletableFuture<Void> oldCompletion = fixture.bridge.dispatch(old);
            assertFalse(first.isDone());
            for (CompletableFuture<Void> completion : pending) assertFalse(completion.isDone());
            assertFalse(oldCompletion.isDone(), "the 128th pending slot must be available besides the active slot");
            assertTrue(fixture.signals.isEmpty());

            GnomeControlBridge.Command replacement = volume("client:replacement", 0.6);
            CompletableFuture<Void> replaced = fixture.bridge.dispatch(replacement);
            await(oldCompletion);
            assertVolumeTarget(fixture.ack(old, "error", SUPERSEDED), "setNodeVolume");
            assertFalse(replaced.isDone(), "replacement is legal even when all pending slots are occupied");
            GnomeControlBridge.Command overflow = command("client:overflow", "select_route", "must-not-apply");
            await(fixture.bridge.dispatch(overflow));
            fixture.ack(overflow, "error", FULL);

            GnomeControlBridge.Command latest = volume("client:latest", 1.0);
            CompletableFuture<Void> last = fixture.bridge.dispatch(latest);
            await(replaced);
            fixture.ack(replacement, "error", SUPERSEDED);
            assertFalse(last.isDone(), "rejection must not append a barrier or disturb the replaceable tail");
            GnomeControlBridge.Command otherClient = volume("other-client:overflow", 0.9);
            await(fixture.bridge.dispatch(otherClient));
            assertVolumeTarget(fixture.ack(otherClient, "error", FULL), "setNodeVolume");
            assertEquals(List.of(active), fixture.handled, "no rejected or superseded operation may mutate audio/config");
            assertTrue(fixture.confirmed.isEmpty());

            GnomeControlBridge.Command presentation = open("client:open-at-capacity");
            CompletableFuture<Void> openCompletion = fixture.bridge.dispatch(presentation);
            assertEquals(List.of(active, presentation), fixture.handled, "presentation bypasses both the FIFO and capacity");
            assertFalse(openCompletion.isDone());
            fifo.add(latest);
            activeApplied.complete(null);
            await(first);
            for (CompletableFuture<Void> completion : pending) await(completion);
            await(last);
            assertFalse(openCompletion.isDone(), "pending presentation must not hold any of the audio slots");
            List<GnomeControlBridge.Command> expectedHandled = new ArrayList<>(List.of(active, presentation));
            expectedHandled.addAll(fifo);
            assertEquals(expectedHandled, fixture.handled);
            List<GnomeControlBridge.Command> expectedConfirmed = new ArrayList<>(List.of(active));
            expectedConfirmed.addAll(fifo);
            assertEquals(expectedConfirmed, fixture.confirmed);
            fixture.ack(active, "ok", "Applied");
            for (GnomeControlBridge.Command item : fifo) fixture.ack(item, "ok", "Applied");
            assertTrue(fixture.signals.isEmpty());

            windowShown.complete(null);
            await(openCompletion);
            fixture.ack(presentation, "ok", "Applied");
            GnomeControlBridge.Command recovered = volume("client:after-drain", 0.4);
            await(fixture.bridge.dispatch(recovered));
            fixture.ack(recovered, "ok", "Applied");
            assertEquals(recovered, fixture.handled.getLast(), "draining the queue restores capacity");
            assertTrue(fixture.signals.isEmpty());
        }
    }

    @Test void closeResolvesActivePendingAndPresentationWhileRuntimeOrConfirmationIsBlocked() throws Exception {
        for (boolean duringConfirmation : List.of(false, true)) {
            CompletableFuture<Void> runtime = new CompletableFuture<>();
            CompletableFuture<Void> observed = new CompletableFuture<>();
            CompletableFuture<Void> windowShown = new CompletableFuture<>();
            if (duringConfirmation) runtime.complete(null);
            try (Fixture fixture = new Fixture(item -> "open".equals(item.name()) ? windowShown
                    : "client:active".equals(item.requestId()) ? runtime : done(),
                    item -> "client:active".equals(item.requestId()) ? observed : done())) {
                GnomeControlBridge.Command active = volume("client:active", 0.2);
                GnomeControlBridge.Command pending = volume("client:pending", 0.4);
                GnomeControlBridge.Command structure = command("client:structure", "select_route", "next-route");
                GnomeControlBridge.Command presentation = open("client:open");
                List<CompletableFuture<Void>> completions = List.of(fixture.bridge.dispatch(active),
                        fixture.bridge.dispatch(pending), fixture.bridge.dispatch(structure), fixture.bridge.dispatch(presentation));
                assertEquals(List.of(active, presentation), fixture.handled);
                for (CompletableFuture<Void> completion : completions) assertFalse(completion.isDone());
                assertEquals(duringConfirmation ? List.of(active) : List.of(), fixture.confirmed);
                assertTrue(fixture.signals.isEmpty());

                fixture.bridge.close();
                for (CompletableFuture<Void> completion : completions) await(completion);
                runtime.complete(null);
                observed.complete(null);
                windowShown.complete(null);
                assertEquals(List.of(active, presentation), fixture.handled, "late completion must never restart pending audio");
                await(fixture.bridge.dispatch(volume("client:after-close", 0.8)));
                await(fixture.bridge.dispatch(open("client:open-after-close")));
                assertEquals(List.of(active, presentation), fixture.handled);
                assertTrue(fixture.signals.isEmpty(), "closing suppresses late State/ACK/Result publication");
            }
        }
    }
}
