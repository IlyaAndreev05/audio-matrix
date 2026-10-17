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

abstract class PipeWireBackendReconnectLiveSmokeSupport {
    @TempDir protected Path tempDir;
    protected Map<String, Object> routeConfig(String sinkName) {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        Map<String, Object> profile = profiles.getFirst();
        profile.put("active_buses", List.of("music"));
        profile.put("active_routes", List.of("private-reconnect"));
        config.put("devices", new ArrayList<>(List.of(DefaultConfig.map("id", "private-reconnect-null", "selector",
                DefaultConfig.map("kind", "name_equals", "value", sinkName), "role", "speaker", "volume", 1.0, "mute", false))));
        config.put("routes", new ArrayList<>(List.of(DefaultConfig.map("id", "private-reconnect", "from", "music", "to", "private-reconnect-null",
                "enabled", true, "volume", 0.0, "gain_db", 0.0, "channel_map", "stereo", "polarity", "normal"))));
        return config;
    }

    protected void assertPrivateRouteGraph(PipeWireRouteManager manager, Map<String, Object> config,
                                        PipeWireBackend.Snapshot snapshot, String sinkName) throws Exception {
        PipeWireRouteManager.RoutePlan route = manager.plan(config, snapshot).routes().getFirst();
        String busName = nodeNameByDescription("Audio Matrix bus music");
        String limiterInput = nodeNameByDescription("Audio Matrix physical output limiter " + route.sink().description());
        String limiterOutput = limiterInput.replace("_limiter_input", "_limiter_output");
        String graph = run("pw-link", "-l");
        List<PipeWireRouteManager.Link> busLinks = PipeWireLinkOperations.linksFromNode(graph, busName).stream()
                .filter(link -> link.input().startsWith(route.captureNodeName() + ":")).toList();
        assertEquals(2, busLinks.size(), "Both private bus channels should reach the route capture node");
        List<PipeWireRouteManager.Link> routeLinks = PipeWireLinkOperations.linksFromNode(graph, route.playbackNodeName());
        assertEquals(2, routeLinks.size(), "Both route channels should reach the output limiter");
        assertTrue(routeLinks.stream().allMatch(link -> link.input().startsWith(limiterInput + ":")), routeLinks.toString());
        List<PipeWireRouteManager.Link> outputLinks = PipeWireLinkOperations.linksFromNode(graph, limiterOutput);
        assertEquals(2, outputLinks.size(), "Both limiter channels should reach the private sink");
        assertTrue(outputLinks.stream().allMatch(link -> link.input().startsWith(sinkName + ":")), outputLinks.toString());
    }

    protected static Path isolatedRuntimePath() throws IOException {
        String runtime = System.getenv("PIPEWIRE_RUNTIME_DIR");
        Assumptions.assumeTrue(runtime != null && !runtime.isBlank(), "An isolated PIPEWIRE_RUNTIME_DIR is required");
        Path runtimePath = Path.of(runtime).toRealPath();
        String xdgRuntime = System.getenv("XDG_RUNTIME_DIR");
        Assumptions.assumeTrue(xdgRuntime != null && !xdgRuntime.isBlank(), "An isolated XDG_RUNTIME_DIR is required");
        assertTrue(runtimePath.equals(Path.of(xdgRuntime).toRealPath()), "XDG_RUNTIME_DIR must be isolated with PipeWire");
        assertTrue(runtimePath.startsWith(Path.of("/tmp").toRealPath()), "The smoke must stay in a private /tmp runtime");
        assertTrue("pipewire-0".equals(System.getenv("PIPEWIRE_REMOTE")), "The smoke must target its private pipewire-0 socket");
        return runtimePath;
    }

    protected Process startWirePlumber(String logName) throws IOException {
        Path log = tempDir.resolve(logName);
        return new ProcessBuilder("wireplumber", "--profile", "policy").redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
    }

