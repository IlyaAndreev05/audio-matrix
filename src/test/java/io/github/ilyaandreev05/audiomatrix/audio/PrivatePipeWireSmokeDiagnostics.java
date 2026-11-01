package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Retains small state-only diagnostics from isolated private PipeWire smoke failures. */
final class PrivatePipeWireSmokeDiagnostics {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PrivatePipeWireSmokeDiagnostics() {}

    static String masterPostWriteFailure(int systemSinkId, double requestedVolume, boolean requestedMute,
                                        PipeWireBackend.Snapshot snapshot, AudioNode observed) {
        if (!isPrivatePipeWireRuntime())
            return "Diagnostics were not retained: runtime was not verified as the private route-smoke session.";
        String details = "Private system-master post-write diagnostics\n"
                + "sequence=setVolume(id, requestedVolume).get -> setMute(id, requestedMute).get -> refresh().get\n"
                + "expected id=" + systemSinkId + ", volume=" + requestedVolume + ", mute=" + requestedMute + "\n"
                + "snapshot connected=" + snapshot.connected() + ", capturedAtMillis=" + snapshot.capturedAtMillis()
                + ", defaultSinkId=" + snapshot.defaultSinkId() + ", node id=" + observed.id()
                + ", volume=" + observed.volume() + ", mute=" + observed.muted() + "\n";
        try {
            JsonNode root = JSON.readTree(PipeWireChannelMatrixRouteLiveSmokeSupport.run("pw-dump", "-N"));
            JsonNode node = null;
            int namedSinks = 0;
            for (JsonNode candidate : root) {
                JsonNode props = candidate.path("info").path("props");
                if (PipeWireBackend.SYSTEM_MIXER_SINK_NAME.equals(props.path("node.name").asText())) namedSinks++;
                if (candidate.path("id").asInt(-1) == systemSinkId) node = candidate;
            }
            if (node == null) throw new IOException("Private system sink was absent from post-write pw-dump");
            JsonNode properties = node.path("info").path("props");
            JsonNode rawProps = node.path("info").path("params").path("Props");
            String status = audioOnlyStatus(PipeWireChannelMatrixRouteLiveSmokeSupport.run("wpctl", "status"));
            details += "named system sink count=" + namedSinks + ", node name=" + properties.path("node.name").asText("")
                    + ", description=" + properties.path("node.description").asText("")
                    + ", media.class=" + properties.path("media.class").asText("")
                    + ", monitor.channel-volumes=" + properties.path("monitor.channel-volumes").asText("")
                    + "\npost-write info.params.Props:\n"
                    + JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rawProps)
                    + "\npost-write wpctl status:\n" + status;
        } catch (Exception | AssertionError captureFailure) {
            details += "\npost-write PipeWire state capture failed: "
                    + captureFailure.getClass().getSimpleName() + ": " + captureFailure.getMessage();
        }
        return retainPrivateDiagnostic("master-", details);
    }

    static String eventRouteFailure(double amplitude, long captureBytesBeforeStop,
                                    Map<String, Object> measured, Map<String, Integer> appOwned,
                                    RouteSmokeToneWriter eventToneWriter, Process eventRecorder,
                                    Path eventCapture, PipeWireBackend backend, PipeWireRouteManager manager) {
        if (!isPrivatePipeWireRuntime())
            return "\nDiagnostics were not retained: runtime was not verified as the private route-smoke session.";
        long captureBytesAfter = captureBytes(eventCapture);
        String links = privateCommandOutput("pw-link", "-l");
        String status = audioOnlyStatus(privateCommandOutput("wpctl", "status"));
        String dump = privateCommandOutput("pw-dump", "-N");
        String nodeLevels;
        try {
            nodeLevels = RouteSmokePcmSupport.nodeLevels(dump).toString();
        } catch (IOException failure) {
            nodeLevels = "<unavailable: " + failure.getClass().getSimpleName() + ">";
        }
        String liveStreams = backend.current().streams().stream()
                .filter(node -> node.name().startsWith("am_route_smoke_"))
                .map(node -> node.id() + ":" + node.name() + "/" + node.state())
                .toList().toString();
        String details = "Private route PCM failure diagnostics\n"
                + "event amplitude=" + amplitude + ", expected >0.02, frequency="
                + RouteSmokePcmSupport.EVENT_FREQUENCY + "\n"
                + "event tone processAlive=" + eventToneWriter.processAlive()
                + ", writerThreadAlive=" + eventToneWriter.writerAlive()
                + ", event recorder processAlive=" + eventRecorder.isAlive() + "\n"
                + "event capture bytes before app-tone stop=" + captureBytesBeforeStop
                + ", after check=" + captureBytesAfter + ", delta=" + (captureBytesAfter - captureBytesBeforeStop) + "\n"
                + "initial measured gains=" + measured + ", application owned nodes=" + appOwned + "\n"
                + "live route-smoke streams=" + liveStreams + "\n"
                + "manager active=" + manager.active() + ", status=" + manager.status()
                + ", lastReport=" + manager.lastReport() + "\n"
                + "branch diagnostics=" + manager.branchDiagnostics() + "\n"
                + "private pw-link -l:\n" + links + "\n"
                + "private wpctl status:\n" + status + "\n"
                + "private pw-dump node levels=" + nodeLevels;
        return "\n" + retainPrivateDiagnostic("route-", details);
    }

    private static boolean isPrivatePipeWireRuntime() {
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        return runtime != null && runtime.startsWith("/tmp/audio-matrix-private-route-")
                && runtime.equals(System.getenv("PIPEWIRE_RUNTIME_DIR"))
                && "pipewire-0".equals(System.getenv("PIPEWIRE_REMOTE"));
    }

    private static String privateCommandOutput(String... command) {
        try {
            return PipeWireChannelMatrixRouteLiveSmokeSupport.run(command);
        } catch (Exception failure) {
            return "<command failed: " + command[0] + " (" + failure.getClass().getSimpleName() + ")>";
        }
    }

    private static String audioOnlyStatus(String status) {
        if (status.startsWith("<command failed")) return status;
        StringBuilder audio = new StringBuilder();
        boolean inAudio = false;
        for (String line : status.split("\\R")) {
            String trimmed = line.strip();
            if ("Audio".equals(trimmed)) inAudio = true;
            if (inAudio && ("Video".equals(trimmed) || "Settings".equals(trimmed))) break;
            if (inAudio) audio.append(line).append('\n');
        }
        return audio.length() == 0 ? "<Audio section unavailable>" : audio.toString().stripTrailing();
    }

    private static long captureBytes(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ignored) {
            return -1;
        }
    }

    private static String retainPrivateDiagnostic(String prefix, String details) {
        try {
            Path directory = Path.of(System.getProperty("user.dir"), "build", "private-pipewire-smoke-diagnostics");
            Files.createDirectories(directory);
            Path artifact = Files.createTempFile(directory, prefix, ".txt");
            Files.writeString(artifact, details, StandardCharsets.UTF_8);
            return details + "\nartifact=" + artifact.toAbsolutePath();
        } catch (IOException | RuntimeException ignored) {
            return details + "\nartifact=<not retained>";
        }
    }
}
