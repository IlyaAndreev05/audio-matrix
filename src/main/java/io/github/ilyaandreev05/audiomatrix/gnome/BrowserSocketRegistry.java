package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Reconciles complete snapshots, tab deltas, and request-correlated browser commands. */
final class BrowserSocketRegistry {
    private static final long COMMAND_TIMEOUT_SECONDS = 5;
    private static final Set<String> EVENT_TYPES = Set.of("tab_created", "tab_removed", "tab_updated", "tab_audio", "window_updated");
    private static final Set<String> COMMANDS = Set.of("mute", "focus", "stopCapture");
    private final Map<Key, BrowserSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<BrowserAdapter.BrowserEvent>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    synchronized List<BrowserAdapter.BrowserSnapshot> snapshots() {
        List<BrowserAdapter.BrowserSnapshot> result = new ArrayList<>();
        sessions.forEach((key, session) -> {
            if (session.client != null && !session.client.disconnected)
                result.add(new BrowserAdapter.BrowserSnapshot(key.browser(), key.instanceId(), key.profileId(), List.copyOf(session.tabs.values())));
        });
        return List.copyOf(result);
    }

    void addListener(Consumer<BrowserAdapter.BrowserEvent> listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    void removeListener(Consumer<BrowserAdapter.BrowserEvent> listener) { listeners.remove(listener); }

    CompletableFuture<BrowserAdapter.BrowserCommandAck> command(BrowserAdapter.BrowserCommand command) {
        if (closed) return CompletableFuture.failedFuture(new IOException("The browser socket is closed."));
        if (command == null || !BrowserSocketProtocol.isSupportedBrowser(command.browser()) || command.command() == null
                || !COMMANDS.contains(command.command())
                || ("firefox".equals(command.browser()) && "stopCapture".equals(command.command())))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported browser command."));
        if (!BrowserSocketProtocol.isSafeKey(command.instanceId())
                || !BrowserSocketProtocol.isSafeKey(command.profileId())
                || !BrowserSocketProtocol.isSafeKey(command.sessionToken()))
            return CompletableFuture.failedFuture(new IllegalArgumentException("The browser command target is invalid."));
        if ("mute".equals(command.command()) && command.muted() == null)
            return CompletableFuture.failedFuture(new IllegalArgumentException("A mute command requires a muted value."));
        if (!"mute".equals(command.command()) && command.muted() != null)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Only a mute command accepts a muted value."));
        String requestId = empty(command.requestId()) ? UUID.randomUUID().toString() : command.requestId();
        if (!requestId.matches("[A-Za-z0-9._:-]{1,256}"))
            return CompletableFuture.failedFuture(new IllegalArgumentException("The browser command requestId is invalid."));
        Key key = new Key(command.browser(), command.instanceId(), command.profileId());
        CompletableFuture<BrowserAdapter.BrowserCommandAck> result = new CompletableFuture<>();
        BrowserSocketClient client;
        PendingCommand pending;
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(new IOException("The browser socket is closed."));
            BrowserSession session = sessions.get(key);
            client = session == null ? null : session.client;
            if (client == null || client.disconnected || !session.tabs.containsKey(command.sessionToken()))
                return CompletableFuture.failedFuture(new IOException("The browser tab is unavailable."));
            if (!client.rememberCommandRequestId(requestId))
                return CompletableFuture.failedFuture(new IllegalArgumentException("The browser command requestId was already used on this connection."));
            pending = new PendingCommand(key, session, client, command.sessionToken(), command.muted(), result);
            if (pendingCommands.putIfAbsent(requestId, pending) != null)
                return CompletableFuture.failedFuture(new IllegalArgumentException("The browser command requestId is already in use."));
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("version", BrowserSocketProtocol.PROTOCOL_VERSION);
        message.put("type", "command");
        message.put("requestId", requestId);
        message.put("browser", command.browser());
        message.put("instanceId", command.instanceId());
        message.put("profileId", command.profileId());
        message.put("tabs", List.of());
        message.put("sessionToken", command.sessionToken());
        message.put("command", command.command());
        if (command.muted() != null) message.put("muted", command.muted());
        try { client.send(message); }
        catch (IOException failure) {
            pendingCommands.remove(requestId, pending);
            result.completeExceptionally(failure);
            return result;
        }
        CompletableFuture.delayedExecutor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute(() -> {
            if (pendingCommands.remove(requestId, pending)) result.completeExceptionally(new IOException("The browser command timed out."));
        });
        return result;
    }

    synchronized void accept(BrowserSocketClient client, JsonNode root) throws IOException {
        if (closed || client.disconnected) throw new IOException("The browser connection is closed.");
        String type = BrowserSocketProtocol.text(root, "type");
        if (!client.snapshotReceived) {
            if (!"snapshot".equals(type)) throw new BrowserSocketProtocol.ProtocolException("A complete snapshot is required first.");
            acceptSnapshot(client, root);
            return;
        }
        if ("snapshot".equals(type)) { acceptSnapshot(client, root); return; }
        if ("ack".equals(type)) { acceptAck(client, root); return; }
        if (!EVENT_TYPES.contains(type)) throw new BrowserSocketProtocol.ProtocolException("Unsupported browser message type.");
        BrowserSocketProtocol.requireFields(root, Set.of("version", "type", "requestId", "browser", "instanceId", "profileId", "tabs"));
        Key key = BrowserSocketProtocol.key(root);
        if (!key.equals(client.key)) throw new BrowserSocketProtocol.ProtocolException("A browser connection changed identity.");
        List<BrowserAdapter.BrowserTab> tabs = BrowserSocketProtocol.tabs(root.path("tabs"), key.browser());
        if (tabs.isEmpty() || ("window_updated".equals(type) ? tabs.size() > 256 : tabs.size() != 1))
            throw new BrowserSocketProtocol.ProtocolException("A browser event must include its affected tab row.");
        BrowserSession session = sessions.get(key);
        if (session == null || session.client != client) throw new BrowserSocketProtocol.ProtocolException("The browser instance is no longer active.");
        if ("tab_removed".equals(type)) tabs.forEach(tab -> session.tabs.remove(tab.sessionToken()));
        else tabs.forEach(tab -> session.tabs.put(tab.sessionToken(), tab));
        publish(new BrowserAdapter.BrowserEvent(type, key.browser(), key.instanceId(), key.profileId(), tabs));
    }

    private void acceptSnapshot(BrowserSocketClient client, JsonNode root) throws IOException {
        BrowserSocketProtocol.requireFields(root, Set.of("version", "type", "requestId", "browser", "instanceId", "profileId", "tabs"));
        Key key = BrowserSocketProtocol.key(root);
        if (client.key != null && !key.equals(client.key))
            throw new BrowserSocketProtocol.ProtocolException("A browser connection changed identity.");
        List<BrowserAdapter.BrowserTab> tabs = BrowserSocketProtocol.tabs(root.path("tabs"), key.browser());
        BrowserSession next = sessions.computeIfAbsent(key, ignored -> new BrowserSession());
        BrowserSocketClient previous = next.client;
        if (previous != null && previous != client) {
            previous.supersede();
            failPendingCommands(previous);
        }
        next.tabs.clear();
        tabs.forEach(tab -> next.tabs.put(tab.sessionToken(), tab));
        next.client = client;
        client.key = key;
        client.snapshotReceived = true;
        publish(new BrowserAdapter.BrowserEvent("snapshot", key.browser(), key.instanceId(), key.profileId(), tabs));
    }

    private void acceptAck(BrowserSocketClient client, JsonNode root) throws IOException {
        BrowserSocketProtocol.requireFields(root, Set.of("version", "type", "requestId", "browser", "instanceId", "profileId", "tabs", "status", "error"));
        Key key = BrowserSocketProtocol.key(root);
        if (!key.equals(client.key) || !root.path("tabs").isArray() || !root.path("tabs").isEmpty())
            throw new BrowserSocketProtocol.ProtocolException("The browser acknowledgement target is invalid.");
        String requestId = BrowserSocketProtocol.safeKey(BrowserSocketProtocol.text(root, "requestId"), "requestId");
        String status = BrowserSocketProtocol.text(root, "status");
        if (!Set.of("ok", "error").contains(status)) throw new BrowserSocketProtocol.ProtocolException("The browser acknowledgement status is invalid.");
        String error = BrowserSocketProtocol.stripUrls(BrowserSocketProtocol.text(root, "error"));
        if (("ok".equals(status) && !error.isEmpty()) || ("error".equals(status) && error.isBlank()))
            throw new BrowserSocketProtocol.ProtocolException("The browser acknowledgement status and error disagree.");
        PendingCommand pending = pendingCommands.get(requestId);
        if (pending == null) return; // A timed-out or already acknowledged request may reply late.
        if (pending.client() != client) throw new BrowserSocketProtocol.ProtocolException("The browser acknowledgement belongs to another connection.");
        if (pendingCommands.remove(requestId, pending)) {
            BrowserSession session = sessions.get(pending.key());
            if (closed || client.disconnected || session != pending.session() || session.client != client) {
                pending.future().completeExceptionally(new IOException("The browser command session is no longer active."));
                return;
            }
            if ("ok".equals(status) && pending.muted() != null) {
                BrowserAdapter.BrowserTab tab = session.tabs.get(pending.sessionToken());
                if (tab == null) {
                    pending.future().completeExceptionally(new IOException("The browser tab is unavailable."));
                    return;
                }
                BrowserAdapter.BrowserTab confirmed = new BrowserAdapter.BrowserTab(tab.sessionToken(), tab.tabId(),
                        tab.title(), tab.audible(), pending.muted(), tab.volumeCapability(), tab.captureStreamKey());
                session.tabs.put(pending.sessionToken(), confirmed);
                publish(new BrowserAdapter.BrowserEvent("tab_updated", key.browser(), key.instanceId(), key.profileId(),
                        List.of(confirmed)));
            }
            pending.future().complete(new BrowserAdapter.BrowserCommandAck(requestId, status, error));
        }
    }

    synchronized void disconnect(BrowserSocketClient client) {
        failPendingCommands(client);
        Key key = client.key;
        if (key == null) return;
        BrowserSession session = sessions.get(key);
        if (session != null && session.client == client) {
            session.client = null;
            publish(new BrowserAdapter.BrowserEvent("disconnected", key.browser(), key.instanceId(), key.profileId(),
                    List.copyOf(session.tabs.values())));
        }
    }

    private void failPendingCommands(BrowserSocketClient client) {
        for (Map.Entry<String, PendingCommand> entry : pendingCommands.entrySet()) {
            PendingCommand pending = entry.getValue();
            if (pending.client() == client && pendingCommands.remove(entry.getKey(), pending))
                pending.future().completeExceptionally(new IOException("The browser disconnected before acknowledging the command."));
        }
    }

    synchronized void close() {
        closed = true;
        for (Map.Entry<String, PendingCommand> entry : pendingCommands.entrySet()) {
            if (pendingCommands.remove(entry.getKey(), entry.getValue()))
                entry.getValue().future().completeExceptionally(new IOException("The browser socket was closed."));
        }
        listeners.clear();
    }

    private void publish(BrowserAdapter.BrowserEvent event) {
        for (Consumer<BrowserAdapter.BrowserEvent> listener : listeners) {
            try { listener.accept(event); } catch (RuntimeException ignored) { }
        }
    }

    private static boolean empty(String value) { return value == null || value.isBlank(); }

    record Key(String browser, String instanceId, String profileId) {}
    private record PendingCommand(Key key, BrowserSession session, BrowserSocketClient client, String sessionToken,
                                  Boolean muted, CompletableFuture<BrowserAdapter.BrowserCommandAck> future) {}
    private static final class BrowserSession {
        private final Map<String, BrowserAdapter.BrowserTab> tabs = new ConcurrentHashMap<>();
        private volatile BrowserSocketClient client;
    }
}
