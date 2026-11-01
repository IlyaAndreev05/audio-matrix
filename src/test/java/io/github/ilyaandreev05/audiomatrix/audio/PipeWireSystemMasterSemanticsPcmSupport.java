package io.github.ilyaandreev05.audiomatrix.audio;

import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.ilyaandreev05.audiomatrix.audio.PipeWireSystemMasterSemanticsLiveSmokeTest.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fresh PCM capture and ratio checks used by the private system-master live smoke. */
final class PipeWireSystemMasterSemanticsPcmSupport extends PipeWireChannelMatrixRouteLiveSmokeSupport {
    PipeWireSystemMasterSemanticsPcmSupport(Path directory) {
        tempDir = directory;
    }

    record Capture(Process process, Path path) {}

    Map<String, Double> verifyMasterPath(String sourceName, String sourceTarget, String applicationId,
                                         String streamName, int systemSinkId, Capture output,
                                         PipeWireBackend backend, PipeWireRouteManager manager,
                                         ConfigStore store) throws Exception {
        Capture raw = startCapture(streamName + "_raw", sourceTarget);
        Process tone = null;
        RouteSmokeToneWriter writer = null;
        try {
            tone = RouteSmokePcmSupport.startToneProcess(streamName, sourceTarget, applicationId);
            writer = RouteSmokeToneWriter.start(tone, TONE_FREQUENCY);
            awaitNodeState(streamName, true);
            PipeWireSystemMasterSemanticsControlSupport.applyMasterFromSystemSink(
                    systemSinkId, 1.0, false, backend, manager, store);
            double[] full = measureFreshRms(output, raw);
            assertTrue(full[0] > 0.01, sourceName + " must carry measurable routed PCM; RMS=" + full[0]);
            assertEquals(RouteSmokePcmSupport.TONE_AMPLITUDE / Math.sqrt(2), full[1], 0.01,
                    sourceName + " raw monitor must carry the original identical signal at unity");
            assertEquals(1.0, full[0] / full[1], VOLUME_TOLERANCE,
                    sourceName + " routed unity PCM must match the raw monitor capture");
            assertSourceLinks(streamName, sourceTarget);

            // Hold the terminal master at unity while changing only the private OSD sink control.
            PipeWireSystemMasterSemanticsControlSupport.setSystemSinkLevelOnly(
                    systemSinkId, 0.5, backend, manager, store);
            double[] osdOnly = measureFreshRms(output, raw);
            assertEquals(1.0, osdOnly[0] / full[0], VOLUME_TOLERANCE,
                    sourceName + " must not inherit the OSD sink's monitor gain");
            assertUnityRaw(full[1], osdOnly[1], sourceName);

            PipeWireSystemMasterSemanticsControlSupport.applyMasterFromSystemSink(
                    systemSinkId, 0.5, false, backend, manager, store);
            double[] half = measureFreshRms(output, raw);
            assertEquals(0.5, half[0] / full[0], VOLUME_TOLERANCE,
                    sourceName + " must receive one 1.0-to-0.5 master gain, not 0.25");
            assertUnityRaw(full[1], half[1], sourceName);

            PipeWireSystemMasterSemanticsControlSupport.applyMasterFromSystemSink(
                    systemSinkId, 0.0, false, backend, manager, store);
            double[] zero = measureFreshRms(output, raw);
            assertSilent(zero[0], sourceName + " must be silent at master zero");
            assertUnityRaw(full[1], zero[1], sourceName);

            PipeWireSystemMasterSemanticsControlSupport.applyMasterFromSystemSink(
                    systemSinkId, 1.0, false, backend, manager, store);
            double[] restored = measureFreshRms(output, raw);
            assertRestored(full[0], restored[0], sourceName + " must restore after zero");

            PipeWireSystemMasterSemanticsControlSupport.applyMasterFromSystemSink(
                    systemSinkId, 1.0, true, backend, manager, store);
            double[] muted = measureFreshRms(output, raw);
            assertSilent(muted[0], sourceName + " must be silent while globally muted");
            assertUnityRaw(full[1], muted[1], sourceName);

            PipeWireSystemMasterSemanticsControlSupport.applyMasterFromSystemSink(
                    systemSinkId, 1.0, false, backend, manager, store);
            double[] unmuted = measureFreshRms(output, raw);
            assertRestored(full[0], unmuted[0], sourceName + " must restore after unmute");
            assertUnityRaw(full[1], unmuted[1], sourceName);

            PipeWireSystemMasterSemanticsControlSupport.applyMasterFromSystemSink(
                    systemSinkId, 1.0, true, backend, manager, store);
            double[] mutedAgain = measureFreshRms(output, raw);
            assertSilent(mutedAgain[0], sourceName + " must remain silent while muted");
            assertUnityRaw(full[1], mutedAgain[1], sourceName);

            PipeWireSystemMasterSemanticsControlSupport.setSystemSinkLevelWhileMuted(
                    systemSinkId, 0.5, backend, manager, store);
            double[] changedWhileMuted = measureFreshRms(output, raw);
            assertSilent(changedWhileMuted[0], sourceName + " must stay silent after an OSD level change while muted");
            assertUnityRaw(full[1], changedWhileMuted[1], sourceName);
            PipeWireSystemMasterSemanticsControlSupport.applyMasterFromSystemSink(
                    systemSinkId, 0.5, false, backend, manager, store);
            double[] restoredAfterMutedChange = measureFreshRms(output, raw);
            assertEquals(0.5, restoredAfterMutedChange[0] / full[0], VOLUME_TOLERANCE,
                    sourceName + " must restore the new stored target after an OSD level change while muted");
            assertUnityRaw(full[1], restoredAfterMutedChange[1], sourceName);

            System.out.printf(java.util.Locale.ROOT,
                    "%s PCM RMS: full=%.6f half=%.6f ratio=%.6f zero=%.6f muted=%.6f restored=%.6f raw=%.6f%n",
                    sourceName, full[0], half[0], half[0] / full[0], zero[0], muted[0], unmuted[0], full[1]);
            return Map.of("full_rms", full[0], "half_rms", half[0], "half_ratio", half[0] / full[0],
                    "zero_rms", zero[0], "muted_rms", muted[0], "restored_rms", unmuted[0],
                    "raw_full_rms", full[1], "raw_half_rms", half[1], "raw_zero_rms", zero[1],
                    "raw_muted_rms", muted[1]);
        } finally {
            if (writer != null) writer.stop();
            stop(tone);
            stop(raw.process());
            awaitNodeState(streamName, false);
            awaitNodeState(streamName + "_raw", false);
        }
    }

