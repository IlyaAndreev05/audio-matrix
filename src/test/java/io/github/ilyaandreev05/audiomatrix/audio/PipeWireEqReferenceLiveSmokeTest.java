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

class PipeWireEqReferenceLiveSmokeTest extends PipeWireChannelMatrixRouteLiveSmokeSupport {
@Test
    void eqReferenceModeSwitchesTheLiveGraphAndRestoresItOnATemporaryNullSink() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE")),
                "Set AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE=1 to run the live PipeWire graph check");
        for (String command : List.of("pipewire", "pw-loopback", "pw-link", "pw-dump", "wpctl"))
            Assumptions.assumeTrue(commandExists(command), "Requires " + command);

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String sinkName = "am_ab_route_" + suffix + "_input";
        String monitorName = "am_ab_route_" + suffix + "_output";
        Path sinkConfig = tempDir.resolve("ab-null-sink.conf");
        Path sinkLog = tempDir.resolve("ab-null-sink.log");
        Files.writeString(sinkConfig, virtualSinkConfig(sinkName, monitorName));

        ObjectMapper mapper = new ObjectMapper();
        PipeWireBackend backend = new PipeWireBackend();
        PipeWireRouteManager manager = new PipeWireRouteManager();
        Process nullSinkClient = null;
        AudioNode originalDefault = null;
        List<String> originalOwnedNodes = List.of();
        try {
            PipeWireBackend.Snapshot before = backend.refresh().get(5, TimeUnit.SECONDS);
            Assumptions.assumeTrue(before.connected() && !before.sinks().isEmpty(), "Requires an active PipeWire server");
            List<String> existingNodes = mapper.readTree(run("pw-dump", "-N")).findValuesAsText("node.name");
            Assumptions.assumeTrue(existingNodes.stream().noneMatch(name -> name.startsWith("audiomatrix_")),
                    "Requires no other active Audio Matrix graph");
            originalOwnedNodes = existingNodes.stream().filter(name -> name.startsWith("audiomatrix_")).sorted().toList();
            originalDefault = before.sinks().stream().filter(node -> node.id() == before.defaultSinkId()).findFirst().orElse(null);

            nullSinkClient = new ProcessBuilder("pipewire", "-c", sinkConfig.toString())
                    .redirectErrorStream(true).redirectOutput(sinkLog.toFile()).start();
            AudioNode nullSink = waitForSink(backend, nullSinkClient, sinkName, sinkLog);
            Map<String, Object> config = routeConfig(sinkName);
            @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) config.get("dsp");
            dsp.put("global_eq", DefaultConfig.map("enabled", true, "filters", List.of(
                    DefaultConfig.map("type", "peaking", "frequency_hz", 1000.0, "gain_db", 3.0, "q", 1.0, "enabled", true))));
            byte[] storedConfig = mapper.writeValueAsBytes(config);

            List<AudioNode> isolatedSinks = new ArrayList<>(before.sinks());
            isolatedSinks.add(nullSink);
            PipeWireBackend.Snapshot isolated = new PipeWireBackend.Snapshot(true, "", isolatedSinks,
                    before.sources(), List.of(), List.of(), Map.of(), before.defaultSinkId(), System.currentTimeMillis());
            PipeWireRouteManager.ApplyReport processed = manager.apply(config, isolated).get(20, TimeUnit.SECONDS);
            assertEquals(1, processed.routes());
            assertEquals(0, processed.streams(), "The A/B graph smoke must not route application streams");

            String eqDescription = "Audio Matrix physical output EQ " + nullSink.description();
            String limiterDescription = "Audio Matrix physical output limiter " + nullSink.description();
            String eqInput = nodeNameByDescription(eqDescription);
            String eqOutput = eqInput.replace("_dsp_input", "_dsp_output");
            String limiterInput = nodeNameByDescription(limiterDescription);
            String limiterOutput = limiterInput.replace("_limiter_input", "_limiter_output");
            assertTrue(nodeExists(eqInput, eqOutput), "A must create the output EQ filter-chain");
            assertTrue(nodeExists(limiterInput, limiterOutput), "A must retain the output limiter");

            double matchGain = manager.maximumEqBoostDb(config, isolated, nullSink);
            assertEquals(3.0, matchGain, 0.05);
            Map<String, Object> reference = DspComparisonConfig.reference(config, matchGain);
            @SuppressWarnings("unchecked") List<Map<String, Object>> referenceBuses =
                    (List<Map<String, Object>>) reference.get("buses");
            Map<String, Object> referenceMusic = referenceBuses.stream()
                    .filter(bus -> "music".equals(bus.get("id"))).findFirst().orElseThrow();
            assertEquals(Math.pow(10.0, -matchGain / 20.0), ((Number) referenceMusic.get("volume")).doubleValue(), 1e-9);
            PipeWireRouteManager.ApplyReport bypassed = manager.apply(reference, isolated).get(20, TimeUnit.SECONDS);
            assertEquals(1, bypassed.routes());
            assertEquals(0, bypassed.streams());
            awaitNodeState(eqInput, false);
            awaitNodeState(eqOutput, false);
            assertTrue(nodeExists(limiterInput, limiterOutput), "B must keep the output limiter active");
            assertTrue(Boolean.TRUE.equals(((Map<?, ?>) dsp.get("global_eq")).get("enabled")),
                    "The stored A configuration must stay enabled during B");

            PipeWireRouteManager.ApplyReport restored = manager.apply(config, isolated).get(20, TimeUnit.SECONDS);
            assertEquals(1, restored.routes());
            assertEquals(0, restored.streams());
            awaitNodeState(eqInput, true);
            awaitNodeState(eqOutput, true);
            assertTrue(nodeExists(limiterInput, limiterOutput), "A must restore with the limiter still active");
            assertEquals(java.util.Arrays.toString(storedConfig), java.util.Arrays.toString(mapper.writeValueAsBytes(config)),
                    "A/B switching must not mutate the stored YAML configuration");

            PipeWireBackend.Snapshot after = backend.refresh().get(5, TimeUnit.SECONDS);
            assertEquals(before.defaultSinkId(), after.defaultSinkId());
            AudioNode defaultAfter = after.sinks().stream().filter(node -> node.id() == after.defaultSinkId()).findFirst().orElseThrow();
            if (originalDefault != null && Double.isFinite(originalDefault.volume()) && Double.isFinite(defaultAfter.volume()))
                assertEquals(originalDefault.volume(), defaultAfter.volume(), 0.01, "The physical default sink volume must remain unchanged");
            if (originalDefault != null) assertEquals(originalDefault.muted(), defaultAfter.muted());
        } finally {
            try { manager.closeAsync().get(10, TimeUnit.SECONDS); }
            finally {
                backend.close();
                stop(nullSinkClient);
            }
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline && nodeExists(sinkName, monitorName)) Thread.sleep(100);
        List<String> remaining = mapper.readTree(run("pw-dump", "-N")).findValuesAsText("node.name").stream()
                .filter(name -> name.startsWith("audiomatrix_")).sorted().toList();
        assertEquals(originalOwnedNodes, remaining, "A/B graph nodes must be removed after cleanup");
        assertFalse(nodeExists(sinkName, monitorName), "The temporary virtual sink must be removed on close");
    }
}
