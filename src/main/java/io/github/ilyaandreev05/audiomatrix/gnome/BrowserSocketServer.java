package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Owner-only JSONL v1 endpoint for local browser extensions and native hosts. */
public final class BrowserSocketServer implements AutoCloseable {
    public static final int PROTOCOL_VERSION = BrowserSocketProtocol.PROTOCOL_VERSION;
    public static final int MAX_LINE_BYTES = BrowserSocketProtocol.MAX_LINE_BYTES;

    private final Path socketPath;
    private final ServerSocketChannel server;
    private final BrowserSocketRegistry registry = new BrowserSocketRegistry();
    private final Set<BrowserSocketClient> clients = ConcurrentHashMap.newKeySet();
    private final Thread acceptThread;
    private volatile boolean closed;

    private BrowserSocketServer(Path socketPath) throws IOException {
        this.socketPath = socketPath.toAbsolutePath().normalize();
        BrowserSocketFiles.prepareParent(this.socketPath);
        BrowserSocketFiles.removeAbandonedSocket(this.socketPath);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            server.bind(UnixDomainSocketAddress.of(this.socketPath));
            BrowserSocketFiles.setSocketPermissions(this.socketPath);
        } catch (IOException | RuntimeException failure) {
            try { server.close(); } catch (IOException ignored) { }
            try { Files.deleteIfExists(this.socketPath); } catch (IOException ignored) { }
            throw failure;
        }
        acceptThread = Thread.ofPlatform().daemon().name("audio-matrix-browser-socket").start(this::acceptLoop);
    }

    public static BrowserSocketServer start() throws IOException { return new BrowserSocketServer(defaultSocketPath()); }

    static BrowserSocketServer start(Path socketPath) throws IOException { return new BrowserSocketServer(socketPath); }

    public static Path defaultSocketPath() { return BrowserSocketFiles.defaultSocketPath(); }

    public Path socketPath() { return socketPath; }
    public List<BrowserAdapter.BrowserSnapshot> snapshots() { return registry.snapshots(); }
    public void addListener(Consumer<BrowserAdapter.BrowserEvent> listener) { registry.addListener(listener); }
    public void removeListener(Consumer<BrowserAdapter.BrowserEvent> listener) { registry.removeListener(listener); }
    public CompletableFuture<BrowserAdapter.BrowserCommandAck> command(BrowserAdapter.BrowserCommand command) {
        return registry.command(command);
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                SocketChannel channel = server.accept();
                if (closed) { channel.close(); break; }
                BrowserSocketClient client = new BrowserSocketClient(channel);
                clients.add(client);
                Thread.ofPlatform().daemon().name("audio-matrix-browser-client").start(() -> clientLoop(client));
            } catch (IOException failure) {
                if (!closed) continue;
            }
        }
    }

    private void clientLoop(BrowserSocketClient client) {
        try (InputStream input = new BufferedInputStream(Channels.newInputStream(client.channel))) {
            while (!closed && !client.disconnected) {
                String line = BrowserSocketProtocol.readLine(input);
                if (line == null) break;
                JsonNode message = BrowserSocketProtocol.parse(line);
                registry.accept(client, message);
            }
        } catch (IOException | RuntimeException rejected) {
            // Reject malformed/unsupported protocol input by closing only this browser connection.
        } finally {
            disconnect(client);
        }
    }

    private void disconnect(BrowserSocketClient client) {
        if (clients.remove(client)) {
            client.close();
            registry.disconnect(client);
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
        for (BrowserSocketClient client : List.copyOf(clients)) disconnect(client);
        registry.close();
        try { acceptThread.join(500); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        try { Files.deleteIfExists(socketPath); } catch (IOException ignored) { }
    }
}
