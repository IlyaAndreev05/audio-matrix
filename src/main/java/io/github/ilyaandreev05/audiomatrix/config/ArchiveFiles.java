package io.github.ilyaandreev05.audiomatrix.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Secure path checks and atomic file operations shared by config archives. */
final class ArchiveFiles {
    private ArchiveFiles() {}

    static Path safeRelative(String raw) throws IOException {
        if (raw == null || raw.isBlank() || raw.contains("\\") || raw.contains(":") || raw.startsWith("/"))
            throw new ConfigArchive.ArchiveException("Measurement file paths must be relative");
        Path path = Path.of(raw).normalize();
        if (path.isAbsolute() || path.startsWith("..") || !path.toString().equals(raw))
            throw new ConfigArchive.ArchiveException("Unsafe measurement path: " + raw);
        return path;
    }

    static void validatePath(String raw) throws IOException {
        if (raw == null || raw.isBlank() || raw.contains("\\") || raw.contains(":") || raw.startsWith("/"))
            throw new ConfigArchive.ArchiveException("Unsafe archive path");
        Path path = Path.of(raw).normalize();
        if (path.isAbsolute() || path.startsWith("..") || !path.toString().replace('\\', '/').equals(raw))
            throw new ConfigArchive.ArchiveException("Unsafe archive path: " + raw);
        if (!(raw.equals("manifest.json") || raw.equals("config.yaml")
                || raw.equals("config.json") || raw.startsWith("data/")))
            throw new ConfigArchive.ArchiveException("Unexpected archive file: " + raw);
    }

    static boolean regularWithoutSymlinks(Path root, Path file) throws IOException {
        Path current = root;
        for (Path part : root.relativize(file)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) return false;
        }
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }

    static void ensureNoSymlinkParents(Path root, Path target) throws IOException {
        Path current = root;
        for (Path part : root.relativize(target.getParent())) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current))
                throw new ConfigArchive.ArchiveException("Measurement path traverses a symbolic link: " + current);
        }
    }

    static void put(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(bytes);
        zip.closeEntry();
    }

    static void put(ZipOutputStream zip, String path, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        try (InputStream input = Files.newInputStream(file)) {
            input.transferTo(zip);
        }
        zip.closeEntry();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[16_384];
                int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static void copyAtomically(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".restore-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                throw new ConfigArchive.ArchiveException("Measurement filesystem does not support atomic replacement", error);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
