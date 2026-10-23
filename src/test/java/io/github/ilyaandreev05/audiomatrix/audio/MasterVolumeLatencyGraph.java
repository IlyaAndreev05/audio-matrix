package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Private PipeWire fixtures used by the master-volume latency measurement. */
final class MasterVolumeLatencyGraph {
    private static final ObjectMapper JSON = new ObjectMapper();

    private MasterVolumeLatencyGraph() {}

    static Process startSink(Path tempDir, String sink, String config, List<Process> clients) throws Exception {
        Path configFile = tempDir.resolve(sink + ".conf");
        Files.writeString(configFile, config);
        Path log = tempDir.resolve(sink + ".log");
        Process process = new ProcessBuilder("pipewire", "-c", configFile.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        clients.add(process);
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline && process.isAlive() && !nodeExists(sink)) Thread.sleep(100);
        if (!nodeExists(sink))
            throw new IllegalStateException("The temporary sink did not appear: " + sink + "\n" + Files.readString(log));
        return process;
    }

    static int awaitNodeId(String name) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            for (JsonNode node : JSON.readTree(run("pw-dump", "-N")))
                if (name.equals(node.path("info").path("props").path("node.name").asText()))
                    return node.path("id").asInt();
            Thread.sleep(100);
        }
        throw new IllegalStateException("PipeWire node did not appear: " + name);
    }

    static boolean nodeExists(String name) throws Exception {
        for (JsonNode node : JSON.readTree(run("pw-dump", "-N")))
            if (name.equals(node.path("info").path("props").path("node.name").asText())) return true;
        return false;
    }

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(8, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException(command[0] + " timed out"); }
        if (process.exitValue() != 0) throw new IllegalStateException(command[0] + " failed: " + output);
        return output;
    }
}
