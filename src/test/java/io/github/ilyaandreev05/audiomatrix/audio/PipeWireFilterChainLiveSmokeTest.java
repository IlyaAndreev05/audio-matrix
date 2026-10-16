package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireFilterChainLiveSmokeTest {
    @TempDir Path tempDir;

    @Test
    void bundledLadspaLimiterGraphLoadsAndCleansUpInPipeWire() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE")),
                "Set AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE=1 to run the live PipeWire graph check");
        Assumptions.assumeTrue(commandExists("pipewire") && commandExists("pw-link") && commandExists("pw-dump"),
                "Requires pipewire, pw-link, and pw-dump");

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String input = "am_limiter_check_" + suffix + "_input";
        String output = "am_limiter_check_" + suffix + "_output";
        Path pluginDirectory = tempDir.resolve("ladspa");
        Files.createDirectories(pluginDirectory);
        try (var plugin = getClass().getResourceAsStream("/native/libaudiomatrix_limiter.so")) {
            Assumptions.assumeTrue(plugin != null, "The bundled LADSPA limiter is required");
            Files.copy(plugin, pluginDirectory.resolve("libaudiomatrix_limiter.so"));
        }
        Path config = tempDir.resolve("limiter-check.conf");
        Path log = tempDir.resolve("limiter-pipewire-client.log");
        String graph = PipeWireFilterChainConfig.render("Audio Matrix limiter smoke", input, output,
                List.of("FL", "FR"), List.of(Map.of("type", "limiter", "enabled", true,
                        "ceiling_db", -1.0, "lookahead_ms", 5.0, "release_ms", 50.0)), true);
        Files.writeString(config, graph.replace("log.level = 1", "log.level = 3"));

        ProcessBuilder builder = new ProcessBuilder("pipewire", "-c", config.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("LADSPA_PATH", pluginDirectory.toString());
        Process client = null;
        try {
            client = builder.start();
            waitForPorts(client, input, output, log);
            assertTrue(run("pw-link", "-i").contains(input));
            assertTrue(run("pw-link", "-o").contains(output));
            String diagnostics = Files.readString(log);
            assertTrue(diagnostics.contains("loaded n_input:8 n_output:8"), diagnostics);
            assertFalse(diagnostics.contains("can not be set"), diagnostics);
            assertFalse(diagnostics.contains("unknown input port"), diagnostics);
        } finally {
            stop(client);
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline && nodeExists(input, output)) Thread.sleep(100);
            assertFalse(nodeExists(input, output), "The temporary limiter nodes must be removed on close");
        }
    }

    @Test
    void leftChannelInvertGraphLoadsAndCleansUpInPipeWire() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE")),
                "Set AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE=1 to run the live PipeWire graph check");
        Assumptions.assumeTrue(commandExists("pipewire") && commandExists("pw-link") && commandExists("pw-dump"),
                "Requires pipewire, pw-link, and pw-dump");

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String input = "am_channel_check_" + suffix + "_input";
        String output = "am_channel_check_" + suffix + "_output";
        Path config = tempDir.resolve("channel-check.conf");
        Path log = tempDir.resolve("pipewire-client.log");
        Files.writeString(config, PipeWireFilterChainConfig.render("Audio Matrix channel test", input, output,
                List.of("FL", "FR"), List.of(Map.of("type", "phase_invert", "enabled", true, "channels", List.of("FL")))));

        Process client = null;
        try {
            client = new ProcessBuilder("pipewire", "-c", config.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            waitForPorts(client, input, output, log);
            assertTrue(run("pw-link", "-i").contains(input));
            assertTrue(run("pw-link", "-o").contains(output));
        } finally {
            stop(client);
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline && nodeExists(input, output)) Thread.sleep(100);
            assertFalse(nodeExists(input, output), "The temporary channel filter nodes must be removed on close");
        }
    }

    @Test
    void applicationEqGraphLoadsWithoutConnectingToAnOutput() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE")),
                "Set AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE=1 to run the live PipeWire graph check");
        Assumptions.assumeTrue(commandExists("pipewire") && commandExists("pw-link") && commandExists("pw-dump"),
                "Requires pipewire, pw-link, and pw-dump");

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String input = "am_app_eq_check_" + suffix + "_input";
        String output = "am_app_eq_check_" + suffix + "_output";
        Path config = tempDir.resolve("application-eq-check.conf");
        Path log = tempDir.resolve("application-eq-pipewire-client.log");
        Files.writeString(config, PipeWireFilterChainConfig.render("Audio Matrix application EQ smoke", input, output,
                List.of("FL", "FR"), List.of(Map.of("type", "notch", "frequency_hz", 80.0,
                        "gain_db", -2.0, "q", 2.0, "enabled", true, "channels", List.of("FL")))));

        Process client = null;
        try {
            client = new ProcessBuilder("pipewire", "-c", config.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            waitForPorts(client, input, output, log);
            assertTrue(run("pw-link", "-i").contains(input));
            assertTrue(run("pw-link", "-o").contains(output));
        } finally {
            stop(client);
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline && nodeExists(input, output)) Thread.sleep(100);
            assertFalse(nodeExists(input, output), "The temporary application EQ nodes must be removed on close");
        }
    }

    @Test
    void userChannelMatrixGraphLoadsWithoutConnectingToAnOutput() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE")),
                "Set AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE=1 to run the live PipeWire graph check");
        Assumptions.assumeTrue(commandExists("pipewire") && commandExists("pw-link") && commandExists("pw-dump"),
                "Requires pipewire, pw-link, and pw-dump");

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String input = "am_matrix_check_" + suffix + "_input";
        String output = "am_matrix_check_" + suffix + "_output";
        Path config = tempDir.resolve("matrix-check.conf");
        Path log = tempDir.resolve("matrix-pipewire-client.log");
        ChannelMatrix matrix = ChannelMatrix.parse(DefaultConfig.map(
                "inputs", List.of("FL", "FR"), "outputs", List.of("FL", "FR"),
                "coefficients", List.of(List.of(0.5, 0.5), List.of(-0.25, 0.25))));
        Files.writeString(config, PipeWireFilterChainConfig.renderMatrix("Audio Matrix matrix smoke", input, output, matrix));

        Process client = null;
        try {
            client = new ProcessBuilder("pipewire", "-c", config.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            waitForPorts(client, input, output, log);
            assertTrue(run("pw-link", "-i").contains(input));
            assertTrue(run("pw-link", "-o").contains(output));
            String diagnostics = Files.readString(log);
            assertFalse(diagnostics.contains("unknown input port"), diagnostics);
            assertFalse(diagnostics.contains("unknown output port"), diagnostics);
            assertFalse(diagnostics.contains("can not be set"), diagnostics);
        } finally {
            stop(client);
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline && nodeExists(input, output)) Thread.sleep(100);
            assertFalse(nodeExists(input, output), "The temporary matrix nodes must be removed on close");
        }
    }

    private static void waitForPorts(Process client, String input, String output, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && client.isAlive()) {
            String inputs = run("pw-link", "-i"), outputs = run("pw-link", "-o");
            if (inputs.contains(input) && outputs.contains(output)) return;
            Thread.sleep(100);
        }
        String diagnostic = Files.exists(log) ? Files.readString(log) : "";
        throw new IOException("Per-channel PipeWire filter ports did not appear: " + diagnostic);
    }

    private static boolean nodeExists(String... names) throws Exception {
        List<String> current = new ObjectMapper().readTree(run("pw-dump", "-N")).findValuesAsText("node.name");
        for (String name : names) if (current.contains(name)) return true;
        return false;
    }

    private static String run(String... command) throws Exception {
        Path output = Files.createTempFile("audiomatrix-pw-channel-smoke-", ".out");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException(command[0] + " timed out"); }
            String text = Files.readString(output);
            if (process.exitValue() != 0) throw new IOException(command[0] + " failed: " + text);
            return text;
        } finally { Files.deleteIfExists(output); }
    }

    private static boolean commandExists(String command) {
        for (String directory : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
            if (Files.isExecutable(Path.of(directory, command))) return true;
        return false;
    }

    private static void stop(Process process) throws InterruptedException {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        if (!process.waitFor(1, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS); }
    }
}