    Capture startCapture(String name, String target) throws Exception {
        Path path = tempDir.resolve(name + ".f32");
        Process process = RouteSmokePcmSupport.startRecorder(name, target, path);
        try {
            awaitNodeState(name, true);
            awaitCaptureLink(target, name);
            return new Capture(process, path);
        } catch (Exception | AssertionError failure) {
            stop(process);
            throw failure;
        }
    }

    private static void awaitCaptureLink(String target, String name) throws Exception {
        // WirePlumber may autoconnect a recorder to the default sink instead of the requested
        // non-default monitor. Keep the PCM assertion strict and own the exact link.
        String listing = run("pw-link", "-l");
        if (!RouteSmokePcmSupport.linked(listing, target, name)) {
            for (String link : RouteSmokePcmSupport.links(listing)) {
                int arrow = link.indexOf(" -> ");
                String from = link.substring(0, arrow), to = link.substring(arrow + 4);
                if (to.startsWith(name + ":") && !from.startsWith(target + ":")) {
                    try { run("pw-link", "-d", from, to); }
                    catch (Exception ignored) { /* The wrong link may already be gone. */ }
                }
            }
            for (String channel : List.of("FL", "FR")) {
                String source = target + ":monitor_" + channel, sink = name + ":input_" + channel;
                if (!RouteSmokePcmSupport.links(run("pw-link", "-l")).contains(source + " -> " + sink))
                    run("pw-link", source, sink);
            }
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        do {
            listing = run("pw-link", "-l");
            if (RouteSmokePcmSupport.linked(listing, target, name)) return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertTrue(RouteSmokePcmSupport.linked(listing, target, name),
                "Private recorder must capture the requested monitor: " + target + ", links: "
                        + RouteSmokePcmSupport.links(listing));
    }

    private static double[] measureFreshRms(Capture... captures) throws Exception {
        long[] starts = new long[captures.length];
        for (int index = 0; index < captures.length; index++) starts[index] = Files.size(captures[index].path());
        // Require both a settling interval and a complete NEW window after every write.
        // A stalled/dead recorder must never masquerade as restored audio or muted silence.
        long requiredBytes = (long) ((0.35 + MEASURE_WINDOW_SECONDS) * RouteSmokePcmSupport.SAMPLE_RATE)
                * RouteSmokePcmSupport.CHANNELS * Float.BYTES;
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        boolean ready;
        do {
            ready = true;
            for (int index = 0; index < captures.length; index++) {
                assertTrue(captures[index].process().isAlive(), "Private PCM recorder exited: " + captures[index].path());
                ready &= Files.size(captures[index].path()) - starts[index] >= requiredBytes;
            }
            if (!ready) Thread.sleep(25);
        } while (!ready && System.nanoTime() < deadline);
        assertTrue(ready, "Private PCM recorders did not deliver a fresh complete measurement window");
        double[] rms = new double[captures.length];
        for (int index = 0; index < captures.length; index++)
            rms[index] = RouteSmokePcmSupport.rms(captures[index].path(), MEASURE_WINDOW_SECONDS);
        return rms;
    }

    private static void assertSourceLinks(String streamName, String target) throws Exception {
        String links = run("pw-link", "-l");
        assertTrue(RouteSmokePcmSupport.linked(links, streamName, target),
                "The identical test signal must enter its explicit system/event sink: " + links);
        assertFalse(RouteSmokePcmSupport.linked(links, streamName, OUTPUT_SINK),
                "The test signal must never bypass the Audio Matrix master route");
    }

    private static void assertUnityRaw(double original, double actual, String sourceName) {
        assertEquals(1.0, actual / original, VOLUME_TOLERANCE,
                sourceName + " raw monitor PCM must stay unity even at master zero or mute");
    }

    private static void assertSilent(double rms, String message) {
        assertTrue(rms < 0.001, message + "; measured full-band stereo RMS=" + rms);
    }

    private static void assertRestored(double original, double restored, String message) {
        assertTrue(original > 0.0 && Math.abs(restored / original - 1.0) <= VOLUME_TOLERANCE,
                message + "; original=" + original + ", restored=" + restored);
    }
}
