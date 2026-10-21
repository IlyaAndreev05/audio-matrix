package io.github.ilyaandreev05.audiomatrix.gnome;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GnomeCommandContractTest {
    @Test void acceptsRevisionedGroupReorderingWithoutChangingThePayload() {
        GnomeControlBridge.Command command = new GnomeControlBridge.Command(5, "client-1:reorder-1", "17",
                "reorder_quick_group", "my arbitrary group\t2");
        AtomicBoolean called = new AtomicBoolean();

        assertEquals("ok", GnomeControlBridge.execute(command, received -> {
            assertEquals("my arbitrary group\t2", received.value());
            called.set(true);
            return CompletableFuture.completedFuture(null);
        }).join().status());
        assertTrue(called.get());
        assertTrue(GnomeCommandRevision.isStale(command, 18));
        assertFalse(GnomeCommandRevision.isStale(command, 17));
        GnomeControlBridge.Command missingRevision = new GnomeControlBridge.Command(5,
                "client-1:reorder-2", "", "reorder_quick_group", "my arbitrary group\t1");
        assertTrue(GnomeCommandRevision.isStale(missingRevision, 17));
    }

    @Test void absoluteGraphCommandsAreIdempotentAndDoNotRejectRapidUpdatesAsStale() {
        String volume = "{\"target\":{\"routeId\":\"main\",\"nodeId\":\"octine-vibro-gain\",\"instanceKey\":\"\"},\"value\":0.4}";
        String mute = "{\"target\":{\"routeId\":\"main\",\"nodeId\":\"octine-vibro-gain\",\"instanceKey\":\"\"},\"muted\":true}";
        GnomeControlBridge.Command level = new GnomeControlBridge.Command(5, "client-1:gain-1", "9",
                "setNodeVolume", volume);
        GnomeControlBridge.Command muteCommand = new GnomeControlBridge.Command(5, "client-1:gain-2", "9",
                "setNodeMuted", mute);
        List<String> received = new ArrayList<>();

        assertEquals("ok", GnomeControlBridge.execute(level, command -> {
            received.add(command.name() + "=" + command.value());
            return CompletableFuture.completedFuture(null);
        }).join().status());
        assertEquals("ok", GnomeControlBridge.execute(muteCommand, command -> {
            received.add(command.name() + "=" + command.value());
            return CompletableFuture.completedFuture(null);
        }).join().status());

        assertEquals(List.of("setNodeVolume=" + volume, "setNodeMuted=" + mute), received);
        assertFalse(GnomeCommandRevision.isStale(level, 10));
        assertFalse(GnomeCommandRevision.isStale(muteCommand, 10));
    }
    @Test void absoluteSetterRevisionAllowsDisjointChangesButRejectsOverlapsAndEvictedBaselines() {
        GnomeControlBridge.Command stale = new GnomeControlBridge.Command(5, "client-1:request-1", "9",
                "setNodeVolume", "{\"target\":{\"routeId\":\"main\",\"nodeId\":\"gain\",\"instanceKey\":\"\"},\"value\":0.4}");

        assertDoesNotThrow(() -> GnomeCommandRevision.requireAbsoluteSetterRevision(stale, 10,
                true, 0.2, 0.2, false));
        assertThrows(IllegalStateException.class, () -> GnomeCommandRevision.requireAbsoluteSetterRevision(stale, 10,
                true, 0.2, 0.3, false));
        assertDoesNotThrow(() -> GnomeCommandRevision.requireAbsoluteSetterRevision(stale, 10,
                true, 0.2, 0.3, true));
        assertThrows(IllegalStateException.class, () -> GnomeCommandRevision.requireAbsoluteSetterRevision(stale, 10,
                false, 0.2, 0.2, true));
        assertEquals("client-1", GnomeCommandRevision.clientId(stale));
    }

}
