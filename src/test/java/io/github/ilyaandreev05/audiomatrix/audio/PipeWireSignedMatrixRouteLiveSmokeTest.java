package io.github.ilyaandreev05.audiomatrix.audio;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireSignedMatrixRouteLiveSmokeTest extends PipeWireChannelMatrixRouteLiveSmokeSupport {
@Test
    void signedMatrixRouteConnectsOnlyToTemporaryNullSinkAndClears() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE")),
                "Set AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE=1 to run the live PipeWire graph check");
        for (String command : List.of("pipewire", "pw-loopback", "pw-link", "pw-dump", "wpctl"))
            Assumptions.assumeTrue(commandExists(command), "Requires " + command);

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String sinkName = "am_matrix_route_" + suffix + "_input";
        String sourceName = "am_matrix_route_" + suffix + "_output";
        Path sinkConfig = tempDir.resolve("null-sink.conf");
        Path sinkLog = tempDir.resolve("null-sink.log");
        Files.writeString(sinkConfig, virtualSinkConfig(sinkName, sourceName));

        ObjectMapper mapper = new ObjectMapper();
        PipeWireBackend backend = new PipeWireBackend();
        PipeWireRouteManager manager = new PipeWireRouteManager();
        Process nullSinkClient = null;
        AudioNode originalDefault = null;
        try {
            PipeWireBackend.Snapshot before = backend.refresh().get(5, TimeUnit.SECONDS);
            Assumptions.assumeTrue(before.connected() && !before.sinks().isEmpty(), "Requires an active PipeWire server");
            List<String> existingOwnedNodes = mapper.readTree(run("pw-dump", "-N"))
                    .findValuesAsText("node.name").stream().filter(name -> name.startsWith("audiomatrix_")).toList();
            Assumptions.assumeTrue(existingOwnedNodes.isEmpty(), "Requires no other active Audio Matrix route graph");
            originalDefault = before.sinks().stream().filter(node -> node.id() == before.defaultSinkId()).findFirst().orElse(null);

            nullSinkClient = new ProcessBuilder("pipewire", "-c", sinkConfig.toString())
                    .redirectErrorStream(true).redirectOutput(sinkLog.toFile()).start();
            AudioNode nullSink = waitForSink(backend, nullSinkClient, sinkName, sinkLog);

            Map<String, Object> config = routeConfig(sinkName);
            List<AudioNode> isolatedSinks = new ArrayList<>(before.sinks());
            isolatedSinks.add(nullSink);
            PipeWireBackend.Snapshot isolated = new PipeWireBackend.Snapshot(true, "", isolatedSinks,
                    before.sources(), List.of(), List.of(), Map.of(), before.defaultSinkId(), System.currentTimeMillis());
            PipeWireRouteManager.Plan plan = manager.plan(config, isolated);
            assertEquals(1, plan.routes().size());
            assertEquals(0, plan.streams().size(), "The matrix smoke must not route application streams");
            PipeWireRouteManager.RoutePlan route = plan.routes().getFirst();

            PipeWireRouteManager.ApplyReport report = manager.apply(config, isolated).get(20, TimeUnit.SECONDS);
            assertEquals(1, report.routes());
            assertEquals(0, report.streams());

            String graph = run("pw-link", "-l");
            String busNode = nodeNameByDescription("Audio Matrix bus music");
            List<PipeWireRouteManager.Link> intoMatrix = PipeWireLinkOperations.linksFromNode(graph, busNode).stream()
                    .filter(link -> link.input().startsWith(route.matrixInputNodeName() + ":")).toList();
            assertEquals(2, intoMatrix.size(), "Both source channels should feed the matrix capture node");
            List<PipeWireRouteManager.Link> afterMatrix = PipeWireLinkOperations.linksFromNode(graph, route.matrixOutputNodeName());
            assertEquals(2, afterMatrix.size(), "Both matrix outputs should feed the route capture node");
            assertTrue(afterMatrix.stream().allMatch(link -> link.input().startsWith(route.captureNodeName() + ":")),
                    afterMatrix.toString());
            List<PipeWireRouteManager.Link> routeOutputs = PipeWireLinkOperations.linksFromNode(graph, route.playbackNodeName());
            assertEquals(2, routeOutputs.size(), "The route should connect both channels to the output limiter");
            String limiterInput = nodeNameByDescription("Audio Matrix physical output limiter " + nullSink.description());
            String limiterOutput = limiterInput.replace("_limiter_input", "_limiter_output");
            assertTrue(routeOutputs.stream().allMatch(link -> link.input().startsWith(limiterInput + ":")), routeOutputs.toString());
            List<PipeWireRouteManager.Link> limitedOutputs = PipeWireLinkOperations.linksFromNode(graph, limiterOutput);
            assertEquals(2, limitedOutputs.size(), "The output limiter should connect both channels to the virtual null sink");
            assertTrue(limitedOutputs.stream().allMatch(link -> link.input().startsWith(sinkName + ":")), limitedOutputs.toString());

            PipeWireRouteManager.ApplyReport recoveredReport = manager.reapplyAfterReconnect(config, isolated)
                    .get(20, TimeUnit.SECONDS);
            assertEquals(1, recoveredReport.routes());
            assertEquals(0, recoveredReport.streams(), "The reconnect smoke must not route application streams");
            assertTrue(manager.active(), "The route graph should be active after reconnect recovery");
            String recoveredGraph = run("pw-link", "-l");
            List<PipeWireRouteManager.Link> recoveredIntoMatrix = PipeWireLinkOperations.linksFromNode(recoveredGraph, busNode).stream()
                    .filter(link -> link.input().startsWith(route.matrixInputNodeName() + ":")).toList();
            assertEquals(2, recoveredIntoMatrix.size(), "Both source channels should be restored after reconnect");
            List<PipeWireRouteManager.Link> recoveredRouteOutputs = PipeWireLinkOperations.linksFromNode(recoveredGraph, route.playbackNodeName());
            assertEquals(2, recoveredRouteOutputs.size(), "The route should reconnect to the output limiter after reconnect");
            assertTrue(recoveredRouteOutputs.stream().allMatch(link -> link.input().startsWith(limiterInput + ":")),
                    recoveredRouteOutputs.toString());
            List<PipeWireRouteManager.Link> recoveredLimitedOutputs = PipeWireLinkOperations.linksFromNode(recoveredGraph, limiterOutput);
            assertEquals(2, recoveredLimitedOutputs.size(), "The limiter should reconnect to the temporary sink after reconnect");
            assertTrue(recoveredLimitedOutputs.stream().allMatch(link -> link.input().startsWith(sinkName + ":")),
                    recoveredLimitedOutputs.toString());

            PipeWireBackend.Snapshot after = backend.refresh().get(5, TimeUnit.SECONDS);
            assertEquals(before.defaultSinkId(), after.defaultSinkId());
            AudioNode defaultAfter = after.sinks().stream().filter(node -> node.id() == after.defaultSinkId()).findFirst().orElseThrow();
            if (originalDefault != null && Double.isFinite(originalDefault.volume()) && Double.isFinite(defaultAfter.volume()))
                assertEquals(originalDefault.volume(), defaultAfter.volume(), 0.01, "Physical default sink volume must remain unchanged");
            if (originalDefault != null) assertEquals(originalDefault.muted(), defaultAfter.muted());
        } finally {
            try { manager.closeAsync().get(10, TimeUnit.SECONDS); }
            finally {
                backend.close();
                stop(nullSinkClient);
            }
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline && nodeExists(sinkName, sourceName)) Thread.sleep(100);
        List<String> remaining = mapper.readTree(run("pw-dump", "-N")).findValuesAsText("node.name").stream()
                .filter(name -> name.startsWith("audiomatrix_")).toList();
        assertTrue(remaining.isEmpty(), "Audio Matrix nodes remain after cleanup: " + remaining);
        assertFalse(nodeExists(sinkName, sourceName), "The temporary virtual sink must be removed on close");
    }
}
