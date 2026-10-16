package io.github.ilyaandreev05.audiomatrix.audio;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in live graph check; the only source is a zero-filled WAV at stream volume 0. */
class PipeWireOutputEqDedupLiveSmokeTest {
    @TempDir Path tempDir;

    @Test
    void twoSilentRoutesShareOnePhysicalOutputEqChain() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE")),
                "Set AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE=1 to run the live PipeWire graph check");
        for (String command : List.of("pw-play", "pw-loopback", "pw-link", "pw-dump", "wpctl", "pipewire"))
            Assumptions.assumeTrue(commandExists(command), "Requires " + command);

        PipeWireBackend backend = new PipeWireBackend();
        PipeWireRouteManager manager = new PipeWireRouteManager();
        Process silentSource = null;
        List<String> originalMatrixNodes = List.of();
        try {
            PipeWireBackend.Snapshot before = backend.refresh().get(5, TimeUnit.SECONDS);
            Assumptions.assumeTrue(before.connected() && !before.sinks().isEmpty(), "Requires an active PipeWire output");
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<String> originalNodes = mapper.readTree(run("pw-dump", "-N")).findValuesAsText("node.name");
            Assumptions.assumeTrue(originalNodes.stream().noneMatch(name -> name.startsWith("audiomatrix_output_")),
                    "Requires no other active Audio Matrix output-EQ graph");
            originalMatrixNodes = originalNodes.stream().filter(name -> name.startsWith("audiomatrix_")).sorted().toList();
            Path wav = tempDir.resolve("silence.wav");
            writeSilence(wav, 30);
            String applicationId = "org.example.AudioMatrixSilentOutputSmoke." + UUID.randomUUID();
            silentSource = new ProcessBuilder("pw-play", "--target", "0", "--volume", "0", "--properties",
                    "application.id=" + applicationId, wav.toString())
                    .redirectErrorStream(true).redirectOutput(tempDir.resolve("pw-play.log").toFile()).start();

            AudioNode stream = waitForNewStream(backend, before.streams(), applicationId);
            Map<String, Object> config = graphConfig(applicationId);
            PipeWireBackend.Snapshot live = new PipeWireBackend.Snapshot(true, "", before.sinks(), before.sources(),
                    List.of(stream), List.of(), Map.of(), before.defaultSinkId(), System.currentTimeMillis());
            manager.apply(config, live).get(20, TimeUnit.SECONDS);

            List<String> nodeNames = mapper.readTree(run("pw-dump", "-N")).findValuesAsText("node.name");
            List<String> outputEqInputs = nodeNames.stream().filter(name -> name.startsWith("audiomatrix_output_") && name.endsWith("_dsp_input")).toList();
            List<String> outputEqOutputs = nodeNames.stream().filter(name -> name.startsWith("audiomatrix_output_") && name.endsWith("_dsp_output")).toList();
            List<String> limiterInputs = nodeNames.stream().filter(name -> name.startsWith("audiomatrix_output_") && name.endsWith("_limiter_input")).toList();
            List<String> limiterOutputs = nodeNames.stream().filter(name -> name.startsWith("audiomatrix_output_") && name.endsWith("_limiter_output")).toList();
            assertEquals(1, outputEqInputs.size(), "One sink should own one shared post-mix output-EQ pair");
            assertEquals(1, outputEqOutputs.size());
            assertEquals(1, limiterInputs.size(), "The linked limiter should be a separate stage after output EQ");
            assertEquals(1, limiterOutputs.size());
            String capture = outputEqInputs.getFirst();
            long routeConnections = run("pw-link", "-l").lines().filter(line -> line.contains(capture + ":")).count();
            assertTrue(routeConnections >= 4, "Both stereo route branches should feed the shared output EQ input");
            List<PipeWireRouteManager.Link> eqToLimiter = PipeWireLinkOperations.linksFromNode(run("pw-link", "-l"), outputEqOutputs.getFirst());
            assertTrue(eqToLimiter.stream().anyMatch(link -> link.input().startsWith(limiterInputs.getFirst() + ":")),
                    "The output EQ must feed the linked limiter");
        } finally {
            try { manager.clear().get(10, TimeUnit.SECONDS); } finally {
                stop(silentSource);
                backend.close();
                manager.closeAsync().get(10, TimeUnit.SECONDS);
            }
        }
        List<String> remainingNodes = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(run("pw-dump", "-N")).findValuesAsText("node.name");
        assertEquals(originalMatrixNodes,
                remainingNodes.stream().filter(name -> name.startsWith("audiomatrix_")).sorted().toList(),
                "The silent test graph must be removed on clear without removing other Audio Matrix nodes");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> graphConfig(String applicationId) {
        Map<String, Object> config = DefaultConfig.create();
        String busId = "eq-smoke-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String firstRoute = busId + "-first";
        String secondRoute = busId + "-second";
        List<Map<String, Object>> buses = new ArrayList<>((List<Map<String, Object>>) config.get("buses"));
        buses.add(DefaultConfig.map("id", busId, "name", "EQ smoke",
                "channels", "stereo", "volume", 1.0, "mute", false));
        config.put("buses", buses);
        ((List<Map<String, Object>>) config.get("applications")).add(DefaultConfig.map("id", "silent-smoke",
                "match", DefaultConfig.map("app_id", applicationId), "volume", 1.0, "mute", false, "bus", busId));
        List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", firstRoute, "from", busId, "to", "system_default", "enabled", true,
                "channel_map", "stereo", "volume", 1.0, "gain_db", 0.0, "delay_ms", 0.0));
        routes.add(DefaultConfig.map("id", secondRoute, "from", busId, "to", "system_default", "enabled", true,
                "channel_map", "stereo", "volume", 1.0, "gain_db", 0.0, "delay_ms", 0.0));
        Map<String, Object> profile = ((List<Map<String, Object>>) config.get("profiles")).getFirst();
        profile.put("active_buses", List.of(busId));
        profile.put("active_routes", List.of(firstRoute, secondRoute));
        Map<String, Object> dsp = (Map<String, Object>) config.get("dsp");
        dsp.put("global_eq", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "notch", "frequency_hz", 80.0, "gain_db", -2.0, "q", 2.0, "enabled", true))));
        return config;
    }

    private AudioNode waitForNewStream(PipeWireBackend backend, List<AudioNode> previous, String applicationId) throws Exception {
        java.util.Set<String> existing = previous.stream().map(AudioNode::name).collect(java.util.stream.Collectors.toSet());
        long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        while (System.nanoTime() < deadline) {
            PipeWireBackend.Snapshot snapshot = backend.refresh().get(4, TimeUnit.SECONDS);
            AudioNode stream = snapshot.streams().stream().filter(node -> !existing.contains(node.name())
                    && applicationId.equals(node.applicationId())).findFirst().orElse(null);
            if (stream != null) return stream;
            Thread.sleep(100);
        }
        throw new IOException("The silent pw-play stream did not appear in pw-dump");
    }

    private static void writeSilence(Path path, int seconds) throws IOException {
        int dataBytes = 48_000 * 2 * 2 * seconds;
        ByteBuffer wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36 + dataBytes).put(new byte[]{'W','A','V','E','f','m','t',' '});
        wav.putInt(16).putShort((short) 1).putShort((short) 2).putInt(48_000).putInt(48_000 * 4)
                .putShort((short) 4).putShort((short) 16).put(new byte[]{'d','a','t','a'}).putInt(dataBytes);
        Files.write(path, wav.array());
    }

    private static String run(String... command) throws Exception {
        Path output = Files.createTempFile("audiomatrix-output-eq-smoke-", ".out");
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
