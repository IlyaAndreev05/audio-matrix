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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in private-daemon reconnect checks; WirePlumber runs with its policy-only profile and no hardware monitors. */
class PipeWireBackendReconnectLiveSmokeTest {
    @TempDir Path tempDir;

    @Test void backendPublishesFreshSnapshotAfterPrivatePipeWireRestarts() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_PIPEWIRE_RECONNECT_SMOKE")),
                "Run scripts/pipewire-reconnect-smoke.py for the isolated daemon restart smoke");
        String runtime = System.getenv("PIPEWIRE_RUNTIME_DIR");
        Assumptions.assumeTrue(runtime != null && !runtime.isBlank(), "An isolated PIPEWIRE_RUNTIME_DIR is required");
        Path runtimePath = Path.of(runtime).toRealPath();
        assertTrue(runtimePath.startsWith(Path.of("/tmp").toRealPath()), "The smoke must use a temporary PipeWire runtime");
        String xdgRuntime = System.getenv("XDG_RUNTIME_DIR");
        Assumptions.assumeTrue(xdgRuntime != null && !xdgRuntime.isBlank(), "An isolated XDG_RUNTIME_DIR is required");
        assertTrue(runtimePath.equals(Path.of(xdgRuntime).toRealPath()),
                "XDG_RUNTIME_DIR must point to the isolated PipeWire runtime");
        assertTrue("pipewire-0".equals(System.getenv("PIPEWIRE_REMOTE")), "The smoke must target its private pipewire-0 socket");

        Process firstServer = startPipeWire();
        Process secondServer = null;
        PipeWireBackend backend = new PipeWireBackend();
        CountDownLatch initialSnapshot = new CountDownLatch(1);
        CountDownLatch recoveredSnapshot = new CountDownLatch(1);
        AtomicReference<PipeWireBackend.Snapshot> recovered = new AtomicReference<>();
        backend.start(snapshot -> {
            if (snapshot.connected() && backend.connectionGeneration() == 0) initialSnapshot.countDown();
            if (snapshot.connected() && backend.connectionGeneration() > 0) {
                recovered.set(snapshot);
                recoveredSnapshot.countDown();
            }
        });
        try {
            assertTrue(waitForCore(firstServer, Duration.ofSeconds(8)), "The private PipeWire server did not start");
            assertTrue(initialSnapshot.await(8, TimeUnit.SECONDS), "Audio Matrix did not publish the initial private-server snapshot");

            firstServer.destroy();
            assertTrue(firstServer.waitFor(5, TimeUnit.SECONDS), "The private PipeWire server did not stop");
            assertTrue(waitForMonitorDisconnect(backend, Duration.ofSeconds(8)), "Audio Matrix monitor did not detect the private server stopping");

            secondServer = startPipeWire();
            assertTrue(waitForCore(secondServer, Duration.ofSeconds(8)), "The restarted private PipeWire server did not start");
            assertTrue(recoveredSnapshot.await(12, TimeUnit.SECONDS), "Audio Matrix did not publish a fresh connected snapshot after reconnect");
            assertTrue(backend.connectionGeneration() > 0, "The backend did not advance its connection generation");
            assertTrue(recovered.get() != null && recovered.get().connected(), "The recovered snapshot must be connected");
        } finally {
            backend.close();
            stop(firstServer);
            if (secondServer != null) stop(secondServer);
        }
    }

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

    private Map<String, Object> routeConfig(String sinkName) {
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

    private void assertPrivateRouteGraph(PipeWireRouteManager manager, Map<String, Object> config,
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

    private static Path isolatedRuntimePath() throws IOException {
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

    private Process startWirePlumber(String logName) throws IOException {
        Path log = tempDir.resolve(logName);
        return new ProcessBuilder("wireplumber", "--profile", "policy").redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
    }

    private Process startVirtualSink(Path config, Path log) throws IOException {
        return new ProcessBuilder("pipewire", "-c", config.toString()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
    }

    private AudioNode waitForSink(PipeWireBackend backend, Process client, String name, Path log) throws Exception {
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

    private static String virtualSinkConfig(String inputName, String outputName) {
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

    private String nodeNameByDescription(String description) throws Exception {
        for (JsonNode node : new ObjectMapper().readTree(run("pw-dump", "-N"))) {
            JsonNode props = node.path("info").path("props");
            if (description.equals(props.path("node.description").asText())) return props.path("node.name").asText();
        }
        throw new IOException("PipeWire node description was not found: " + description);
    }

    private String run(String... command) throws Exception {
        Path output = Files.createTempFile(tempDir, "pw-command-", ".out");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(6, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException(command[0] + " timed out"); }
            String text = Files.readString(output);
            if (process.exitValue() != 0) throw new IOException(command[0] + " failed: " + text);
            return text;
        } finally { Files.deleteIfExists(output); }
    }

    private static boolean commandExists(String command) {
        for (String directory : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
            if (Files.isExecutable(Path.of(directory, command))) return true;
        return false;
    }

    private static Process startPipeWire() throws IOException {
        return new ProcessBuilder("pipewire").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }

    private static boolean waitForCore(Process server, Duration timeout) throws IOException, InterruptedException {
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

    private static boolean waitForMonitorDisconnect(PipeWireBackend backend, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (backend.backendMessage().startsWith("PipeWire monitor ")) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static void stop(Process server) throws InterruptedException {
        if (server.isAlive()) server.destroy();
        if (server.isAlive() && !server.waitFor(2, TimeUnit.SECONDS)) server.destroyForcibly();
        if (server.isAlive()) server.waitFor(2, TimeUnit.SECONDS);
    }
}
