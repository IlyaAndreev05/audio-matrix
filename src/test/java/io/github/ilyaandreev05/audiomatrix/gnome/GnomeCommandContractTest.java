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
        GnomeControlBridge.Command command = new GnomeControlBridge.Command(3, "reorder-1", "17",
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
    }

    @Test void acceptsRevisionedGraphGainCommandsForSharedPinnedGain() {
        GnomeControlBridge.Command level = new GnomeControlBridge.Command(3, "gain-1", "9",
                "set_graph_gain", "octine-common-vibro\toctine-vibro-gain\t0.4");
        GnomeControlBridge.Command mute = new GnomeControlBridge.Command(3, "gain-2", "9",
                "toggle_graph_gain_mute", "octine-common-vibro\toctine-vibro-gain");
        List<String> received = new ArrayList<>();

        assertEquals("ok", GnomeControlBridge.execute(level, command -> {
            received.add(command.name() + "=" + command.value());
            return CompletableFuture.completedFuture(null);
        }).join().status());
        assertEquals("ok", GnomeControlBridge.execute(mute, command -> {
            received.add(command.name() + "=" + command.value());
            return CompletableFuture.completedFuture(null);
        }).join().status());

        assertEquals(List.of("set_graph_gain=octine-common-vibro\toctine-vibro-gain\t0.4",
                "toggle_graph_gain_mute=octine-common-vibro\toctine-vibro-gain"), received);
        assertTrue(GnomeCommandRevision.isStale(level, 10));
        assertFalse(GnomeCommandRevision.isStale(level, 9));
    }
}
