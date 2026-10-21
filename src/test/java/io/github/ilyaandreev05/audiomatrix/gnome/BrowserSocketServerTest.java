package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BrowserSocketServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INSTANCE = "instance-7";
    private static final String PROFILE = "profile-2";
    private static final String TOKEN = "session-9";
    @TempDir Path temporaryDirectory;

    @Test void choosesSessionRuntimeDirectoryOrUidFallback() {
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        Path expected = runtime == null || runtime.isBlank()
                ? null : Path.of(runtime, "audio-matrix", "browser.sock");
        Path actual = BrowserSocketServer.defaultSocketPath();
        if (expected != null) assertEquals(expected, actual);
        else assertTrue(actual.toString().matches("/run/user/[0-9]+/audio-matrix/browser\\.sock"));
    }

    @Test void bindsOwnerOnlySocketAndParentAndKeepsUrlOutOfPublishedState() throws Exception {
        Path socketPath = temporaryDirectory.resolve("audio-matrix/browser.sock");
        try (BrowserSocketServer server = BrowserSocketServer.start(socketPath)) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(socketPath.getParent()));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(socketPath));
            List<BrowserAdapter.BrowserEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch arrived = new CountDownLatch(1);
            server.addListener(event -> { events.add(event); arrived.countDown(); });
            try (Connection connection = connect(socketPath)) {
                connection.send(snapshot("snapshot", List.of(tab(TOKEN, 41, "https://private.example/path", false, false, "none", ""))));
                assertTrue(arrived.await(2, TimeUnit.SECONDS));
                BrowserAdapter.BrowserTab received = events.getFirst().tabs().getFirst();
                assertEquals("[link]", received.title());
                assertFalse(received.title().contains("https://"));
                assertFalse(JSON.writeValueAsString(events).contains("private.example"));
            }
        }
        assertFalse(Files.exists(socketPath));
    }

    @Test void requiresSnapshotBeforeEventsAndRetainsStableSessionIdentityAcrossReconnect() throws Exception {
        Path socketPath = temporaryDirectory.resolve("browser.sock");
        try (BrowserSocketServer server = BrowserSocketServer.start(socketPath)) {
            List<BrowserAdapter.BrowserEvent> events = new CopyOnWriteArrayList<>();
            server.addListener(events::add);
            try (Connection invalid = connect(socketPath)) {
                invalid.send(snapshot("tab_created", List.of(tab(TOKEN, 7, "Tab", false, false, "none", ""))));
                assertEquals(-1, invalid.input.read());
            }
            assertTrue(events.isEmpty());

            try (Connection first = connect(socketPath)) {
                first.send(snapshot("snapshot", List.of(tab(TOKEN, 7, "Tab", true, false, "none", ""),
                        tab("session-10", 8, "Other", false, false, "none", ""))));
                awaitEvents(events, 1);
                first.send(event("tab_updated", tab(TOKEN, 7, "Renamed", true, false, "none", "")));
                awaitEvents(events, 2);
                first.send(event("tab_removed", tab(TOKEN, 7, "Renamed", true, false, "none", "")));
                awaitEvents(events, 3);
                assertEquals(TOKEN, events.get(2).tabs().getFirst().sessionToken());
                assertEquals(7, events.get(2).tabs().getFirst().tabId());
            }
            awaitEvents(events, 4);
            assertEquals("session-10", events.get(3).tabs().getFirst().sessionToken());
            assertTrue(server.snapshots().isEmpty());
            assertTrue(server.command(new BrowserAdapter.BrowserCommand(
                    "offline-command", "chrome", INSTANCE, PROFILE, "session-10", "mute", true)).isCompletedExceptionally());
            try (Connection reconnected = connect(socketPath)) {
                reconnected.send(snapshot("snapshot", List.of(tab(TOKEN, 93, "Restored session", false, false, "none", ""))));
                awaitEvents(events, 5);
                assertEquals(List.of("snapshot", "tab_updated", "tab_removed", "disconnected", "snapshot"),
                        events.stream().map(BrowserAdapter.BrowserEvent::type).toList());
                BrowserAdapter.BrowserTab restored = server.snapshots().getFirst().tabs().getFirst();
                assertEquals(TOKEN, restored.sessionToken());
                assertEquals(93, restored.tabId());
            }
        }
    }
    @Test void rejectsMalformedUnsupportedUrlBearingAndOverlongLines() throws Exception {
        Path socketPath = temporaryDirectory.resolve("browser.sock");
        try (BrowserSocketServer server = BrowserSocketServer.start(socketPath)) {
            for (String line : List.of("not-json", snapshot("unsupported", List.of()),
                    snapshot("snapshot", List.of()).replace("\"version\":1", "\"version\":2"),
                    snapshot("snapshot", List.of()).replace("\"tabs\":[]", "\"tabs\":[{\"url\":\"https://private.example\"}]"))) {
                try (Connection connection = connect(socketPath)) {
                    connection.send(line);
                    assertEquals(-1, connection.input.read());
                }
            }
            try (Connection connection = connect(socketPath)) {
                try { connection.send("x".repeat(BrowserSocketServer.MAX_LINE_BYTES + 1)); }
                catch (IOException rejectedAfterOverflow) { }
                assertEquals(-1, connection.input.read());
            }
            assertTrue(server.snapshots().isEmpty());
        }
    }

    @Test void commandsAddressStableSessionAndCompleteOnlyAfterRequestIdAcknowledgement() throws Exception {
        Path socketPath = temporaryDirectory.resolve("browser.sock");
        try (BrowserSocketServer server = BrowserSocketServer.start(socketPath);
             Connection connection = connect(socketPath)) {
            List<BrowserAdapter.BrowserEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch arrived = new CountDownLatch(1);
            server.addListener(event -> { events.add(event); arrived.countDown(); });
            connection.send(snapshot("snapshot", List.of(tab(TOKEN, 7, "Tab", true, false, "captured", "capture-9"))));
            assertTrue(arrived.await(2, TimeUnit.SECONDS));
            CompletableFuture<BrowserAdapter.BrowserCommandAck> pending = server.command(new BrowserAdapter.BrowserCommand(
                    "request-22", "chrome", INSTANCE, PROFILE, TOKEN, "mute", true));
            JsonNode command = JSON.readTree(connection.input.readLine());
            assertEquals("command", command.path("type").asText());
            assertEquals(1, command.path("version").asInt());
            assertEquals("chrome", command.path("browser").asText());
            assertEquals("mute", command.path("command").asText());
            assertTrue(command.path("tabs").isArray());
            assertEquals(0, command.path("tabs").size());
            assertEquals("request-22", command.path("requestId").asText());
            assertEquals(INSTANCE, command.path("instanceId").asText());
            assertEquals(PROFILE, command.path("profileId").asText());
            assertEquals(TOKEN, command.path("sessionToken").asText());
            assertTrue(command.path("muted").asBoolean());
            connection.send(ack("request-22", "ok", ""));
            BrowserAdapter.BrowserCommandAck ack = pending.get(2, TimeUnit.SECONDS);
            assertEquals("request-22", ack.requestId());
            assertEquals("ok", ack.status());
            assertEquals("", ack.error());
            CompletableFuture<BrowserAdapter.BrowserCommandAck> duplicate = server.command(new BrowserAdapter.BrowserCommand(
                    "request-22", "chrome", INSTANCE, PROFILE, TOKEN, "focus", null));
            assertTrue(duplicate.isCompletedExceptionally());
            CompletableFuture<BrowserAdapter.BrowserCommandAck> failed = server.command(new BrowserAdapter.BrowserCommand(
                    "request-23", "chrome", INSTANCE, PROFILE, TOKEN, "focus", null));
            assertEquals("request-23", JSON.readTree(connection.input.readLine()).path("requestId").asText());
            connection.send(ack("request-23", "error", "Tab is unavailable."));
            BrowserAdapter.BrowserCommandAck error = failed.get(2, TimeUnit.SECONDS);
            assertEquals("error", error.status());
            assertEquals("Tab is unavailable.", error.error());
        }
    }

    @Test void capturedCapabilityIsAttachedToSpecificTabNotStreamCount() {
        BrowserAdapter.BrowserTab captured = tab(TOKEN, 7, "Tab", true, false, "captured", "capture-9");
        BrowserAdapter.BrowserTab ordinary = tab("session-10", 8, "Other", true, false, "none", "");
        assertEquals("captured", captured.volumeCapability());
        assertEquals("capture-9", captured.captureStreamKey());
        assertEquals("none", ordinary.volumeCapability());
        assertEquals("", ordinary.captureStreamKey());
        assertFalse((Boolean) new FirefoxBrowserAdapter().capabilities(5, false).get("independentTabVolume"));
        assertFalse((Boolean) new ChromeBrowserAdapter().capabilities(5, false).get("independentTabVolume"));
    }

    private static void awaitEvents(List<?> events, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (events.size() < count && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(count, events.size());
    }

    private static String snapshot(String type, List<BrowserAdapter.BrowserTab> tabs) throws Exception {
        return JSON.writeValueAsString(java.util.Map.of("version", 1, "type", type, "requestId", "event-1",
                "browser", "chrome", "instanceId", INSTANCE, "profileId", PROFILE, "tabs", tabs));
    }

    private static String event(String type, BrowserAdapter.BrowserTab tab) throws Exception {
        return snapshot(type, List.of(tab));
    }

    private static String ack(String requestId, String status, String error) throws Exception {
        return JSON.writeValueAsString(java.util.Map.of("version", 1, "type", "ack", "requestId", requestId,
                "browser", "chrome", "instanceId", INSTANCE, "profileId", PROFILE, "tabs", List.of(),
                "status", status, "error", error));
    }

    private static BrowserAdapter.BrowserTab tab(String token, int tabId, String title, boolean audible,
                                                 boolean muted, String capability, String captureKey) {
        return new BrowserAdapter.BrowserTab(token, tabId, title, audible, muted, capability, captureKey);
    }

    private static Connection connect(Path socket) throws Exception {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(UnixDomainSocketAddress.of(socket));
        return new Connection(channel,
                new BufferedReader(new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8)),
                new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8)));
    }

    private record Connection(SocketChannel channel, BufferedReader input, BufferedWriter output) implements AutoCloseable {
        private void send(String line) throws Exception { output.write(line); output.write('\n'); output.flush(); }
        @Override public void close() throws Exception { channel.close(); }
    }
}
