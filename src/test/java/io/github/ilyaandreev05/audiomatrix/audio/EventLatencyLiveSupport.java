package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Client-only plumbing: an already-running private core is mandatory, and every target is virtual. */
abstract class EventLatencyLiveSupport extends PipeWireChannelMatrixRouteLiveSmokeSupport {
    static final String PLAYER = "am_event_latency_player";
    static final String CAPTURE = "am_event_latency_capture";

    static void requirePrivateRuntime() throws IOException {
        String pipewireRuntime = System.getenv("PIPEWIRE_RUNTIME_DIR");
        String xdgRuntime = System.getenv("XDG_RUNTIME_DIR");
        assertNotNull(pipewireRuntime, "PIPEWIRE_RUNTIME_DIR must explicitly select the private core");
        assertNotNull(xdgRuntime, "XDG_RUNTIME_DIR must explicitly select the same private root");
        assertFalse(pipewireRuntime.isBlank() || xdgRuntime.isBlank(), "Private runtime paths must not be blank");
        assertTrue(Path.of(pipewireRuntime).isAbsolute() && Path.of(xdgRuntime).isAbsolute(),
                "Private runtime paths must be absolute");
        Path runtime = Path.of(pipewireRuntime).toRealPath();
        Path temporaryRoot = Path.of("/tmp").toRealPath();
        assertEquals(runtime, Path.of(xdgRuntime).toRealPath(), "Both clients must use the same private runtime root");
        assertTrue(runtime.startsWith(temporaryRoot) && !runtime.equals(temporaryRoot),
                "Refusing any default/live runtime: the core must live in a private directory under /tmp");
        assertEquals("pipewire-0", System.getenv("PIPEWIRE_REMOTE"),
                "The private harness must explicitly select its local pipewire-0 socket");
        Path socket = runtime.resolve("pipewire-0");
        assertTrue(Files.exists(socket) && !Files.isSymbolicLink(socket),
                "The private socket must exist inside the selected root, not point to another core");
    }

    static void assertPrivateHarnessSeedsOnly() throws Exception {
        Set<String> seeds = Set.of("am_private_primary", "am_private_event",
                "audiomatrix_private_primary_monitor", "audiomatrix_private_event_monitor");
        for (JsonNode item : new ObjectMapper().readTree(run("pw-dump", "-N"))) {
            JsonNode props = item.path("info").path("props");
            String mediaClass = props.path("media.class").asText("");
            String node = props.path("node.name").asText("");
            assertFalse(props.has("device.api") || props.has("api.alsa.card") || props.has("api.bluez5.address"),
                    "The private core must not discover hardware: " + props);
            if (mediaClass.startsWith("Audio/") || (mediaClass.startsWith("Stream/") && mediaClass.endsWith("/Audio")))
                assertTrue(seeds.contains(node), "Unexpected audio node in private PipeWire core: " + props);
        }
    }

    void startSink(String name, String monitor, PipeWireBackend backend, List<Process> clients) throws Exception {
        Path config = tempDir.resolve(name + ".conf");
        Files.writeString(config, virtualSinkConfig(name, monitor));
        Path log = tempDir.resolve(name + ".log");
        Process sink = RouteSmokePcmSupport.startClient(List.of("pipewire", "-c", config.toString()), log);
        clients.add(sink);
        waitForSink(backend, sink, name, log);
    }

    Process startPlayer(List<Process> clients) throws IOException {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("node.name", PLAYER);
        properties.put("media.name", "Private Audio Matrix event latency impulses");
        properties.put("application.id", RouteSmokePcmSupport.EVENT_APP_ID);
        properties.put("node.autoconnect", false);
        properties.put("stream.dont-remix", true);
        Process player = RouteSmokePcmSupport.startClient(List.of("pw-cat", "--playback",
                "--target=" + RouteSmokePcmSupport.ESS_MONITOR, "--latency=256/48000",
                "--properties", new ObjectMapper().writeValueAsString(properties),
                "--raw", "--format=f32", "--rate=48000", "--channels=2", "--channel-map=FL,FR", "-"),
                tempDir.resolve("event-player.log"));
        clients.add(player);
        return player;
    }

    Process startRecorder(Path pcm, List<Process> clients) throws IOException {
        String properties = new ObjectMapper().writeValueAsString(Map.of("node.name", CAPTURE,
                "node.autoconnect", false, "stream.dont-remix", true));
        Process recorder = new ProcessBuilder("pw-cat", "--record", "--target=0", "--latency=256/48000",
                "--properties", properties, "--raw", "--format=f32", "--rate=48000", "--channels=2",
                "--channel-map=FL,FR", "-").redirectError(tempDir.resolve("event-recorder.log").toFile())
                .redirectOutput(pcm.toFile()).start();
        clients.add(recorder);
        return recorder;
    }

    List<String> connectPlayerToEventInput() throws Exception {
        Map<String, String> player = awaitChannels(PLAYER, "-o");
        Map<String, String> sink = awaitChannels(RouteSmokePcmSupport.ESS_MONITOR, "-i");
        List<String> expected = List.of(link(player.get("FL"), sink.get("FL")), link(player.get("FR"), sink.get("FR")));
        assertTrue(currentLinks().stream().filter(value -> value.startsWith(PLAYER + ":")).allMatch(expected::contains),
                "The player must have no destination other than the virtual event input");
        connectMissing(expected);
        return expected;
    }

    List<String> connectRecorderTaps() throws Exception {
        Map<String, String> capture = awaitChannels(CAPTURE, "-i");
        Map<String, String> reference = awaitChannels(RouteSmokePcmSupport.ESS_MONITOR, "-o");
        Map<String, String> routed = awaitChannels(RouteSmokePcmSupport.EVENT_MONITOR, "-o");
        List<String> expected = List.of(link(reference.get("FL"), capture.get("FL")),
                link(routed.get("FL"), capture.get("FR")));
        assertTrue(currentLinks().stream().noneMatch(value -> destinationNode(value).equals(CAPTURE)),
                "The target=0, autoconnect=false recorder must initially have no source links");
        connectMissing(expected);
        return expected;
    }

    private void connectMissing(List<String> expected) throws Exception {
        Set<String> observed = new LinkedHashSet<>(currentLinks());
        for (String value : expected) {
            if (observed.contains(value)) continue;
            String[] ports = value.split(" -> ", 2);
            run("pw-link", ports[0], ports[1]);
        }
        assertTrue(currentLinks().containsAll(expected), "Both channel links must actually be present: " + expected);
    }

    private Map<String, String> awaitChannels(String name, String direction) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            List<String> ports = new ArrayList<>();
            for (String line : run("pw-link", direction).split("\\R")) {
                String port = line.trim();
                if (port.startsWith(name + ":")) ports.add(port);
            }
            Map<String, String> channels = PipeWireRouteStaticSupport01.channelPorts(ports);
            if (channels.containsKey("FL") && channels.containsKey("FR")) return channels;
            Thread.sleep(100);
        }
        throw new IOException("Both actual channel ports did not appear for " + name + " (" + direction + ")");
    }

    static List<String> currentLinks() throws Exception {
        return RouteSmokePcmSupport.links(run("pw-link", "-l"));
    }

    private static String link(String output, String input) {
        return output + " -> " + input;
    }

    static String destinationNode(String link) {
        String destination = link.substring(link.indexOf(" -> ") + 4);
        return destination.substring(0, destination.lastIndexOf(':'));
    }

    String readLog(String name) throws IOException {
        return Files.readString(tempDir.resolve(name));
    }
}
