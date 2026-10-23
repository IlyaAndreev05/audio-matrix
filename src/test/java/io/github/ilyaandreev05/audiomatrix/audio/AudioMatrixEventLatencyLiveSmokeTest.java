package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphPipeWireCompiler;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.ilyaandreev05.audiomatrix.audio.EventLatencyImpulseWriter.IMPULSE_COUNT;
import static io.github.ilyaandreev05.audiomatrix.audio.EventLatencyImpulseWriter.SAMPLE_RATE;
import static io.github.ilyaandreev05.audiomatrix.audio.EventLatencyRouteSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Measures event-input-monitor to routed-output PCM, excluding companion dispatch and hardware latency. */
class AudioMatrixEventLatencyLiveSmokeTest extends EventLatencyLiveSupport {
    @Test
    void measuresSimultaneousReferenceAndActualRoutedEventMonitorLatency() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE")),
                "Requires the private core and policy from scripts/pipewire-route-smoke.py");
        requirePrivateRuntime();
        for (String command : List.of("pipewire", "pw-loopback", "pw-link", "pw-dump", "pw-cat"))
            Assumptions.assumeTrue(commandExists(command), "Requires " + command);
        assertPrivateHarnessSeedsOnly();

        Map<String, Object> config = RouteSmokePcmSupport.loadFixture();
        AudioGraph graph = GraphCodec.fromMap(RouteSmokePcmSupport.activeRoute(config));
        Map<String, Object> runtime = GraphPipeWireCompiler.compile(graph, config);
        List<Process> clients = new ArrayList<>();
        PipeWireBackend backend = new PipeWireBackend();
        PipeWireRouteManager manager = new PipeWireRouteManager();
        EventLatencyImpulseWriter writer = null;
        Path captureFile = tempDir.resolve("event-latency.f32");
        try {
            startSink(RouteSmokePcmSupport.PRIMARY_SINK, RouteSmokePcmSupport.PRIMARY_MONITOR, backend, clients);
            startSink(RouteSmokePcmSupport.EVENT_SINK, RouteSmokePcmSupport.EVENT_MONITOR, backend, clients);
            clients.add(RouteSmokePcmSupport.startClient(
                    List.of("/bin/sh", "src/main/systemd/user/audiomatrix-events-loopback.sh"),
                    tempDir.resolve("event-input.log")));
            awaitNodeState(RouteSmokePcmSupport.ESS_MONITOR, true);
            Process player = startPlayer(clients);
            writer = new EventLatencyImpulseWriter(player);
            awaitNodeState(PLAYER, true);
            List<String> playerLinks = connectPlayerToEventInput();
            EventPath path = eventPath(manager, runtime, backend.refresh().get(5, TimeUnit.SECONDS));
            applyUntilRouted(backend, manager, runtime, path);

            Process recorder = startRecorder(captureFile, clients);
            awaitNodeState(CAPTURE, true);
            List<String> captureLinks = connectRecorderTaps();
            List<String> beforeLinks = currentLinks();
            assertMeasurementTopology(manager, path, playerLinks, captureLinks, beforeLinks);
            Map<String, Integer> ownedBefore = ownedEventNodes(path);
            // Reapply actual code during silence: stable event owners must retain their nodes and links.
            manager.apply(runtime, backend.refresh().get(5, TimeUnit.SECONDS)).get(20, TimeUnit.SECONDS);
            Map<String, Integer> ownedAfter = ownedEventNodes(path);
            List<String> afterLinks = currentLinks();
            assertEquals(ownedBefore, ownedAfter, "Reapplying must keep the same owned event node ids");
            assertEquals(new LinkedHashSet<>(beforeLinks), new LinkedHashSet<>(afterLinks),
                    "Reapplying the event route must not churn its topology");
            assertMeasurementTopology(manager, path, playerLinks, captureLinks, afterLinks);

            // The writer's half-second silence lead-in lets the normal route fade settle. Neither
            // this wait nor file size is used to measure latency: only simultaneous PCM frame offsets.
            writer.emitImpulses();
            writer.awaitImpulses();
            Thread.sleep(1000);
            assertTrue(player.isAlive(), "The persistent player exited: " + readLog("event-player.log"));
            assertTrue(recorder.isAlive(), "The single PCM recorder exited: " + readLog("event-recorder.log"));
            List<String> measuredLinks = currentLinks();
            assertMeasurementTopology(manager, path, playerLinks, captureLinks, measuredLinks);
            assertEquals(ownedBefore, ownedEventNodes(path), "Measuring must not replace the event route nodes");
            stop(recorder);
            // 100 ms is a private-route regression bound, not an end-to-end ESS/hardware latency claim.
            EventLatencyPcmSupport.Result result = EventLatencyPcmSupport.analyze(Files.readAllBytes(captureFile),
                    SAMPLE_RATE, IMPULSE_COUNT, SAMPLE_RATE / 4, SAMPLE_RATE / 10);
            writeResult(result, ownedBefore, ownedAfter, measuredLinks);
        } finally {
            if (writer != null) writer.close();
            try {
                manager.closeAsync().get(10, TimeUnit.SECONDS);
            } finally {
                try {
                    stopClients(clients);
                } finally {
                    backend.close();
                }
            }
        }
    }

    private static void writeResult(EventLatencyPcmSupport.Result measured, Map<String, Integer> before,
                                    Map<String, Integer> after, List<String> links) throws IOException {
        String output = System.getenv("AUDIO_MATRIX_ROUTE_LATENCY_RESULT");
        if (output == null || output.isBlank()) return;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("latency_ms_p50", measured.latencyMsP50());
        result.put("latency_ms_p95", measured.latencyMsP95());
        result.put("latency_ms_max", measured.latencyMsMax());
        result.put("sample_count", measured.sampleCount());
        result.put("reference_node", RouteSmokePcmSupport.ESS_MONITOR);
        result.put("output_node", RouteSmokePcmSupport.EVENT_SINK);
        result.put("capture_node", CAPTURE);
        result.put("sample_rate", SAMPLE_RATE);
        result.put("measurement", "simultaneous-reference-and-routed-monitor-pcm");
        result.put("owned_node_ids_before", before);
        result.put("owned_node_ids_after", after);
        result.put("links", links);
        Files.writeString(Path.of(output), new ObjectMapper().writeValueAsString(result));
    }

    private static void stopClients(List<Process> clients) {
        boolean interrupted = false;
        for (int index = clients.size() - 1; index >= 0; index--) {
            Process client = clients.get(index);
            try {
                stop(client);
            } catch (InterruptedException error) {
                interrupted = true;
                client.destroyForcibly();
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
