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

abstract class PipeWireChannelMatrixRouteLiveSmokeSupport {
    @TempDir protected Path tempDir;

        protected static Map<String, Object> routeConfig(String sinkName) {
            Map<String, Object> config = DefaultConfig.create();
            @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
            Map<String, Object> profile = profiles.getFirst();
            profile.put("active_buses", List.of("music"));
            profile.put("active_routes", List.of("matrix-smoke"));
            config.put("devices", new ArrayList<>(List.of(DefaultConfig.map("id", "matrix-null", "selector",
                    DefaultConfig.map("kind", "name_equals", "value", sinkName), "role", "speaker", "volume", 1.0, "mute", false))));
            config.put("routes", new ArrayList<>(List.of(DefaultConfig.map("id", "matrix-smoke", "from", "music", "to", "matrix-null",
                    "enabled", true, "volume", 0.0, "gain_db", 0.0, "channel_map", "stereo", "polarity", "normal",
                    "channel_matrix", DefaultConfig.map("inputs", List.of("FL", "FR"), "outputs", List.of("FL", "FR"),
                            "coefficients", List.of(List.of(0.5, 0.5), List.of(-0.25, 0.25)))))));
            return config;
        }


    protected AudioNode waitForSink(PipeWireBackend backend, Process client, String name, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && client.isAlive()) {
            AudioNode sink = backend.refresh().get(5, TimeUnit.SECONDS).sinks().stream()
                    .filter(node -> name.equals(node.name())).findFirst().orElse(null);
            if (sink != null) return sink;
            Thread.sleep(100);
        }
        String diagnostic = Files.exists(log) ? Files.readString(log) : "";
        throw new IOException("Temporary null sink did not appear: " + diagnostic);
    }

    protected String nodeNameByDescription(String description) throws Exception {
        for (JsonNode node : new ObjectMapper().readTree(run("pw-dump", "-N"))) {
            JsonNode props = node.path("info").path("props");
            if (description.equals(props.path("node.description").asText())) return props.path("node.name").asText();
        }
        throw new IOException("PipeWire node description was not found: " + description);
    }

    protected boolean nodeExists(String... names) throws Exception {
        List<String> current = new ObjectMapper().readTree(run("pw-dump", "-N")).findValuesAsText("node.name");
        for (String name : names) if (current.contains(name)) return true;
        return false;
    }

    protected void awaitNodeState(String name, boolean expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline && nodeExists(name) != expected) Thread.sleep(100);
        assertEquals(expected, nodeExists(name), "Unexpected live PipeWire node state: " + name);
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
                      node.description = "Audio Matrix isolated matrix-route smoke"
                      media.name = "Audio Matrix isolated matrix-route smoke"
                      filter.graph = { nodes = [ { type = builtin name = passthrough label = linear control = { "Mult" = 1 "Add" = 0 } } ] }
                      capture.props = { node.name = %s node.description = "Audio Matrix isolated null sink" media.class = Audio/Sink node.autoconnect = false audio.channels = 2 audio.position = [ FL FR ] }
                      playback.props = { node.name = %s node.description = "Audio Matrix isolated null sink monitor" node.passive = true node.autoconnect = false audio.channels = 2 audio.position = [ FL FR ] }
                    }
                  }
                ]
                """.formatted(inputName, outputName);
    }

    protected static boolean commandExists(String command) {
        for (String directory : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
            if (Files.isExecutable(Path.of(directory, command))) return true;
        return false;
    }

    protected static String run(String... command) throws Exception {
        Path output = Files.createTempFile("audiomatrix-matrix-route-smoke-", ".out");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException(command[0] + " timed out"); }
            String text = Files.readString(output);
            if (process.exitValue() != 0) throw new IOException(command[0] + " failed: " + text);
            return text;
        } finally { Files.deleteIfExists(output); }
    }

    protected static void stop(Process process) throws InterruptedException {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        if (!process.waitFor(1, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS); }
    }
}
