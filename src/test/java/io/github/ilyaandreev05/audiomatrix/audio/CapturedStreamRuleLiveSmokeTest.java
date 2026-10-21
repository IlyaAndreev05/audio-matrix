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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in private-PipeWire check for exact captured-node binding, gain product and mute OR semantics. */
class CapturedStreamRuleLiveSmokeTest {
    private record LevelState(double volume, boolean muted) { }

    @TempDir Path tempDir;

    @Test
    void appliesOnlyExactCapturedStreamsWithParentTimesTabGainAndCombinedMute() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE")),
                "Run only in the private PipeWire route smoke session");
        Assumptions.assumeTrue(commandExists("pw-play") && commandExists("pw-link"), "Requires PipeWire playback tools");
        PipeWireBackend backend = new PipeWireBackend();
        List<Process> streams = new ArrayList<>();
        try {
            String suffix = UUID.randomUUID().toString().replace("-", "");
            String appIdA = "org.example.CaptureParentA." + suffix;
            String appIdB = "org.example.CaptureParentB." + suffix;
            String parentNameA = "am_capture_parent_a_" + suffix;
            String parentNameB = "am_capture_parent_b_" + suffix;
            String captureA = CapturedStreamBinding.NODE_PREFIX + "tab-a-" + suffix;
            String captureB = CapturedStreamBinding.NODE_PREFIX + "tab-b-" + suffix;
            String unmatchedCapture = captureA + "-near-match";
            Path silence = tempDir.resolve("silence.wav");
            writeSilence(silence);

            streams.add(startStream(silence, appIdA, "chrome", parentNameA));
            streams.add(startStream(silence, appIdB, "chrome", parentNameB));
            streams.add(startStream(silence, "io.github.ilyaandreev05.audiomatrix.chrome-tab-capture", "capture-host", captureA));
            streams.add(startStream(silence, "io.github.ilyaandreev05.audiomatrix.chrome-tab-capture", "capture-host", captureB));
            streams.add(startStream(silence, "io.github.ilyaandreev05.audiomatrix.chrome-tab-capture", "capture-host", unmatchedCapture));
            waitForNode(backend, parentNameA);
            waitForNode(backend, parentNameB);
            waitForNode(backend, captureA);
            waitForNode(backend, captureB);
            waitForNode(backend, unmatchedCapture);

            List<Map<String, Object>> rules = List.of(
                    Map.of("id", "capture-parent-a-" + suffix,
                            "match", Map.of("app_id", appIdA, "process", "chrome"),
                            "volume", 0.8, "mute", false),
                    Map.of("id", "capture-parent-b-" + suffix,
                            "match", Map.of("app_id", appIdB, "process", "chrome"),
                            "volume", 0.6, "mute", true));
            Map<String, CapturedStreamBinding> bindings = Map.of(
                    captureA, new CapturedStreamBinding(captureA, "generated-source-a", "capture-parent-a-" + suffix,
                            appIdA, "chrome", "tab:a-" + suffix, 0.5, true),
                    captureB, new CapturedStreamBinding(captureB, "generated-source-b", "capture-parent-b-" + suffix,
                            appIdB, "chrome", "tab:b-" + suffix, 0.5, false));
            LevelState unmatchedBefore = levelOf(backend, unmatchedCapture);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ApplicationStreamRuleApplier applier = new ApplicationStreamRuleApplier(backend);
            applier.apply(backend.refresh().get(5, TimeUnit.SECONDS), rules, bindings, false,
                    (stream, error) -> failure.compareAndSet(null, error)).get(10, TimeUnit.SECONDS);
            assertTrue(failure.get() == null);

            LevelState first = waitForLevel(backend, captureA, 0.4, true);
            LevelState second = waitForLevel(backend, captureB, 0.3, true);
            assertEquals(0.0, first.volume(), 0.02, "a muted captured stream keeps the effective gain at zero");
            assertTrue(first.muted(), "tab mute must remain separate from saved gain");
            assertEquals(0.0, second.volume(), 0.02);
            assertTrue(second.muted(), "parent mute must mute the captured stream");
            LevelState unmatched = levelOf(backend, unmatchedCapture);
            assertEquals(unmatchedBefore.volume(), unmatched.volume(), 0.02, "a prefix-near-match is not a binding");
            assertFalse(unmatched.muted());
            // The saved level of a captured stream is the parent level times the tab gain.
            Map<String, CapturedStreamBinding> unmutedBindings = Map.of(
                    captureA, new CapturedStreamBinding(captureA, "generated-source-a", "capture-parent-a-" + suffix,
                            appIdA, "chrome", "tab:a-" + suffix, 0.5, false));
            applier.apply(backend.refresh().get(5, TimeUnit.SECONDS), rules, unmutedBindings, true,
                    (stream, error) -> failure.compareAndSet(null, error)).get(10, TimeUnit.SECONDS);
            assertEquals(Math.cbrt(0.4), waitForLevel(backend, captureA, 0.4, false).volume(), 0.02,
                    "the captured level must be the parent level times the tab gain");
            String links = run("pw-link", "-l");
            assertTrue(PipeWireLinkOperations.linksFromNode(links, captureA).isEmpty());
            assertTrue(PipeWireLinkOperations.linksFromNode(links, captureB).isEmpty());
            assertTrue(PipeWireLinkOperations.linksFromNode(links, unmatchedCapture).isEmpty());
            List<Map<String, Object>> staleRules = List.of(
                    Map.of("id", "capture-parent-a-" + suffix,
                            "match", Map.of("app_id", appIdA, "process", "chrome"),
                            "volume", 0.2, "mute", false));
            List<Map<String, Object>> latestRules = List.of(
                    Map.of("id", "capture-parent-a-" + suffix,
                            "match", Map.of("app_id", appIdA, "process", "chrome"),
                            "volume", 0.7, "mute", false));
            PipeWireBackend.Snapshot rapidUpdateSnapshot = backend.refresh().get(5, TimeUnit.SECONDS);
            Map<String, CapturedStreamBinding> bindingA = unmutedBindings;
            CompletableFuture<Void> staleWrite = applier.apply(rapidUpdateSnapshot, staleRules, bindingA, true,
                    (stream, error) -> failure.compareAndSet(null, error));
            CompletableFuture<Void> latestWrite = applier.apply(rapidUpdateSnapshot, latestRules, bindingA, true,
                    (stream, error) -> failure.compareAndSet(null, error));
            CompletableFuture.allOf(staleWrite, latestWrite).get(10, TimeUnit.SECONDS);
            assertTrue(failure.get() == null);
            assertEquals(Math.cbrt(0.35), waitForLevel(backend, captureA, 0.35, false).volume(), 0.02,
                    "serialized absolute writes must leave the latest accepted gain");
        } finally {
            for (Process stream : streams) stop(stream);
            backend.close();
        }
    }

    private Process startStream(Path wav, String applicationId, String process, String nodeName) throws IOException {
        String properties = "application.id=" + applicationId + " application.process.binary=" + process
                + " node.name=" + nodeName;
        return new ProcessBuilder("pw-play", "--target", "0", "--volume", "0", "--properties", properties,
                wav.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    private AudioNode waitForNode(PipeWireBackend backend, String name) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            AudioNode node = findNode(backend.refresh().get(5, TimeUnit.SECONDS), name);
            if (node != null) return node;
            Thread.sleep(50);
        }
        throw new AssertionError("PipeWire playback stream did not appear: " + name);
    }

    private LevelState levelOf(PipeWireBackend backend, String name) throws Exception {
        AudioNode node = findNode(backend.refresh().get(5, TimeUnit.SECONDS), name);
        assertNotNull(node, "The captured stream disappeared: " + name);
        String control = run("wpctl", "get-volume", Integer.toString(node.id()));
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("Volume: ([0-9.]+)").matcher(control);
        assertTrue(matcher.find(), "Could not parse the WirePlumber volume: " + control);
        return new LevelState(Double.parseDouble(matcher.group(1)), control.contains("MUTED"));
    }

    private LevelState waitForLevel(PipeWireBackend backend, String name, double volume, boolean muted) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        LevelState observed = null;
        while (System.nanoTime() < deadline) {
            AudioNode node = findNode(backend.refresh().get(5, TimeUnit.SECONDS), name);
            if (node != null) {
                String control = run("wpctl", "get-volume", Integer.toString(node.id()));
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("Volume: ([0-9.]+)").matcher(control);
                if (matcher.find()) {
                    // WirePlumber reports the cubic value and zeroes the level of a muted stream.
                    double value = Double.parseDouble(matcher.group(1));
                    boolean isMuted = control.contains("MUTED");
                    observed = new LevelState(value, isMuted);
                    double expected = muted ? 0.0 : Math.cbrt(volume);
                    if (Math.abs(value - expected) <= 0.02 && isMuted == muted) return observed;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Captured stream level was not applied: " + name
                + " expected linear=" + volume + " muted=" + muted + " observed=" + observed);
    }

    private static AudioNode findNode(PipeWireBackend.Snapshot snapshot, String name) {
        return snapshot.streams().stream().filter(node -> name.equals(node.name())).findFirst().orElse(null);
    }

    private static void writeSilence(Path path) throws IOException {
        int frames = 48000 * 20;
        ByteBuffer wav = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes()).putInt(36 + frames * 4).put("WAVEfmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 2).putInt(48000).putInt(192000)
                .putShort((short) 4).putShort((short) 16).put("data".getBytes()).putInt(frames * 4);
        while (wav.hasRemaining()) wav.put((byte) 0);
        Files.write(path, wav.array());
    }

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] bytes = process.getInputStream().readAllBytes();
        if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Command timed out: " + command[0]);
        if (process.exitValue() != 0) throw new IOException("Command failed: " + new String(bytes));
        return new String(bytes);
    }

    private static boolean commandExists(String command) {
        try { return new ProcessBuilder("sh", "-c", "command -v " + command).start().waitFor() == 0; }
        catch (Exception ignored) { return false; }
    }

    private static void stop(Process process) throws InterruptedException {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
        }
    }
}
