package io.github.ilyaandreev05.audiomatrix;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Owns the per-user Audio Matrix process and forwards requests to its running instance. */
public final class InstanceControlServer implements AutoCloseable {
    private static final Set<String> COMMANDS = Set.of("open", "quit", "background");
    private static final Duration FORWARD_TIMEOUT = Duration.ofSeconds(3);
    private final Path socketPath;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final ServerSocketChannel server;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Consumer<String>> handler = new AtomicReference<>();
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
    private final Thread listener;

    private InstanceControlServer(Path socketPath, FileChannel lockChannel, FileLock lock,
                                  ServerSocketChannel server) {
        this.socketPath = socketPath;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.server = server;
        listener = Thread.ofPlatform().daemon().name("audio-matrix-instance-control").start(this::acceptLoop);
    }

    public static Path defaultSocketPath() {
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        Path directory = runtime == null || runtime.isBlank()
                ? Path.of(System.getProperty("user.home"), ".cache", "audio-matrix", "run")
                : Path.of(runtime, "audio-matrix");
        return directory.resolve("control.sock");
    }

    /** Claim the per-user instance. Returns null after forwarding a request to a running instance. */
    public static InstanceControlServer acquireOrForward(String command) throws IOException {
        return acquireOrForward(defaultSocketPath(), command);
    }

    static InstanceControlServer acquireOrForward(Path socketPath, String command) throws IOException {
        if (!COMMANDS.contains(command)) throw new IllegalArgumentException("Unknown control command: " + command);
        Path normalizedSocket = socketPath.toAbsolutePath().normalize();
        Path directory = normalizedSocket.getParent();
        Files.createDirectories(directory);
        setPermissions(directory, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Path lockPath = normalizedSocket.resolveSibling(normalizedSocket.getFileName() + ".lock");
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        setPermissions(lockPath, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        FileLock lock;
        try { lock = channel.tryLock(); }
        catch (OverlappingFileLockException occupied) { lock = null; }
        if (lock == null) {
            channel.close();
            try {
                if (forward(normalizedSocket, command, FORWARD_TIMEOUT)) return null;
            } catch (IOException unavailable) {
                if (!tryAcquireLock(normalizedSocket))
                    throw new IOException("Audio Matrix is already starting, but its control socket did not answer", unavailable);
            }
            if (tryAcquireLock(normalizedSocket)) return acquireOrForward(normalizedSocket, command);
            return null;
        }

        ServerSocketChannel server = null;
        try {
            Files.deleteIfExists(normalizedSocket);
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(normalizedSocket));
            setPermissions(normalizedSocket, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return new InstanceControlServer(normalizedSocket, channel, lock, server);
        } catch (IOException failure) {
            if (server != null) try { server.close(); } catch (IOException ignored) { }
            try { lock.release(); } catch (IOException ignored) { }
            try { channel.close(); } catch (IOException ignored) { }
            throw failure;
        }
    }

    /** Sends a quit command when an instance exists; waits for its lock to be released. */
    public static boolean requestQuitAndWait(Duration timeout) throws IOException, InterruptedException {
        Path socket = defaultSocketPath().toAbsolutePath().normalize();
        if (!Files.exists(socket.resolveSibling(socket.getFileName() + ".lock"))) return false;
        if (tryAcquireLock(socket)) return false;
        try {
            if (!forward(socket, "quit", FORWARD_TIMEOUT)) return false;
        } catch (IOException unavailable) {
            if (tryAcquireLock(socket)) return false;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (tryAcquireLock(socket)) return true;
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new IOException("Audio Matrix did not stop within " + timeout.toSeconds() + " seconds");
    }

    public void setHandler(Consumer<String> next) {
        handler.set(next);
        String command;
        while ((command = pending.poll()) != null) dispatch(command);
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try (SocketChannel client = server.accept();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(client), StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(client), StandardCharsets.UTF_8))) {
                String command = reader.readLine();
                if (command != null && COMMANDS.contains(command)) {
                    writer.write("ok\n");
                    writer.flush();
                    dispatch(command);
                } else {
                    writer.write("error\n");
                    writer.flush();
                }
            } catch (IOException error) {
                if (!closed.get()) continue;
            }
        }
    }

    private void dispatch(String command) {
        Consumer<String> current = handler.get();
        if (current == null) pending.add(command);
        else current.accept(command);
    }

    private static boolean forward(Path socket, String command, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                client.configureBlocking(true);
                client.connect(UnixDomainSocketAddress.of(socket));
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(client), StandardCharsets.UTF_8));
                     BufferedReader reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(client), StandardCharsets.UTF_8))) {
                    writer.write(command);
                    writer.write('\n');
                    writer.flush();
                    return "ok".equals(reader.readLine());
                }
            } catch (IOException error) {
                last = error;
                try { TimeUnit.MILLISECONDS.sleep(40); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while contacting Audio Matrix", interrupted);
                }
            }
        }
        if (last != null) throw last;
        return false;
    }

    private static boolean tryAcquireLock(Path socket) throws IOException {
        Path lockPath = socket.resolveSibling(socket.getFileName() + ".lock");
        if (!Files.exists(lockPath)) return true;
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE)) {
            try (FileLock lock = channel.tryLock()) { return lock != null; }
            catch (OverlappingFileLockException occupied) { return false; }
        }
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        try { Files.setPosixFilePermissions(path, permissions); }
        catch (UnsupportedOperationException | IOException ignored) { }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { server.close(); } catch (IOException ignored) { }
        try { listener.join(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        try { lock.release(); } catch (IOException ignored) { }
        try { lockChannel.close(); } catch (IOException ignored) { }
    }
}