    protected Process startVirtualSink(Path config, Path log) throws IOException {
        return new ProcessBuilder("pipewire", "-c", config.toString()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
    }

    protected AudioNode waitForSink(PipeWireBackend backend, Process client, String name, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline && client.isAlive()) {
            AudioNode sink = backend.refresh().get(5, TimeUnit.SECONDS).sinks().stream()
                    .filter(node -> name.equals(node.name())).findFirst().orElse(null);
            if (sink != null) return sink;
            Thread.sleep(100);
        }
        String diagnostic = Files.exists(log) ? Files.readString(log) : "";
        throw new IOException("Temporary null sink did not appear: " + diagnostic);
    }

    protected static String virtualSinkConfig(String inputName, String outputName) {
        return """
                context.properties = { log.level = 1 }
                context.spa-libs = {
                  audio.convert.* = audioconvert/libspa-audioconvert
                  support.* = support/libspa-support
                }
                context.modules = [
                  { name = libpipewire-module-rt args = { } flags = [ ifexists nofail ] }
                  { name = libpipewire-module-protocol-native }
                  { name = libpipewire-module-client-node }
                  { name = libpipewire-module-adapter }
                  { name = libpipewire-module-filter-chain args = {
                      node.description = "Audio Matrix private reconnect smoke"
                      media.name = "Audio Matrix private reconnect smoke"
                      filter.graph = { nodes = [ { type = builtin name = passthrough label = linear control = { "Mult" = 1 "Add" = 0 } } ] }
                      capture.props = { node.name = %s node.description = "Audio Matrix private reconnect null sink" media.class = Audio/Sink node.autoconnect = false audio.channels = 2 audio.position = [ FL FR ] }
                      playback.props = { node.name = %s node.description = "Audio Matrix private reconnect sink monitor" node.passive = true node.autoconnect = false audio.channels = 2 audio.position = [ FL FR ] }
                    }
                  }
                ]
                """.formatted(inputName, outputName);
    }

    protected String nodeNameByDescription(String description) throws Exception {
        for (JsonNode node : new ObjectMapper().readTree(run("pw-dump", "-N"))) {
            JsonNode props = node.path("info").path("props");
            if (description.equals(props.path("node.description").asText())) return props.path("node.name").asText();
        }
        throw new IOException("PipeWire node description was not found: " + description);
    }

    protected Map<String, Integer> nodeIdsByName() throws Exception {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (JsonNode node : new ObjectMapper().readTree(run("pw-dump", "-N"))) {
            JsonNode props = node.path("info").path("props");
            String name = props.path("node.name").asText("");
            int id = node.path("id").asInt(-1);
            if (!name.isBlank() && id >= 0) result.put(name, id);
        }
        return result;
    }

    protected String run(String... command) throws Exception {
        Path output = Files.createTempFile(tempDir, "pw-command-", ".out");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(6, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException(command[0] + " timed out"); }
            String text = Files.readString(output);
            if (process.exitValue() != 0) throw new IOException(command[0] + " failed: " + text);
            return text;
        } finally { Files.deleteIfExists(output); }
    }

    protected static boolean commandExists(String command) {
        for (String directory : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
            if (Files.isExecutable(Path.of(directory, command))) return true;
        return false;
    }

    protected static Process startPipeWire() throws IOException {
        return new ProcessBuilder("pipewire").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }

    protected static boolean waitForCore(Process server, Duration timeout) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && server.isAlive()) {
            Process check = new ProcessBuilder("pw-cli", "info", "0").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (check.waitFor(500, TimeUnit.MILLISECONDS) && check.exitValue() == 0) return true;
            check.destroyForcibly();
            Thread.sleep(100);
        }
        return false;
    }

    protected static boolean waitForMonitorDisconnect(PipeWireBackend backend, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (backend.backendMessage().startsWith("PipeWire monitor ")) return true;
            Thread.sleep(50);
        }
        return false;
    }

    protected static void stop(Process server) throws InterruptedException {
        if (server == null) return;
        if (server.isAlive()) server.destroy();
        if (server.isAlive() && !server.waitFor(2, TimeUnit.SECONDS)) server.destroyForcibly();
        if (server.isAlive()) server.waitFor(2, TimeUnit.SECONDS);
    }
}
