package io.github.ilyaandreev05.audiomatrix.audio;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphPipeWireCompiler;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import io.github.ilyaandreev05.audiomatrix.graph.SwitchNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireBackendRestartLiveSmokeTest extends PipeWireBackendReconnectLiveSmokeSupport {
@Test void privatePipeWireRestartRebuildsTheRouteGraph() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_PIPEWIRE_RECONNECT_SMOKE")),
                "Run scripts/pipewire-reconnect-smoke.py for the isolated route restart smoke");
        for (String command : List.of("pipewire", "wireplumber", "pw-cli", "pw-loopback", "pw-link", "pw-dump", "wpctl"))
            Assumptions.assumeTrue(commandExists(command), "Requires " + command);
        isolatedRuntimePath();

        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String sinkName = "am_private_reconnect_" + suffix + "_input";
        String monitorName = "am_private_reconnect_" + suffix + "_output";
        Path sinkConfig = tempDir.resolve("private-null-sink.conf");
        Path sinkLog = tempDir.resolve("private-null-sink.log");
        Files.writeString(sinkConfig, virtualSinkConfig(sinkName, monitorName));

        Process firstServer = startPipeWire();
        Process firstPolicy = null;
        Process firstSinkClient = null;
        Process secondServer = null;
        Process secondPolicy = null;
        Process secondSinkClient = null;
        PipeWireBackend backend = new PipeWireBackend();
        PipeWireRouteManager manager = new PipeWireRouteManager();
        CountDownLatch initialSnapshot = new CountDownLatch(1);
        CountDownLatch recoveredSnapshot = new CountDownLatch(1);
        AtomicReference<PipeWireBackend.Snapshot> initial = new AtomicReference<>();
        AtomicReference<PipeWireBackend.Snapshot> recovered = new AtomicReference<>();
        try {
            assertTrue(waitForCore(firstServer, Duration.ofSeconds(8)), "The private PipeWire server did not start");
            firstPolicy = startWirePlumber("first-wireplumber.log");
            assertTrue(firstPolicy.isAlive(), "The private WirePlumber policy process did not stay alive");
            firstSinkClient = startVirtualSink(sinkConfig, sinkLog);
            AudioNode firstSink = waitForSink(backend, firstSinkClient, sinkName, sinkLog);
            run("wpctl", "set-default", Integer.toString(firstSink.id()));

            Map<String, Object> routeConfig = routeConfig(sinkName);
            backend.start(snapshot -> {
                if (snapshot.connected() && snapshot.sinks().stream().anyMatch(node -> sinkName.equals(node.name()))) {
                    if (backend.connectionGeneration() == 0) {
                        initial.set(snapshot);
                        initialSnapshot.countDown();
                    } else {
                        recovered.set(snapshot);
                        recoveredSnapshot.countDown();
                    }
                }
            });
            assertTrue(initialSnapshot.await(8, TimeUnit.SECONDS), "No initial private-server snapshot with the null sink arrived");
            PipeWireBackend.Snapshot first = initial.get();
            PipeWireRouteManager.Plan plan = manager.plan(routeConfig, first);
            assertEquals(1, plan.routes().size());
            assertEquals(0, plan.streams().size(), "The private reconnect smoke must not route application streams");
            PipeWireRouteManager.ApplyReport firstReport = manager.apply(routeConfig, first).get(20, TimeUnit.SECONDS);
            assertEquals(1, firstReport.routes());
            assertEquals(0, firstReport.streams());
            assertPrivateRouteGraph(manager, routeConfig, first, sinkName);

            assertTrue(first.sources().isEmpty(), "The private policy profile must not expose physical input sources");
            try (PipeWireLevelMonitor meter = new PipeWireLevelMonitor()) {
                meter.setTarget(firstSink);
                Process silence = new ProcessBuilder("pw-cat", "--playback", "--target", sinkName, "--raw",
                        "--format", "f32", "--rate", "48000", "--channels", "2", "--channel-map", "FL,FR", "/dev/zero")
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                try {
                    long meterDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!meter.reading().available() && System.nanoTime() < meterDeadline) Thread.sleep(50);
                    assertTrue(meter.reading().available(), meter.reading().message());
                    assertEquals(firstSink.name(), meter.reading().nodeName());
                    JsonNode meterGraph = new ObjectMapper().readTree(run("pw-dump", "-N"));
                    int sinkNodeId = -1;
                    int captureNodeId = -1;
                    for (JsonNode node : meterGraph) {
                        JsonNode properties = node.path("info").path("props");
                        if (firstSink.name().equals(properties.path("node.name").asText())) sinkNodeId = node.path("id").asInt(-1);
                        if (properties.path("stream.capture.sink").asBoolean(false)) captureNodeId = node.path("id").asInt(-1);
                    }
                    assertTrue(sinkNodeId >= 0, "The private sink must remain present during meter capture");
                    assertTrue(captureNodeId >= 0, "Output meter capture must explicitly target the sink monitor");
                    int selectedSinkId = sinkNodeId;
                    int selectedCaptureId = captureNodeId;
                    long sinkMonitorLinks = java.util.stream.StreamSupport.stream(meterGraph.spliterator(), false)
                            .filter(node -> node.path("info").path("props").path("link.output.node").asInt(-1) == selectedSinkId
                                    && node.path("info").path("props").path("link.input.node").asInt(-1) == selectedCaptureId)
                            .count();
                    assertTrue(sinkMonitorLinks >= 2,
                            "The meter capture must link from the selected private sink's monitor ports");
                } finally {
                    stop(silence);
                }
            }

            firstServer.destroy();
            assertTrue(firstServer.waitFor(5, TimeUnit.SECONDS), "The first private PipeWire server did not stop");
            assertTrue(waitForMonitorDisconnect(backend, Duration.ofSeconds(8)), "The route smoke monitor did not detect server loss");
            stop(firstPolicy);
            stop(firstSinkClient);

            secondServer = startPipeWire();
            assertTrue(waitForCore(secondServer, Duration.ofSeconds(8)), "The restarted private PipeWire server did not start");
            secondPolicy = startWirePlumber("second-wireplumber.log");
            assertTrue(secondPolicy.isAlive(), "The restarted private WirePlumber policy process did not stay alive");
            secondSinkClient = startVirtualSink(sinkConfig, sinkLog);
            AudioNode secondSink = waitForSink(backend, secondSinkClient, sinkName, sinkLog);
            run("wpctl", "set-default", Integer.toString(secondSink.id()));
            assertTrue(recoveredSnapshot.await(12, TimeUnit.SECONDS), "No connected private-server snapshot arrived after restart");
            PipeWireBackend.Snapshot afterReconnect = recovered.get();
            assertTrue(backend.connectionGeneration() > 0, "The backend did not advance its connection generation");
            assertTrue(afterReconnect.connected(), "The recovered snapshot must be connected");

            PipeWireRouteManager.ApplyReport recoveredReport = manager.reapplyAfterReconnect(routeConfig, afterReconnect)
                    .get(25, TimeUnit.SECONDS);
            assertEquals(1, recoveredReport.routes());
            assertEquals(0, recoveredReport.streams());
            assertPrivateRouteGraph(manager, routeConfig, afterReconnect, sinkName);
        } finally {
            try { manager.closeAsync().get(10, TimeUnit.SECONDS); }
            finally {
                backend.close();
                stop(firstSinkClient);
                stop(secondSinkClient);
                stop(firstPolicy);
                stop(secondPolicy);
                stop(firstServer);
                stop(secondServer);
            }
        }
    }
}
