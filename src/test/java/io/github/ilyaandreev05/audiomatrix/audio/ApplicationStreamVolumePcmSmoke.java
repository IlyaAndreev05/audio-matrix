package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** An explicit virtual output proves that stream zero silences nonzero PCM and .35 restores linear gain. */
final class ApplicationStreamVolumePcmSmoke extends PipeWireChannelMatrixRouteLiveSmokeSupport {
    ApplicationStreamVolumePcmSmoke(Path directory) { tempDir = directory; }

    static void requirePrivateServer() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE")),
                "Run scripts/pipewire-route-smoke.py in its private PipeWire session");
        String runtime = System.getenv("XDG_RUNTIME_DIR"), pipewire = System.getenv("PIPEWIRE_RUNTIME_DIR");
        assertNotNull(runtime, "Private XDG_RUNTIME_DIR is required");
        assertNotNull(pipewire, "Private PIPEWIRE_RUNTIME_DIR is required");
        Path root = Path.of(runtime).toRealPath();
        assertTrue(root.toString().startsWith("/tmp/audio-matrix-private-route-"), "Host audio is forbidden");
        assertEquals(root, Path.of(pipewire).toRealPath());
        assertEquals("pipewire-0", System.getenv("PIPEWIRE_REMOTE"));
        for (JsonNode node : new ObjectMapper().readTree(run("pw-dump", "-N"))) {
            JsonNode props = node.path("info").path("props");
            assertFalse("alsa".equalsIgnoreCase(props.path("device.api").asText()) || props.has("api.alsa.path"),
                    "Private stream smoke must not discover ALSA hardware");
        }
    }

    void verify() throws Exception {
        requirePrivateServer();
        for (String command : List.of("pipewire", "pw-cat", "pw-dump", "pw-link", "wpctl"))
            Assumptions.assumeTrue(commandExists(command), "Requires " + command);
        List<Process> clients = new ArrayList<>();
        PipeWireBackend backend = new PipeWireBackend();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String sink = "am_manual_volume_" + suffix, monitor = sink + "_monitor";
        try {
            int defaultId = backend.refresh().get(5, TimeUnit.SECONDS).defaultSinkId();
            String defaultVolume = run("wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@");
            Path config = tempDir.resolve("manual-volume-output.conf"), log = tempDir.resolve("manual-volume-output.log");
            Files.writeString(config, virtualSinkConfig(sink, monitor));
            Process output = new ProcessBuilder("pipewire", "-c", config.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            clients.add(output);
            waitForSink(backend, output, sink, log);
            for (String process : List.of("chrome", "telegram-desktop")) verifyProcess(backend, sink, monitor, process, clients);
            assertEquals(defaultId, backend.refresh().get(5, TimeUnit.SECONDS).defaultSinkId(), "Do not change default output");
            assertEquals(defaultVolume, run("wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@"), "Do not alter default level or mute");
            requirePrivateServer();
        } finally {
            for (Process process : clients) stop(process);
            backend.close();
        }
    }

    private void verifyProcess(PipeWireBackend backend, String sink, String monitor, String process,
                               List<Process> clients) throws Exception {
        String name = "am_manual_" + process + UUID.randomUUID().toString().replace("-", "");
        String app = "org.example.ManualVolume." + name;
        Path capture = tempDir.resolve(name + ".f32");
        Process recorder = RouteSmokePcmSupport.startRecorder(name + "_capture", monitor, capture);
        Process tone = new ProcessBuilder("pw-cat", "--playback", "--target", sink, "--latency=20ms",
                "--properties", new ObjectMapper().writeValueAsString(Map.of("node.name", name,
                        "application.id", app, "application.process.binary", process)),
                "--raw", "--format", "f32", "--rate", "48000", "--channels", "2", "--channel-map", "FL,FR", "-")
                .redirectErrorStream(true).redirectOutput(tempDir.resolve(name + ".log").toFile()).start();
        clients.add(recorder);
        clients.add(tone);
        RouteSmokeToneWriter writer = RouteSmokeToneWriter.start(tone, 440);
        try {
            AudioNode stream = waitStream(backend, app, process);
            awaitNodeState(name + "_capture", true);
            String links = run("pw-link", "-l");
            assertTrue(RouteSmokePcmSupport.linked(links, name, sink));
            assertTrue(RouteSmokePcmSupport.linked(links, monitor, name + "_capture"));
            backend.setStreamMute(stream.id(), true).get(5, TimeUnit.SECONDS);
            stream = waitStream(backend, app, process);
            ApplicationStreamRuleApplier applier = new ApplicationStreamRuleApplier(backend);
            applier.setManualVolume(stream, .2, UUID.randomUUID().toString()).get(5, TimeUnit.SECONDS);
            assertControl(backend, app, process, .2);
            // A fresh snapshot paired with the old saved rule must not snap the live drag back to .8.
            applier.apply(backend.refresh().get(5, TimeUnit.SECONDS), List.of(Map.of("id", "old",
                    "match", Map.of("process", process), "volume", .8, "mute", true)), null).get(5, TimeUnit.SECONDS);
            assertControl(backend, app, process, .2);
            double initial = freshRms(recorder, capture);
            assertEquals(RouteSmokePcmSupport.TONE_AMPLITUDE * .2 / Math.sqrt(2), initial, .005);
            applier.setManualVolume(waitStream(backend, app, process), 0, UUID.randomUUID().toString()).get(5, TimeUnit.SECONDS);
            assertControl(backend, app, process, 0);
            double zero = freshRms(recorder, capture);
            assertTrue(zero < .0005, "Zero volume must silence fresh nonzero PCM: " + zero);
            applier.setManualVolume(waitStream(backend, app, process), .35, UUID.randomUUID().toString()).get(5, TimeUnit.SECONDS);
            assertControl(backend, app, process, .35);
            double restored = freshRms(recorder, capture);
            assertEquals(.35 / .2, restored / initial, .08, "Restored PCM must reflect the linear stream volume");
            assertEquals(RouteSmokePcmSupport.TONE_AMPLITUDE * .35 / Math.sqrt(2), restored, .005);
            System.out.printf(java.util.Locale.ROOT, "%s stream PCM RMS .2=%.6f zero=%.6f .35=%.6f%n", process, initial, zero, restored);
        } finally {
            writer.stop();
            stop(tone);
            stop(recorder);
        }
    }

    private static AudioNode waitStream(PipeWireBackend backend, String app, String process) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            AudioNode stream = backend.refresh().get(5, TimeUnit.SECONDS).streams().stream()
                    .filter(node -> app.equals(node.applicationId()) && process.equals(node.processName())).findFirst().orElse(null);
            if (stream != null) return stream;
            Thread.sleep(100);
        }
        throw new IOException("Private PCM stream not discovered for " + process);
    }

    private static void assertControl(PipeWireBackend backend, String app, String process, double level) throws Exception {
        AudioNode stream = waitStream(backend, app, process);
        String status = run("wpctl", "get-volume", Integer.toString(stream.id()));
        java.util.regex.Matcher volume = java.util.regex.Pattern.compile("Volume: ([0-9.]+)").matcher(status);
        assertTrue(volume.find(), status);
        assertEquals(Math.cbrt(level), Double.parseDouble(volume.group(1)), .02, status);
        assertFalse(status.contains("MUTED"), "Volume commands must unmute " + process);
    }

    private static double freshRms(Process recorder, Path capture) throws Exception {
        long start = Files.size(capture), bytes = (long) (.85 * 48000) * 2 * Float.BYTES;
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (recorder.isAlive() && Files.size(capture) - start < bytes && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(recorder.isAlive() && Files.size(capture) - start >= bytes,
                "Recorder must provide a fresh settled measurement, not stale data or a dead recorder");
        return RouteSmokePcmSupport.rms(capture, .5);
    }
}
