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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Applies the real route smoke fixture in a private PipeWire session and measures the routed PCM. */
class AudioMatrixRoutePcmLiveSmokeTest extends PipeWireChannelMatrixRouteLiveSmokeSupport {
    private static final double MEASURE_WINDOW_SECONDS = 1.0;

    @Test
    void audioMatrixRoutingDeliversMeasuredGainsToPrivateOutputs() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE")),
                "Requires the private PipeWire session from scripts/pipewire-route-smoke.py");
        for (String command : List.of("pipewire", "pw-link", "pw-dump", "pw-cat"))
            Assumptions.assumeTrue(commandExists(command), "Requires " + command);

        Map<String, Object> config = RouteSmokePcmSupport.loadFixture();
        AudioGraph graph = GraphCodec.fromMap(RouteSmokePcmSupport.activeRoute(config));
        Map<String, Object> runtime = GraphPipeWireCompiler.compile(graph, config);

        List<Process> clients = new ArrayList<>();
        List<RouteSmokePcmSupport.ToneWriter> tones = new ArrayList<>();
        PipeWireBackend backend = new PipeWireBackend();
        PipeWireRouteManager manager = new PipeWireRouteManager();
        Path primaryCapture = tempDir.resolve("primary.f32");
        Path eventCapture = tempDir.resolve("event.f32");
        try {
            Process eventSink = startSink(RouteSmokePcmSupport.EVENT_SINK, RouteSmokePcmSupport.EVENT_MONITOR, backend, clients);
            startSink(RouteSmokePcmSupport.PRIMARY_SINK, RouteSmokePcmSupport.PRIMARY_MONITOR, backend, clients);
            startSink(RouteSmokePcmSupport.SYSTEM_SINK, RouteSmokePcmSupport.SYSTEM_MONITOR, backend, clients);
            startEventInput(clients);
            startRecorder("am_route_smoke_primary_capture", RouteSmokePcmSupport.PRIMARY_MONITOR,
                    primaryCapture, clients);
            Process eventRecorder = startRecorder("am_route_smoke_event_capture", RouteSmokePcmSupport.EVENT_MONITOR,
                    eventCapture, clients);
            startTone(RouteSmokePcmSupport.APP_STREAM, RouteSmokePcmSupport.SYSTEM_SINK,
                    RouteSmokePcmSupport.APP_ID, RouteSmokePcmSupport.APP_FREQUENCY, clients, tones);
            startTone(RouteSmokePcmSupport.EVENT_STREAM, RouteSmokePcmSupport.ESS_MONITOR,
                    RouteSmokePcmSupport.EVENT_APP_ID, RouteSmokePcmSupport.EVENT_FREQUENCY, clients, tones);

            applyUntilRouted(backend, manager, runtime, List.of(RouteSmokePcmSupport.APP_STREAM,
                    RouteSmokePcmSupport.ESS_MONITOR));
            Thread.sleep(1500);
            Map<String, Object> measured = measure(primaryCapture, eventCapture);
            measured.put("primary_capture_bytes", Files.size(primaryCapture));
            measured.put("event_capture_bytes", Files.size(eventCapture));
            writeResult(measured, Map.of());
            assertMeasuredGains(measured);
            assertRoutedThroughAudioMatrix(runtime);

            Map<String, Integer> appOwned = appBranchNodes(runtime);
            tones.getFirst().stop();
            applyUntilBranchGone(backend, manager, runtime, RouteSmokePcmSupport.APP_STREAM);
            Thread.sleep(1200);
            assertTrue(RouteSmokePcmSupport.amplitude(eventCapture, RouteSmokePcmSupport.EVENT_FREQUENCY,
                            MEASURE_WINDOW_SECONDS) > 0.02,
                    "The event branch must keep its signal while the application stream disappears");
            assertEquals(appOwned, appBranchNodes(runtime), "The surviving branch must keep its owned node ids");
            startTone(RouteSmokePcmSupport.APP_STREAM, RouteSmokePcmSupport.SYSTEM_SINK,
                    RouteSmokePcmSupport.APP_ID, RouteSmokePcmSupport.APP_FREQUENCY, clients, tones);
            applyUntilRouted(backend, manager, runtime, List.of(RouteSmokePcmSupport.APP_STREAM,
                    RouteSmokePcmSupport.ESS_MONITOR));

            stop(eventSink);
            awaitNodeState(RouteSmokePcmSupport.EVENT_SINK, false);
            applyUntilRouted(backend, manager, runtime, List.of(RouteSmokePcmSupport.APP_STREAM));
            Thread.sleep(1200);
            assertTrue(RouteSmokePcmSupport.amplitude(primaryCapture, RouteSmokePcmSupport.APP_FREQUENCY,
                            MEASURE_WINDOW_SECONDS) > 0.05,
                    "The application branch must keep its signal while the event output disappears");
            assertEquals(appOwned, appBranchNodes(runtime), "The surviving branch must keep its owned node ids");
            startSink(RouteSmokePcmSupport.EVENT_SINK, RouteSmokePcmSupport.EVENT_MONITOR, backend, clients);
            applyUntilRouted(backend, manager, runtime, List.of(RouteSmokePcmSupport.APP_STREAM,
                    RouteSmokePcmSupport.ESS_MONITOR));
            stop(eventRecorder);
            Path returnedCapture = tempDir.resolve("event-returned.f32");
            startRecorder("am_route_smoke_event_capture_returned", RouteSmokePcmSupport.EVENT_MONITOR,
                    returnedCapture, clients);
            Thread.sleep(2000);
            assertTrue(RouteSmokePcmSupport.amplitude(returnedCapture, RouteSmokePcmSupport.EVENT_FREQUENCY,
                            MEASURE_WINDOW_SECONDS) > 0.02, "The event output must return with its signal");
            writeResult(measured, appOwned);
        } finally {
            for (RouteSmokePcmSupport.ToneWriter tone : tones) tone.stop();
            for (Process client : clients) stop(client);
            try {
                manager.closeAsync().get(10, TimeUnit.SECONDS);
            } finally {
                backend.close();
            }
        }
    }

    private void assertMeasuredGains(Map<String, Object> measured) {
        double appGain = (double) measured.get("app_gain");
        double eventGain = (double) measured.get("event_gain");
        assertTrue(appGain > 0.42 && appGain < 0.58,
                "The application path must deliver its configured 0.5 gain, measured " + appGain);
        assertTrue(eventGain > 0.19 && eventGain < 0.31,
                "The event path must deliver its configured 0.25 gain, measured " + eventGain);
        assertTrue((double) measured.get("app_leak") < 0.1,
                "The event tone must not leak into the application output");
        assertTrue((double) measured.get("event_leak") < 0.1,
                "The application tone must not leak into the event output");
    }

    private void assertRoutedThroughAudioMatrix(Map<String, Object> runtime) throws Exception {
        String listing = run("pw-link", "-l");
        String bus = "audiomatrix_bus_" + PipeWireRouteStaticSupport01.shortHash(RouteSmokePcmSupport.APP_SOURCE);
        assertTrue(RouteSmokePcmSupport.linked(listing, RouteSmokePcmSupport.APP_STREAM, bus),
                "The application stream must be linked into the Audio Matrix bus, links: "
                        + RouteSmokePcmSupport.links(listing));
        assertFalse(RouteSmokePcmSupport.linked(listing, RouteSmokePcmSupport.APP_STREAM,
                RouteSmokePcmSupport.PRIMARY_SINK), "The application stream must not be linked straight to the output");
        assertFalse(RouteSmokePcmSupport.linked(listing, RouteSmokePcmSupport.EVENT_STREAM,
                RouteSmokePcmSupport.EVENT_SINK), "The event stream must not be linked straight to the output");
        assertTrue(runtime.get("routes") instanceof List<?> && !((List<?>) runtime.get("routes")).isEmpty(),
                "The compiled fixture must contain routes");
    }

    private Map<String, Object> measure(Path primaryCapture, Path eventCapture) throws IOException {
        return RouteSmokePcmSupport.measure(primaryCapture, eventCapture, MEASURE_WINDOW_SECONDS);
    }

    private void applyUntilRouted(PipeWireBackend backend, PipeWireRouteManager manager,
                                  Map<String, Object> runtime, List<String> owners) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        Exception failure = null;
        while (System.nanoTime() < deadline) {
            try {
                PipeWireBackend.Snapshot snapshot = backend.refresh().get(5, TimeUnit.SECONDS);
                manager.apply(runtime, snapshot).get(20, TimeUnit.SECONDS);
                if (routed(manager, owners)) return;
            } catch (Exception error) {
                failure = error;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Audio Matrix did not route the private smoke streams: "
                + (failure == null ? "the branches stayed unlinked" : failure.getMessage()), failure);
    }

    private void applyUntilBranchGone(PipeWireBackend backend, PipeWireRouteManager manager,
                                      Map<String, Object> runtime, String owner) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            PipeWireBackend.Snapshot snapshot = backend.refresh().get(5, TimeUnit.SECONDS);
            manager.apply(runtime, snapshot).get(20, TimeUnit.SECONDS);
            PipeWireRouteManager.BranchDiagnostic diagnostic = manager.branchDiagnostics().get(owner);
            if (diagnostic == null || diagnostic.observedLinks().isEmpty()) return;
            Thread.sleep(250);
        }
        throw new IllegalStateException("The Audio Matrix branch stayed active after its stream disappeared: " + owner);
    }

    private boolean routed(PipeWireRouteManager manager, List<String> owners) {
        Map<String, PipeWireRouteManager.BranchDiagnostic> diagnostics = manager.branchDiagnostics();
        for (String owner : owners) {
            PipeWireRouteManager.BranchDiagnostic diagnostic = diagnostics.get(owner);
            if (diagnostic == null || !diagnostic.error().isEmpty() || diagnostic.observedLinks().isEmpty()) return false;
        }
        return true;
    }

    private Map<String, Integer> appBranchNodes(Map<String, Object> runtime) throws Exception {
        return RouteSmokePcmSupport.appBranchNodes(runtime, run("pw-dump", "-N"));
    }

    private Process startSink(String name, String monitor, PipeWireBackend backend, List<Process> clients)
            throws Exception {
        Path config = tempDir.resolve(name + ".conf");
        Files.writeString(config, virtualSinkConfig(name, monitor));
        Process client = RouteSmokePcmSupport.startClient(List.of("pipewire", "-c", config.toString()),
                tempDir.resolve(name + ".log"));
        clients.add(client);
        waitForSink(backend, client, name, tempDir.resolve(name + ".log"));
        return client;
    }

    private void startEventInput(List<Process> clients) throws Exception {
        Path script = Path.of("src/main/systemd/user/audiomatrix-events-loopback.sh");
        Process client = RouteSmokePcmSupport.startClient(List.of("/bin/sh", script.toString()),
                tempDir.resolve("event-input.log"));
        clients.add(client);
        awaitNodeState(RouteSmokePcmSupport.ESS_MONITOR, true);
    }

    private Process startRecorder(String name, String target, Path file, List<Process> clients) throws Exception {
        Process recorder = RouteSmokePcmSupport.startRecorder(name, target, file);
        clients.add(recorder);
        awaitNodeState(name, true);
        String listing = run("pw-link", "-l");
        assertTrue(RouteSmokePcmSupport.linked(listing, target, name),
                "The capture must record the requested output, links: " + RouteSmokePcmSupport.links(listing));
        return recorder;
    }

    private void startTone(String name, String target, String applicationId, double frequency,
                           List<Process> clients, List<RouteSmokePcmSupport.ToneWriter> tones) throws Exception {
        Process process = RouteSmokePcmSupport.startToneProcess(name, target, applicationId);
        clients.add(process);
        awaitNodeState(name, true);
        tones.add(RouteSmokePcmSupport.ToneWriter.start(process, frequency));
    }

    private void writeResult(Map<String, Object> measured, Map<String, Integer> appOwned) throws Exception {
        String path = System.getenv("AUDIO_MATRIX_ROUTE_SMOKE_RESULT");
        if (path == null || path.isBlank()) return;
        Map<String, Object> result = new LinkedHashMap<>(measured);
        result.put("app_owned_nodes", appOwned);
        result.put("links", RouteSmokePcmSupport.links(run("pw-link", "-l")));
        result.put("wpctl", run("wpctl", "status"));
        result.put("node_levels", RouteSmokePcmSupport.nodeLevels(run("pw-dump", "-N")));
        Files.writeString(Path.of(path), new ObjectMapper().writeValueAsString(result));
    }
}
