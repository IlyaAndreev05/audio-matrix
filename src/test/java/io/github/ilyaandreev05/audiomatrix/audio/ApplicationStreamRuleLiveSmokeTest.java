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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in live rule check using silent streams deliberately left unlinked from every output. */
class ApplicationStreamRuleLiveSmokeTest {
    private record RuleState(double volume, boolean muted) {}

    @TempDir Path tempDir;

    @Test
    void appliesIndependentChromeAndTelegramRulesToUnlinkedSilentStreams() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE")),
                "Set AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE=1 to run the live application-rule check");
        for (String command : List.of("pw-play", "pw-link", "pw-dump", "wpctl"))
            Assumptions.assumeTrue(commandExists(command), "Requires " + command);

        PipeWireBackend backend = new PipeWireBackend();
        List<Process> playbacks = new ArrayList<>();
        try {
            PipeWireBackend.Snapshot before = backend.refresh().get(5, TimeUnit.SECONDS);
            Assumptions.assumeTrue(before.connected() && !before.sinks().isEmpty(),
                    "Requires an active PipeWire server and output for unchanged-state checks");
            int originalDefaultId = defaultSinkId();
            double originalDefaultVolume = defaultSinkVolume();
            boolean originalDefaultMuted = defaultSinkMuted();
            String suffix = UUID.randomUUID().toString().replace("-", "");
            String chromeId = "org.example.AudioMatrixChromeRuleSmoke." + suffix;
            String telegramId = "org.example.AudioMatrixTelegramRuleSmoke." + suffix;
            Path wav = tempDir.resolve("silence.wav");
            writeSilence(wav, 30);

            playbacks.add(startUnlinkedPlayback(wav, chromeId, "chrome"));
            playbacks.add(startUnlinkedPlayback(wav, telegramId, "telegram-desktop"));
            AudioNode chrome = waitForStream(backend, chromeId, "chrome");
            AudioNode telegram = waitForStream(backend, telegramId, "telegram-desktop");
            assertTrue(PipeWireLinkOperations.linksFromNode(run("pw-link", "-l"), chrome.name()).isEmpty(),
                    "Chrome smoke stream must have no output links before applying its rule");
            assertTrue(PipeWireLinkOperations.linksFromNode(run("pw-link", "-l"), telegram.name()).isEmpty(),
                    "Telegram smoke stream must have no output links before applying its rule");

            List<Map<String, Object>> rules = List.of(
                    Map.of("id", "chrome-rule-" + suffix, "match", Map.of("process", "chrome"), "volume", 0.37, "mute", true),
                    Map.of("id", "telegram-rule-" + suffix, "match", Map.of("process", "telegram-desktop"), "volume", 0.62, "mute", false));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            new ApplicationStreamRuleApplier(backend).apply(backend.refresh().get(5, TimeUnit.SECONDS), rules,
                    (stream, error) -> failure.compareAndSet(null, error));

            RuleState adjustedChrome = waitForRuleState(backend, chromeId, "chrome", 0.37, true, failure);
            RuleState adjustedTelegram = waitForRuleState(backend, telegramId, "telegram-desktop", 0.62, false, failure);
            assertTrue(adjustedChrome.muted());
            assertEquals(Math.cbrt(0.62), adjustedTelegram.volume(), 0.02);
            assertFalse(adjustedTelegram.muted());
            // Unmuting restores the saved linear level of the muted rule.
            List<Map<String, Object>> unmutedRules = List.of(
                    Map.of("id", "chrome-rule-" + suffix, "match", Map.of("process", "chrome"), "volume", 0.37, "mute", false));
            new ApplicationStreamRuleApplier(backend).apply(backend.refresh().get(5, TimeUnit.SECONDS), unmutedRules,
                    (stream, error) -> failure.compareAndSet(null, error));
            RuleState restoredChrome = waitForRuleState(backend, chromeId, "chrome", 0.37, false, failure);
            assertEquals(Math.cbrt(0.37), restoredChrome.volume(), 0.02, "the saved rule level must survive the mute");
            assertFalse(restoredChrome.muted());
            String links = run("pw-link", "-l");
            assertTrue(PipeWireLinkOperations.linksFromNode(links, chrome.name()).isEmpty());
            assertTrue(PipeWireLinkOperations.linksFromNode(links, telegram.name()).isEmpty());

            PipeWireBackend.Snapshot after = backend.refresh().get(5, TimeUnit.SECONDS);
            assertTrue(after.connected());
            assertEquals(originalDefaultId, defaultSinkId(), "The system default output must remain unchanged");
            double currentDefaultVolume = defaultSinkVolume();
            Assumptions.assumeTrue(Math.abs(originalDefaultVolume - currentDefaultVolume) <= 0.01,
                    "The default sink level changed concurrently; physical-output isolation is inconclusive");
            assertEquals(originalDefaultMuted, defaultSinkMuted(), "The system default output mute must remain unchanged");
        } finally {
            for (Process playback : playbacks) stop(playback);
            backend.close();
        }
    }

    private Process startUnlinkedPlayback(Path wav, String applicationId, String processBinary) throws IOException {
        String properties = "application.id=" + applicationId + " application.process.binary=" + processBinary;
        return new ProcessBuilder("pw-play", "--target", "0", "--volume", "0", "--properties", properties,
                wav.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    private AudioNode waitForStream(PipeWireBackend backend, String applicationId, String processBinary) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            PipeWireBackend.Snapshot snapshot = backend.refresh().get(5, TimeUnit.SECONDS);
            AudioNode stream = snapshot.streams().stream().filter(node -> applicationId.equals(node.applicationId())
                    && processBinary.equals(node.processName())).findFirst().orElse(null);
            if (stream != null) return stream;
            Thread.sleep(100);
        }
        throw new IOException("Silent unlinked stream was not discovered for " + processBinary);
    }

    private RuleState waitForRuleState(PipeWireBackend backend, String applicationId, String processBinary,
                                       double volume, boolean muted, AtomicReference<Throwable> failure) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        String observed = "none";
        while (System.nanoTime() < deadline) {
            Throwable error = failure.get();
            if (error != null) throw new IOException("Application stream rule failed", error);
            PipeWireBackend.Snapshot snapshot = backend.refresh().get(5, TimeUnit.SECONDS);
            AudioNode stream = snapshot.streams().stream().filter(node -> applicationId.equals(node.applicationId())
                    && processBinary.equals(node.processName())).findFirst().orElse(null);
            if (stream != null) {
                String control = run("wpctl", "get-volume", Integer.toString(stream.id()));
                java.util.regex.Matcher actualVolume = java.util.regex.Pattern.compile("Volume: ([0-9.]+)").matcher(control);
                if (actualVolume.find()) {
                    // wpctl exposes the stored channel level separately from the effective mute state.
                    double value = Double.parseDouble(actualVolume.group(1));
                    boolean isMuted = control.contains("MUTED");
                    observed = control.trim().replace('\n', ' ');
                    if ((muted || Math.abs(value - Math.cbrt(volume)) < 0.02) && isMuted == muted)
                        return new RuleState(value, isMuted);
                }
            }
            Thread.sleep(200);
        }
        Throwable error = failure.get();
        if (error != null) throw new IOException("Application stream rule failed", error);
        throw new IOException("Timed out waiting for application rule state for " + processBinary
                + " expected linear=" + volume + " muted=" + muted + " observed: " + observed);
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
        Path output = Files.createTempFile("audiomatrix-rule-smoke-", ".out");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(command[0] + " timed out");
            }
            String text = Files.readString(output);
            if (process.exitValue() != 0) throw new IOException(command[0] + " failed: " + text.trim());
            return text;
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static int defaultSinkId() throws Exception {
        String inspect = run("wpctl", "inspect", "@DEFAULT_AUDIO_SINK@");
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("(?m)^id (\\d+),").matcher(inspect);
        if (!id.find()) throw new IOException("Could not resolve PipeWire's default output id");
        return Integer.parseInt(id.group(1));
    }

    private static double defaultSinkVolume() throws Exception {
        String status = run("wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@");
        java.util.regex.Matcher volume = java.util.regex.Pattern.compile("Volume: ([0-9.]+)").matcher(status);
        if (!volume.find()) throw new IOException("Could not read default output volume");
        return Double.parseDouble(volume.group(1));
    }

    private static boolean defaultSinkMuted() throws Exception {
        return run("wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@").contains("MUTED");
    }

    private static boolean commandExists(String command) {
        for (String directory : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
            if (Files.isExecutable(Path.of(directory, command))) return true;
        return false;
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
