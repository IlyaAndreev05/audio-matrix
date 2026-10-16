package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in test for the isolated service using its own PipeWire and policy-only WirePlumber session. */
class AudioMatrixEventsServiceLiveSmokeTest {
    @TempDir Path temporaryDirectory;

    @Test void servicePublishesOnlyItsExplicitlyNamedVirtualEventSink() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_EVENTS_SERVICE_SMOKE")),
                "Run scripts/audiomatrix-events-service-smoke.py for the isolated service smoke");
        Path runtime = Path.of(System.getenv("PIPEWIRE_RUNTIME_DIR")).toRealPath();
        assertTrue(runtime.startsWith(Path.of("/tmp").toRealPath()));
        assertEquals(runtime, Path.of(System.getenv("XDG_RUNTIME_DIR")).toRealPath());
        assertEquals("pipewire-0", System.getenv("PIPEWIRE_REMOTE"));

        Process pipewire = start("pipewire", temporaryDirectory.resolve("pipewire.log"));
        Process wireplumber = null;
        Process eventService = null;
        try {
            assertTrue(waitForCore(pipewire, Duration.ofSeconds(8)), "The isolated PipeWire server did not start");
            wireplumber = start("wireplumber", "--profile", "policy", temporaryDirectory.resolve("wireplumber.log"));
            assertTrue(wireplumber.isAlive(), "The private policy-only WirePlumber did not stay alive");
            String script = Path.of("src/main/systemd/user/audiomatrix-events-loopback.sh").toAbsolutePath().toString();
            eventService = start("/bin/sh", script, temporaryDirectory.resolve("event-service.log"));

            JsonNode graph = waitForEventSink(eventService, Duration.ofSeconds(8));
            JsonNode eventSink = findNode(graph, "audiomatrix_events");
            JsonNode discard = findNode(graph, "audiomatrix_events_discard");
            assertNotNull(eventSink);
            assertNotNull(discard);
            assertEquals("Audio/Sink", eventSink.path("info").path("props").path("media.class").asText());
            assertFalse(eventSink.path("info").path("props").path("node.autoconnect").asBoolean(true));
            assertFalse(discard.path("info").path("props").path("node.autoconnect").asBoolean(true));
            assertFalse(hasLinkFrom(graph, discard.path("id").asInt()),
                    "the discard side must remain unlinked when no explicit physical target exists");
            assertEquals(1, sinkCount(graph), "the private session should expose no physical or default sink");
        } finally {
            stop(eventService);
            stop(wireplumber);
            stop(pipewire);
        }
    }

    private Process start(String command, Path log) throws Exception {
        return start(command, new String[0], log);
    }

    private Process start(String command, String argument, Path log) throws Exception {
        return start(command, new String[]{argument}, log);
    }

    private Process start(String command, String first, String second, Path log) throws Exception {
        return start(command, new String[]{first, second}, log);
    }

    private Process start(String command, String[] arguments, Path log) throws Exception {
        java.util.ArrayList<String> commandLine = new java.util.ArrayList<>();
        commandLine.add(command);
        commandLine.addAll(java.util.List.of(arguments));
        return new ProcessBuilder(commandLine).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static boolean waitForCore(Process pipewire, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && pipewire.isAlive()) {
            Process probe = new ProcessBuilder("pw-cli", "info", "0").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (probe.waitFor(400, TimeUnit.MILLISECONDS) && probe.exitValue() == 0) return true;
            probe.destroyForcibly();
            Thread.sleep(100);
        }
        return false;
    }

    private static JsonNode waitForEventSink(Process service, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && service.isAlive()) {
            JsonNode graph = new ObjectMapper().readTree(new ProcessBuilder("pw-dump", "-N")
                    .redirectErrorStream(true).start().getInputStream());
            if (findNode(graph, "audiomatrix_events") != null && findNode(graph, "audiomatrix_events_discard") != null)
                return graph;
            Thread.sleep(100);
        }
        throw new AssertionError("The isolated event sink did not appear; service alive=" + service.isAlive());
    }

    private static JsonNode findNode(JsonNode graph, String name) {
        for (JsonNode item : graph) if (name.equals(item.path("info").path("props").path("node.name").asText())) return item;
        return null;
    }

    private static boolean hasLinkFrom(JsonNode graph, int nodeId) {
        for (JsonNode item : graph) {
            JsonNode props = item.path("info").path("props");
            if (props.path("link.output.node").asInt(-1) == nodeId || props.path("link.input.node").asInt(-1) == nodeId)
                return true;
        }
        return false;
    }

    private static int sinkCount(JsonNode graph) {
        int count = 0;
        for (JsonNode item : graph) if ("Audio/Sink".equals(item.path("info").path("props").path("media.class").asText())) count++;
        return count;
    }

    private static void stop(Process process) throws InterruptedException {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
    }
}
