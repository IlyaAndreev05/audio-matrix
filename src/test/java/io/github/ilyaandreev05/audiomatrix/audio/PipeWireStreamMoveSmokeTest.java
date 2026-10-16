package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireStreamMoveSmokeTest {
    @TempDir Path tempDir;

    @Test
    void pwLinkFallbackMovesSilentStreamAndRestoresItsOriginalEdges() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE")), "Set AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE=1 to run the live silent-stream check");
        Assumptions.assumeTrue(commandExists("pipewire") && commandExists("pw-link") && commandExists("pw-play")
                && commandExists("pw-dump") && commandExists("wpctl") && !commandExists("pactl"),
                "Requires PipeWire tools with pw-link available and pactl absent");

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String sinkNode = "am_movecheck_" + suffix + "_input";
        Path config = tempDir.resolve("move-check.conf");
        Path wav = tempDir.resolve("silence.wav");
        Path log = tempDir.resolve("pipewire-client.log");
        Files.writeString(config, virtualSinkConfig(sinkNode, "am_movecheck_" + suffix + "_output"));
        writeSilenceWav(wav, 10_000);

        PipeWireBackend backend = new PipeWireBackend();
        Process filterClient = null;
        Process playback = null;
        AudioNode stream = null;
        AudioNode originalSink = null;
        boolean moved = false;
        try {
            filterClient = new ProcessBuilder("pipewire", "-c", config.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            AudioNode virtualSink = waitForSink(backend, filterClient, sinkNode);
            PipeWireBackend.Snapshot initial = backend.refresh().get(5, TimeUnit.SECONDS);
            originalSink = initial.sinks().stream().filter(node -> node.id() == initial.defaultSinkId())
                    .findFirst().orElseThrow(() -> new IOException("Default PipeWire sink was not resolved"));
            double originalVolume = originalSink.volume();
            Set<Integer> previousStreamIds = new HashSet<>();
            initial.streams().forEach(node -> previousStreamIds.add(node.id()));

            playback = new ProcessBuilder("pw-play", "--volume", "0", wav.toString()).redirectErrorStream(true).start();
            stream = waitForNewPlaybackStream(backend, playback, previousStreamIds);
            List<PipeWireRouteManager.Link> originalLinks = PipeWireLinkOperations.linksFromNode(run("pw-link", "-l"), stream.name());
            assertFalse(originalLinks.isEmpty(), "The silent stream should start linked to its current output");

            assertTrue(backend.canMoveStreams());
            backend.moveStream(stream, virtualSink).get(6, TimeUnit.SECONDS);
            moved = true;
            List<PipeWireRouteManager.Link> movedLinks = PipeWireLinkOperations.linksFromNode(run("pw-link", "-l"), stream.name());
            assertFalse(movedLinks.isEmpty());
            assertTrue(movedLinks.stream().allMatch(link -> link.input().startsWith(virtualSink.name() + ":")), movedLinks.toString());

            backend.moveStream(stream, originalSink).get(6, TimeUnit.SECONDS);
            moved = false;
            List<PipeWireRouteManager.Link> restoredLinks = PipeWireLinkOperations.linksFromNode(run("pw-link", "-l"), stream.name());
            assertEquals(new HashSet<>(originalLinks), new HashSet<>(restoredLinks));

            PipeWireBackend.Snapshot after = backend.refresh().get(5, TimeUnit.SECONDS);
            assertEquals(initial.defaultSinkId(), after.defaultSinkId());
            AudioNode unchangedSink = after.sinks().stream().filter(node -> node.id() == after.defaultSinkId()).findFirst().orElseThrow();
            if (Double.isFinite(originalVolume) && Double.isFinite(unchangedSink.volume()))
                Assumptions.assumeTrue(Math.abs(originalVolume - unchangedSink.volume()) <= 0.01,
                        "Default sink volume changed during the live smoke; concurrent volume changes make this check inconclusive");
        } finally {
            if (moved && stream != null && originalSink != null) {
                try { backend.moveStream(stream, originalSink).get(6, TimeUnit.SECONDS); }
                catch (Exception ignored) {}
            }
            stop(playback);
            backend.close();
            stop(filterClient);
            if (filterClient != null) {
                long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (System.nanoTime() < deadline && nodeExists(sinkNode)) Thread.sleep(100);
                assertFalse(nodeExists(sinkNode), "The temporary virtual sink must be removed on close");
            }
        }
    }

    private AudioNode waitForSink(PipeWireBackend backend, Process client, String name) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && client.isAlive()) {
            AudioNode sink = backend.refresh().get(5, TimeUnit.SECONDS).sinks().stream()
                    .filter(node -> name.equals(node.name())).findFirst().orElse(null);
            if (sink != null) return sink;
            Thread.sleep(100);
        }
        String log = Files.exists(tempDir.resolve("pipewire-client.log")) ? Files.readString(tempDir.resolve("pipewire-client.log")) : "";
        throw new IOException("Temporary virtual sink did not appear: " + log);
    }

    private AudioNode waitForNewPlaybackStream(PipeWireBackend backend, Process playback, Set<Integer> previousIds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && playback.isAlive()) {
            AudioNode stream = backend.refresh().get(5, TimeUnit.SECONDS).streams().stream()
                    .filter(node -> !previousIds.contains(node.id()))
                    .filter(node -> node.processName().contains("pw-play") || node.name().contains("pw-play"))
                    .findFirst().orElse(null);
            if (stream != null) return stream;
            Thread.sleep(100);
        }
        throw new IOException("The silent pw-play stream did not appear in PipeWire");
    }

    private boolean nodeExists(String name) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(run("pw-dump", "-N")).findValuesAsText("node.name").contains(name);
    }

    private static boolean commandExists(String command) {
        for (String directory : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
            if (Files.isExecutable(Path.of(directory, command))) return true;
        return false;
    }

    private static String run(String... command) throws Exception {
        Path output = Files.createTempFile("audiomatrix-pw-smoke-", ".out");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException(command[0] + " timed out"); }
            String text = Files.readString(output);
            if (process.exitValue() != 0) throw new IOException(command[0] + " failed: " + text);
            return text;
        } finally { Files.deleteIfExists(output); }
    }

    private static void stop(Process process) throws InterruptedException {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        if (!process.waitFor(1, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS); }
    }

    private static void writeSilenceWav(Path path, int durationMs) throws IOException {
        int dataLength = 48_000 * durationMs / 1000 * 4;
        ByteBuffer wav = ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36 + dataLength).put(new byte[]{'W','A','V','E'});
        wav.put(new byte[]{'f','m','t',' '}).putInt(16).putShort((short) 1).putShort((short) 2).putInt(48_000);
        wav.putInt(192_000).putShort((short) 4).putShort((short) 16).put(new byte[]{'d','a','t','a'}).putInt(dataLength);
        wav.put(new byte[dataLength]);
        Files.write(path, wav.array());
    }

    private static String virtualSinkConfig(String inputName, String outputName) {
        return """
                context.properties = { log.level = 1 }
                context.spa-libs = {
                  audio.convert.* = audioconvert/libspa-audioconvert
                  support.* = support/libspa-support
                }
                context.modules = [
                  { name = libpipewire-module-rt args = { } flags = [ ifexists nofail ] }
                  { name = libpipewire-module-protocol-native }
                  { name = libpipewire-module-client-node }
                  { name = libpipewire-module-adapter }
                  { name = libpipewire-module-filter-chain args = {
                      node.description = "Audio Matrix silent move smoke"
                      media.name = "Audio Matrix silent move smoke"
                      filter.graph = { nodes = [ { type = builtin name = phase_invert label = invert } ] }
                      capture.props = { node.name = %s media.class = Audio/Sink node.autoconnect = false audio.channels = 2 audio.position = [ FL FR ] }
                      playback.props = { node.name = %s node.passive = true node.autoconnect = false audio.channels = 2 audio.position = [ FL FR ] }
                    }
                  }
                ]
                """.formatted(inputName, outputName);
    }
}
