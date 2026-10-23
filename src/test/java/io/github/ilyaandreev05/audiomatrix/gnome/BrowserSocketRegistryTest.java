package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class BrowserSocketRegistryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INSTANCE = "instance-7";
    private static final String PROFILE = "profile-2";
    private static final String TOKEN = "session-9";
    private static final BrowserAdapter.BrowserTab OTHER = new BrowserAdapter.BrowserTab(
            "session-other", 8, "Other tab", false, false, "none", "");
    private final BrowserSocketRegistry registry = new BrowserSocketRegistry();
    @TempDir Path temporaryDirectory;

    @AfterEach void closeRegistry() { registry.close(); }

    @Test void successfulMutePublishesAuthoritativeTargetBeforeCompletingAndRetainsLatestMetadata() throws Exception {
        try (Connection connection = connect("browser.sock")) {
            BrowserAdapter.BrowserTab original = tab(7, "Original", false, false, "none", "");
            accept(connection, "snapshot", INSTANCE, List.of(original, OTHER));
            CompletableFuture<BrowserAdapter.BrowserCommandAck> pending = registry.command(command("mute-1", true));
            assertFalse(pending.isDone());
            assertEquals(original, knownTab(TOKEN));

            BrowserAdapter.BrowserTab latest = tab(7, "Renamed", true, false, "captured", "capture-9");
            accept(connection, "tab_updated", INSTANCE, List.of(latest));
            List<BrowserAdapter.BrowserEvent> events = new ArrayList<>();
            registry.addListener(events::add);
            CompletableFuture<List<BrowserAdapter.BrowserTab>> stateAtCompletion = pending.thenApply(ack -> {
                assertEquals("ok", ack.status());
                assertEquals(1, events.size());
                return registry.snapshots().getFirst().tabs();
            });

            acknowledge(connection, "mute-1", "ok", "");
            BrowserAdapter.BrowserTab expected = tab(7, "Renamed", true, true, "captured", "capture-9");
            assertEquals(expected, knownTab(TOKEN));
            assertEquals(OTHER, knownTab(OTHER.sessionToken()));
            assertTrue(stateAtCompletion.join().containsAll(List.of(expected, OTHER)));
            assertEquals(List.of(new BrowserAdapter.BrowserEvent("tab_updated", "chrome", INSTANCE, PROFILE,
                    List.of(expected))), events);

            CompletableFuture<BrowserAdapter.BrowserCommandAck> unmute = registry.command(command("mute-2", false));
            assertFalse(unmute.isDone());
            assertTrue(knownTab(TOKEN).muted());
            acknowledge(connection, "mute-2", "ok", "");
            assertEquals("ok", unmute.join().status());
            assertEquals(latest, knownTab(TOKEN));
            assertEquals(OTHER, knownTab(OTHER.sessionToken()));
            assertEquals(List.of(latest), events.get(1).tabs());
        }
    }

    @Test void rejectedMuteDoesNotChangeAnyTabOrPublishAnOptimisticEvent() throws Exception {
        try (Connection connection = connect("browser.sock")) {
            BrowserAdapter.BrowserTab original = tab(7, "Tab", true, false, "none", "");
            accept(connection, "snapshot", INSTANCE, List.of(original, OTHER));
            List<BrowserAdapter.BrowserEvent> events = new ArrayList<>();
            registry.addListener(events::add);
            CompletableFuture<BrowserAdapter.BrowserCommandAck> pending = registry.command(command("mute-error", true));
            assertFalse(pending.isDone());
            assertTrue(events.isEmpty());

            acknowledge(connection, "mute-error", "error", "Tab is unavailable.");

            assertEquals(new BrowserAdapter.BrowserCommandAck("mute-error", "error", "Tab is unavailable."), pending.join());
            assertEquals(original, knownTab(TOKEN));
            assertEquals(OTHER, knownTab(OTHER.sessionToken()));
            assertTrue(events.isEmpty());
        }
    }

    @Test void successfulLateAcknowledgementNeverResurrectsARemovedTab() throws Exception {
        try (Connection connection = connect("browser.sock")) {
            BrowserAdapter.BrowserTab original = tab(7, "Tab", true, false, "none", "");
            accept(connection, "snapshot", INSTANCE, List.of(original, OTHER));
            CompletableFuture<BrowserAdapter.BrowserCommandAck> pending = registry.command(command("removed-tab", true));
            accept(connection, "tab_removed", INSTANCE, List.of(original));
            List<BrowserAdapter.BrowserEvent> events = new ArrayList<>();
            registry.addListener(events::add);

            acknowledge(connection, "removed-tab", "ok", "");

            assertInstanceOf(IOException.class, assertThrows(CompletionException.class, pending::join).getCause());
            assertEquals(List.of(OTHER), registry.snapshots().getFirst().tabs());
            assertTrue(events.isEmpty());
        }
    }

    @Test void replacementFailsOldPendingAndCannotApplyItsDesiredStateToTheNewConnection() throws Exception {
        try (Connection previous = connect("previous.sock"); Connection replacement = connect("replacement.sock")) {
            accept(previous, "snapshot", INSTANCE, List.of(tab(7, "Old tab", true, false, "none", "")));
            CompletableFuture<BrowserAdapter.BrowserCommandAck> old = registry.command(command("reused-request", true));
            BrowserAdapter.BrowserTab next = tab(93, "New session", false, false, "none", "");
            accept(replacement, "snapshot", INSTANCE, List.of(next, OTHER));

            assertTrue(previous.client().disconnected);
            assertInstanceOf(IOException.class, assertThrows(CompletionException.class, old::join).getCause());
            CompletableFuture<BrowserAdapter.BrowserCommandAck> current = registry.command(command("reused-request", false));
            List<BrowserAdapter.BrowserEvent> events = new ArrayList<>();
            registry.addListener(events::add);
            assertThrows(IOException.class, () -> acknowledge(previous, "reused-request", "ok", ""));
            assertFalse(current.isDone());
            assertEquals(next, knownTab(TOKEN));
            assertTrue(events.isEmpty());

            acknowledge(replacement, "reused-request", "ok", "");
            assertEquals("ok", current.join().status());
            assertEquals(next, knownTab(TOKEN));
            assertEquals(OTHER, knownTab(OTHER.sessionToken()));
            assertEquals(List.of(next), events.getFirst().tabs());
        }
    }

    @Test void disconnectFailsPendingAndLateAcknowledgementDoesNotTouchReconnectedTab() throws Exception {
        try (Connection previous = connect("previous.sock"); Connection replacement = connect("replacement.sock")) {
            accept(previous, "snapshot", INSTANCE, List.of(tab(7, "Old tab", true, false, "none", "")));
            CompletableFuture<BrowserAdapter.BrowserCommandAck> pending = registry.command(command("offline", true));
            registry.disconnect(previous.client());
            assertInstanceOf(IOException.class, assertThrows(CompletionException.class, pending::join).getCause());
            assertTrue(registry.snapshots().isEmpty());
            BrowserAdapter.BrowserTab restored = tab(93, "Restored tab", true, false, "none", "");
            accept(replacement, "snapshot", INSTANCE, List.of(restored, OTHER));
            List<BrowserAdapter.BrowserEvent> events = new ArrayList<>();
            registry.addListener(events::add);

            acknowledge(previous, "offline", "ok", "");

            assertEquals(restored, knownTab(TOKEN));
            assertEquals(OTHER, knownTab(OTHER.sessionToken()));
            assertTrue(events.isEmpty());
        }
    }

    @Test void acknowledgementFromAnotherLiveBrowserInstanceCannotCompleteOrMutateTheTarget() throws Exception {
        try (Connection target = connect("target.sock"); Connection other = connect("other.sock")) {
            BrowserAdapter.BrowserTab original = tab(7, "Tab", true, false, "none", "");
            accept(target, "snapshot", INSTANCE, List.of(original));
            accept(other, "snapshot", "other-instance", List.of(OTHER));
            CompletableFuture<BrowserAdapter.BrowserCommandAck> pending = registry.command(command("owned-request", true));

            assertThrows(IOException.class, () -> acknowledge(other, "owned-request", "ok", ""));
            assertFalse(pending.isDone());
            assertEquals(original, knownTab(TOKEN));
            assertEquals(OTHER, knownTab(OTHER.sessionToken()));
            acknowledge(target, "owned-request", "ok", "");
            assertEquals("ok", pending.join().status());
            assertTrue(knownTab(TOKEN).muted());
            assertEquals(OTHER, knownTab(OTHER.sessionToken()));
        }
    }

    @Test void rejectsMalformedUnsupportedAndUnavailableCommandTargetsWithoutChangingState() throws Exception {
        try (Connection connection = connect("browser.sock")) {
            BrowserAdapter.BrowserTab original = tab(7, "Tab", true, false, "none", "");
            accept(connection, "snapshot", INSTANCE, List.of(original));
            List<BrowserAdapter.BrowserEvent> events = new ArrayList<>();
            registry.addListener(events::add);
            for (BrowserAdapter.BrowserCommand invalid : List.of(
                    new BrowserAdapter.BrowserCommand("bad-browser", "unknown", INSTANCE, PROFILE, TOKEN, "mute", true),
                    new BrowserAdapter.BrowserCommand("bad-instance", "chrome", " ", PROFILE, TOKEN, "mute", true),
                    new BrowserAdapter.BrowserCommand("bad-profile", "chrome", INSTANCE, "", TOKEN, "mute", true),
                    new BrowserAdapter.BrowserCommand("bad-token", "chrome", INSTANCE, PROFILE, "not/a/token", "mute", true),
                    new BrowserAdapter.BrowserCommand("missing-state", "chrome", INSTANCE, PROFILE, TOKEN, "mute", null),
                    new BrowserAdapter.BrowserCommand("missing-instance", "chrome", "absent", PROFILE, TOKEN, "mute", true),
                    new BrowserAdapter.BrowserCommand("missing-profile", "chrome", INSTANCE, "absent", TOKEN, "mute", true),
                    new BrowserAdapter.BrowserCommand("missing-tab", "chrome", INSTANCE, PROFILE, "absent", "mute", true))) {
                assertThrows(CompletionException.class, () -> registry.command(invalid).join(), invalid.toString());
            }
            assertEquals(original, knownTab(TOKEN));
            assertTrue(events.isEmpty());
        }
    }

    private BrowserAdapter.BrowserTab knownTab(String token) {
        return registry.snapshots().stream().flatMap(snapshot -> snapshot.tabs().stream())
                .filter(tab -> token.equals(tab.sessionToken())).findFirst().orElseThrow();
    }

    private static BrowserAdapter.BrowserTab tab(int id, String title, boolean audible, boolean muted,
                                                  String capability, String captureKey) {
        return new BrowserAdapter.BrowserTab(TOKEN, id, title, audible, muted, capability, captureKey);
    }

    private static BrowserAdapter.BrowserCommand command(String requestId, boolean muted) {
        return new BrowserAdapter.BrowserCommand(requestId, "chrome", INSTANCE, PROFILE, TOKEN, "mute", muted);
    }

    private void accept(Connection connection, String type, String instance, List<BrowserAdapter.BrowserTab> tabs) throws Exception {
        accept(connection, type, "chrome", instance, tabs);
    }

    private void accept(Connection connection, String type, String browser, String instance,
                        List<BrowserAdapter.BrowserTab> tabs) throws Exception {
        registry.accept(connection.client(), BrowserSocketProtocol.parse(JSON.writeValueAsString(Map.of(
                "version", 1, "type", type, "requestId", "event-1", "browser", browser,
                "instanceId", instance, "profileId", PROFILE, "tabs", tabs))));
    }

    private void acknowledge(Connection connection, String requestId, String status, String error) throws Exception {
        BrowserSocketRegistry.Key key = connection.client().key;
        registry.accept(connection.client(), BrowserSocketProtocol.parse(JSON.writeValueAsString(Map.of(
                "version", 1, "type", "ack", "requestId", requestId, "browser", key.browser(),
                "instanceId", key.instanceId(), "profileId", key.profileId(), "tabs", List.of(),
                "status", status, "error", error))));
    }

    private Connection connect(String filename) throws IOException {
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(temporaryDirectory.resolve(filename));
        try (ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            listener.bind(address);
            SocketChannel browser = SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                browser.connect(address);
                return new Connection(new BrowserSocketClient(listener.accept()), browser);
            } catch (IOException failure) {
                browser.close();
                throw failure;
            }
        }
    }

    private record Connection(BrowserSocketClient client, SocketChannel browser) implements AutoCloseable {
        @Override public void close() throws IOException {
            client.close();
            browser.close();
        }
    }
}
