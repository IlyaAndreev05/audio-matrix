package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Per-connection writer and state shared by the socket reader and command dispatcher. */
final class BrowserSocketClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    final SocketChannel channel;
    private final OutputStream output;
    volatile BrowserSocketRegistry.Key key;
    volatile boolean snapshotReceived;
    volatile boolean disconnected;

    private final Set<String> commandRequestIds = ConcurrentHashMap.newKeySet();
    BrowserSocketClient(SocketChannel channel) throws IOException {
        this.channel = channel;
        this.output = new BufferedOutputStream(Channels.newOutputStream(channel));
    }

    synchronized void send(Map<String, Object> message) throws IOException {
        if (disconnected) throw new IOException("The browser connection is closed.");
        byte[] bytes = JSON.writeValueAsBytes(message);
        if (bytes.length > BrowserSocketProtocol.MAX_LINE_BYTES)
            throw new IOException("The browser command exceeds one MiB.");
        output.write(bytes);
        output.write('\n');
        output.flush();
    }
    boolean rememberCommandRequestId(String requestId) { return commandRequestIds.add(requestId); }


    void supersede() { disconnected = true; close(); }

    void close() {
        disconnected = true;
        try { channel.close(); } catch (IOException ignored) { }
    }
}
