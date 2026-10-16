package io.github.ilyaandreev05.audiomatrix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InstanceControlServerTest {
    @TempDir Path temporaryDirectory;

    @Test void forwardsOpenToTheExistingPerUserInstance() throws Exception {
        Path socket = temporaryDirectory.resolve("instance.sock");
        InstanceControlServer first = InstanceControlServer.acquireOrForward(socket, "open");
        assertNotNull(first);
        try (first) {
            List<String> received = new CopyOnWriteArrayList<>();
            CountDownLatch handled = new CountDownLatch(1);
            first.setHandler(command -> { received.add(command); handled.countDown(); });
            assertNull(InstanceControlServer.acquireOrForward(socket, "open"));
            assertTrue(handled.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("open"), received);
        }
        assertTrue(Files.exists(socket));
    }

    @Test void removesAnAbandonedSocketWhenItsOwnerLockIsFree() throws Exception {
        Path socket = temporaryDirectory.resolve("instance.sock");
        Files.writeString(socket, "stale");
        try (InstanceControlServer server = InstanceControlServer.acquireOrForward(socket, "open")) {
            assertNotNull(server);
            assertTrue(Files.exists(socket));
            assertFalse(Files.isRegularFile(socket));
        }
        try (InstanceControlServer restarted = InstanceControlServer.acquireOrForward(socket, "open")) {
            assertNotNull(restarted);
            assertFalse(Files.isRegularFile(socket));
        }
    }

    @Test void rejectsUnknownCommandsWithoutClaimingAnInstance() throws Exception {
        Path socket = temporaryDirectory.resolve("instance.sock");
        assertThrows(IllegalArgumentException.class, () -> InstanceControlServer.acquireOrForward(socket, "launch-another"));
        assertFalse(Files.exists(socket));
    }
}
