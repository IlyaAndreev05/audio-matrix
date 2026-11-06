package io.github.ilyaandreev05.audiomatrix.audio;

import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** PCM isolation proof that refuses to run against the user's PipeWire server. */
class PipeWireRearMatrixPcmLiveSmokeTest extends PipeWireChannelMatrixRouteLiveSmokeSupport {
    @Test void matrixSendsStereoOnlyToRearPair() throws Exception { verifyPair(true); }
    @Test void stereoRouteDoesNotLeakToRearPair() throws Exception { verifyPair(false); }

    private void verifyPair(boolean rear) throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE")));
        Path runtime = Path.of(System.getenv("PIPEWIRE_RUNTIME_DIR")).toRealPath();
        assertTrue(runtime.startsWith(Path.of("/tmp").toRealPath()));
        assertEquals(runtime, Path.of(System.getenv("XDG_RUNTIME_DIR")).toRealPath());
        String sink = "am_private_four_channel_sink", monitor = "am_private_four_channel_monitor";
        var positions = List.of("FL", "FR", "RL", "RR");
        var identity = new ChannelMatrix(positions, positions, List.of(
                List.of(1.0, 0.0, 0.0, 0.0), List.of(0.0, 1.0, 0.0, 0.0),
                List.of(0.0, 0.0, 1.0, 0.0), List.of(0.0, 0.0, 0.0, 1.0)));
        Path configFile = tempDir.resolve("private-sink.conf"), log = tempDir.resolve("private-sink.log");
        Files.writeString(configFile, PipeWireChannelMatrixConfig.render("Private four channel sink", sink, monitor, identity));
        var backend = new PipeWireBackend();
        var manager = new PipeWireRouteManager();
        var processes = new ArrayList<Process>();
        try {
            Process sinkProcess = new ProcessBuilder("pipewire", "-c", configFile.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            processes.add(sinkProcess);
            AudioNode sinkNode = waitForSink(backend, sinkProcess, sink, log);
            Map<String, Object> config = routeConfig(sink);
            @SuppressWarnings("unchecked") var routes = (List<Map<String, Object>>) config.get("routes");
            routes.getFirst().put("volume", 1.0);
            if (rear) routes.getFirst().put("channel_matrix", Map.of("inputs", List.of("FL", "FR"),
                    "outputs", List.of("RL", "RR"), "coefficients", List.of(List.of(1.0, 0.0), List.of(0.0, 1.0))));
            else routes.getFirst().remove("channel_matrix");
            var before = backend.refresh().get(5, TimeUnit.SECONDS);
            var snapshot = new PipeWireBackend.Snapshot(true, "", List.of(sinkNode), before.sources(),
                    List.of(), List.of(), Map.of(), before.defaultSinkId(), System.currentTimeMillis());
            var plan = manager.plan(config, snapshot);
            manager.apply(config, snapshot).get(20, TimeUnit.SECONDS);
            String bus = plan.buses().get("music").nodeName();
            Path input = tempDir.resolve("tone.raw"), output = tempDir.resolve("capture.raw");
            ByteBuffer tone = ByteBuffer.allocate(48000 * 4 * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 48000 * 4; i++) {
                short sample = (short) (10000 * Math.sin(2 * Math.PI * 300 * i / 48000));
                tone.putShort(sample).putShort(sample);
            }
            Files.write(input, tone.array());
            Process recorder = new ProcessBuilder("pw-cat", "-r", "--raw", "--rate", "48000", "--channels", "4",
                    "--channel-map", "FL,FR,RL,RR", "--format", "s16", "--target", "0",
                    "--properties", "{ node.name = am_private_split_record node.autoconnect = false }",
                    "--sample-count", "192000", "-").redirectOutput(output.toFile())
                    .redirectError(tempDir.resolve("record.log").toFile()).start();
            processes.add(recorder);
            Process player = new ProcessBuilder("pw-cat", "-p", "--raw", "--rate", "48000", "--channels", "2",
                    "--format", "s16", "--target", "0",
                    "--properties", "{ node.name = am_private_split_player node.autoconnect = false }", "-")
                    .redirectInput(input.toFile()).redirectError(tempDir.resolve("player.log").toFile()).start();
            processes.add(player);
            awaitPort("-i", "am_private_split_record:input_FL");
            awaitPort("-o", "am_private_split_player:output_FL");
            for (String channel : positions) run("pw-link", monitor + ":output_" + channel,
                    "am_private_split_record:input_" + channel);
            for (String channel : List.of("FL", "FR")) run("pw-link", "am_private_split_player:output_" + channel,
                    bus + ":playback_" + channel);
            assertTrue(recorder.waitFor(10, TimeUnit.SECONDS), "private recorder timed out");
            byte[] bytes = Files.readAllBytes(output);
            int[] peak = new int[4];
            ByteBuffer samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; samples.remaining() >= 2; i++)
                peak[i % 4] = Math.max(peak[i % 4], Math.abs((int) samples.getShort()));
            int target = rear ? 2 : 0, silent = rear ? 0 : 2;
            assertTrue(peak[target] > 100 && peak[target + 1] > 100,
                    "target signal missing: " + java.util.Arrays.toString(peak));
            assertTrue(peak[silent] < 5 && peak[silent + 1] < 5,
                    "other pair leakage: " + java.util.Arrays.toString(peak));
        } finally {
            for (Process process : processes) stop(process);
            try { manager.closeAsync().get(10, TimeUnit.SECONDS); } finally { backend.close(); }
        }
    }

    private void awaitPort(String direction, String port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (run("pw-link", direction).lines().anyMatch(port::equals)) return;
            Thread.sleep(100);
        }
        fail("Missing private PipeWire port: " + port);
    }
}
