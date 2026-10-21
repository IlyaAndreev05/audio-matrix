package io.github.ilyaandreev05.audiomatrix.gnome;

import java.io.IOException;
import java.net.ConnectException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/** Filesystem checks and owner-only permissions for the browser socket endpoint. */
final class BrowserSocketFiles {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> SOCKET_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private BrowserSocketFiles() {}

    static Path defaultSocketPath() {
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        if (runtime != null && !runtime.isBlank()) return Path.of(runtime, "audio-matrix", "browser.sock");
        return Path.of("/run/user", processUid(), "audio-matrix", "browser.sock");
    }

    static void prepareParent(Path socket) throws IOException {
        Path parent = socket.getParent();
        if (parent == null) throw new IOException("The browser socket requires a parent directory.");
        for (Path component = parent; component != null; component = component.getParent()) {
            if (Files.isSymbolicLink(component)) throw new IOException("The browser socket path cannot contain symbolic links.");
        }
        Files.createDirectories(parent);
        if (!Files.getAttribute(parent, "unix:uid").toString().equals(processUid()))
            throw new IOException("The browser socket directory is not owned by this user.");
        setPermissions(parent, DIRECTORY_PERMISSIONS);
        verifyPermissions(parent, DIRECTORY_PERMISSIONS);
    }

    static void removeAbandonedSocket(Path socket) throws IOException {
        if (!Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(socket)
                || !Files.readAttributes(socket, java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).isOther())
            throw new IOException("Refusing to replace a non-socket browser path.");
        if (!Files.getAttribute(socket, "unix:uid", LinkOption.NOFOLLOW_LINKS).toString().equals(processUid()))
            throw new IOException("The existing browser socket is not owned by this user.");
        setPermissions(socket, SOCKET_PERMISSIONS);
        verifyPermissions(socket, SOCKET_PERMISSIONS);
        try (SocketChannel probe = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            probe.connect(UnixDomainSocketAddress.of(socket));
            throw new IOException("The browser socket is already in use.");
        } catch (ConnectException stale) {
            Files.deleteIfExists(socket);
        } catch (java.nio.file.NoSuchFileException stale) {
            // It disappeared while checking; bind will report a concurrent owner if one appeared.
        }
    }

    static void setSocketPermissions(Path socket) throws IOException {
        setPermissions(socket, SOCKET_PERMISSIONS);
        verifyPermissions(socket, SOCKET_PERMISSIONS);
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try { Files.setPosixFilePermissions(path, permissions); }
        catch (UnsupportedOperationException unsupported) {
            throw new IOException("Owner-only browser socket permissions are unavailable.", unsupported);
        }
    }

    private static void verifyPermissions(Path path, Set<PosixFilePermission> expected) throws IOException {
        if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(expected))
            throw new IOException("The browser socket permissions are broader than owner-only.");
    }

    private static String processUid() {
        try { return Files.getAttribute(Path.of("/proc/self"), "unix:uid").toString(); }
        catch (IOException | UnsupportedOperationException failure) {
            String uid = System.getenv("UID");
            if (uid != null && uid.matches("[0-9]+")) return uid;
            throw new IllegalStateException("Could not determine the current user id for the browser socket.", failure);
        }
    }
}
