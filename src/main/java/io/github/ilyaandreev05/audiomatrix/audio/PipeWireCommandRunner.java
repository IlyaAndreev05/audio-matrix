package io.github.ilyaandreev05.audiomatrix.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Executes bounded PipeWire and WirePlumber command line operations. */
final class PipeWireCommandRunner {
    private PipeWireCommandRunner() {}

    static String run(List<String> command, Duration timeout) throws Exception {
        Path output = Files.createTempFile("audio-matrix-command-", ".out");
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(output.toFile()).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
                throw new IOException("Command timed out: " + command.getFirst());
            String text = Files.readString(output);
            if (process.exitValue() != 0)
                throw new IOException(text.isBlank() ? command.getFirst() + " exited with " + process.exitValue() : text.trim());
            return text;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                boolean interrupted = Thread.interrupted();
                try { process.waitFor(1, TimeUnit.SECONDS); }
                catch (InterruptedException error) { interrupted = true; }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
            Files.deleteIfExists(output);
        }
    }

    static boolean executable(String name) {
        String path = System.getenv().getOrDefault("PATH", "");
        for (String directory : path.split(java.io.File.pathSeparator))
            if (Files.isExecutable(Path.of(directory, name))) return true;
        return false;
    }

    static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
