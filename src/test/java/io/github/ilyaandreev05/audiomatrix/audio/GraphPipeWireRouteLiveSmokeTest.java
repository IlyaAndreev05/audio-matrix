package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphPipeWireCompiler;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphPipeWireRouteLiveSmokeTest extends PipeWireChannelMatrixRouteLiveSmokeSupport {
    @Test
    void compiledGraphRunsOnlyIntoTemporaryNullSink() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE")));
        for (String command : List.of("pipewire", "pw-link", "pw-dump", "wpctl"))
            Assumptions.assumeTrue(commandExists(command), "Requires " + command);

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String sinkName = "am_graph_route_" + suffix + "_input";
        String sourceName = "am_graph_route_" + suffix + "_output";
        Path sinkConfig = tempDir.resolve("graph-null-sink.conf");
        Path sinkLog = tempDir.resolve("graph-null-sink.log");
        Files.writeString(sinkConfig, virtualSinkConfig(sinkName, sourceName));
        PipeWireBackend backend = new PipeWireBackend();
        PipeWireRouteManager manager = new PipeWireRouteManager();
        Process nullSinkClient = null;
        ObjectMapper mapper = new ObjectMapper();
        try {
            PipeWireBackend.Snapshot before = backend.refresh().get(5, TimeUnit.SECONDS);
            Assumptions.assumeTrue(before.connected(), "Requires the private PipeWire server");
            Assumptions.assumeTrue(mapper.readTree(run("pw-dump", "-N")).findValuesAsText("node.name").stream()
                    .noneMatch(name -> name.startsWith("audiomatrix_")), "Requires an empty Audio Matrix graph");

            nullSinkClient = new ProcessBuilder("pipewire", "-c", sinkConfig.toString())
                    .redirectErrorStream(true).redirectOutput(sinkLog.toFile()).start();
            AudioNode sink = waitForSink(backend, nullSinkClient, sinkName, sinkLog);
            Map<String, Object> config = graphConfig(sinkName);
            AudioGraph graph = graph();
            Map<String, Object> compiled = GraphPipeWireCompiler.compile(graph, config);
            PipeWireBackend.Snapshot current = backend.refresh().get(5, TimeUnit.SECONDS);
            List<AudioNode> sinks = new ArrayList<>(current.sinks());
            if (sinks.stream().noneMatch(node -> node.name().equals(sinkName))) sinks.add(sink);
            PipeWireBackend.Snapshot isolated = new PipeWireBackend.Snapshot(true, "", sinks, current.sources(),
                    List.of(), List.of(), Map.of(), current.defaultSinkId(), System.currentTimeMillis());
            PipeWireRouteManager.Plan plan = manager.plan(compiled, isolated);
            assertEquals(1, plan.routes().size());
            assertEquals(sinkName, plan.routes().getFirst().sink().name());

            PipeWireRouteManager.ApplyReport report = manager.apply(compiled, isolated).get(20, TimeUnit.SECONDS);
            assertEquals(1, report.routes());
            PipeWireRouteManager.RoutePlan route = plan.routes().getFirst();
            String limiterInput = nodeNameByDescription("Audio Matrix physical output limiter " + sink.description());
            String limiterOutput = limiterInput.replace("_limiter_input", "_limiter_output");
            List<PipeWireRouteManager.Link> routeLinks = PipeWireLinkOperations.linksFromNode(
                    run("pw-link", "-l"), route.playbackNodeName());
            assertEquals(2, routeLinks.size());
            assertTrue(routeLinks.stream().allMatch(link -> link.input().startsWith(limiterInput + ":")));
            List<PipeWireRouteManager.Link> finalLinks = PipeWireLinkOperations.linksFromNode(
                    run("pw-link", "-l"), limiterOutput);
            assertEquals(2, finalLinks.size());
            assertTrue(finalLinks.stream().allMatch(link -> link.input().startsWith(sinkName + ":")));

            PipeWireBackend.Snapshot after = backend.refresh().get(5, TimeUnit.SECONDS);
            assertEquals(before.defaultSinkId(), after.defaultSinkId());
            assertTrue(manager.active());
        } finally {
            try { manager.closeAsync().get(10, TimeUnit.SECONDS); }
            finally {
                backend.close();
                stop(nullSinkClient);
            }
        }
        assertTrue(!nodeExists(sinkName, sourceName), "The temporary virtual sink must be removed on close");
        assertTrue(mapper.readTree(run("pw-dump", "-N")).findValuesAsText("node.name").stream()
                .noneMatch(name -> name.startsWith("audiomatrix_")), "Audio Matrix graph nodes remain after cleanup");
    }

    private static Map<String, Object> graphConfig(String sinkName) {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", "graph-null", "selector",
                DefaultConfig.map("kind", "name_equals", "value", sinkName), "role", "speaker", "volume", 1.0, "mute", false));
        return config;
    }

    private static AudioGraph graph() {
        List<String> stereo = List.of("FL", "FR");
        SourceNode source = new SourceNode("graph-source", "Applications", "application:unassigned", stereo);
        GainNode gain = new GainNode("graph-gain", "Graph gain", stereo, 0.25, false);
        OutputNode output = new OutputNode("graph-output", "Private null sink", "graph-null", stereo);
        return new AudioGraph("graph-live-smoke", "Private graph smoke", List.of(source, gain, output), List.of(
                new GraphConnection("source-gain", "graph-source", "out", "graph-gain", "in", true, 1.0),
                new GraphConnection("gain-output", "graph-gain", "out", "graph-output", "in", true, 1.0)));
    }
}
