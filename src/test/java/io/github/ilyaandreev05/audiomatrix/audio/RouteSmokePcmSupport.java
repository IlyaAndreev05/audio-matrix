package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Private PipeWire plumbing for the routing smoke: fixture, tones, recorders, and PCM measurement. */
final class RouteSmokePcmSupport {
    static final int SAMPLE_RATE = 48000;
    static final int CHANNELS = 2;
    static final double TONE_AMPLITUDE = 0.25;
    static final double APP_FREQUENCY = 440.0;
    static final double EVENT_FREQUENCY = 1000.0;
    static final String APP_ID = "org.example.AudioMatrixRouteSmoke.Main";
    static final String EVENT_APP_ID = "io.github.ilyaandreev05.eventsoundstudio";
    static final String PRIMARY_SINK = "am_route_smoke_primary";
    static final String PRIMARY_MONITOR = "audiomatrix_smoke_primary_monitor";
    static final String EVENT_SINK = "am_route_smoke_event";
    static final String EVENT_MONITOR = "audiomatrix_smoke_event_monitor";
    static final String SYSTEM_SINK = "audiomatrix_system";
    static final String SYSTEM_MONITOR = "audiomatrix_system_monitor";
    static final String ESS_MONITOR = "audiomatrix_events";
    static final String APP_SOURCE = "route-smoke-app-source";
    static final String APP_STREAM = "am_route_smoke_app_stream";
    static final String EVENT_STREAM = "am_route_smoke_event_stream";
    static final String FIXTURE = "/io/github/ilyaandreev05/audiomatrix/audio/route-smoke-config.json";

    private RouteSmokePcmSupport() {}

    static Map<String, Object> loadFixture() throws IOException {
        String override = System.getenv("AUDIO_MATRIX_ROUTE_SMOKE_CONFIG");
        if (override != null && !override.isBlank()) return read(Files.readString(Path.of(override)));
        try (InputStream stream = RouteSmokePcmSupport.class.getResourceAsStream(FIXTURE)) {
            if (stream == null) throw new IOException("The route smoke fixture is missing from the test resources: " + FIXTURE);
            return read(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> activeRoute(Map<String, Object> config) {
        String active = String.valueOf(config.getOrDefault("active_route", ""));
        Object library = config.getOrDefault("route_library", List.of());
        for (Object candidate : library instanceof List<?> list ? list : List.of())
            if (candidate instanceof Map<?, ?> route && active.equals(route.get("id"))) {
                Map<String, Object> copy = new LinkedHashMap<>();
                route.forEach((key, value) -> copy.put(String.valueOf(key), value));
                return copy;
            }
        throw new IllegalStateException("The route smoke fixture has no active route: " + active);
    }

    private static Map<String, Object> read(String json) throws IOException {
        return new ObjectMapper().readValue(json, new TypeReference<Map<String, Object>>() { });
    }

    static Process startClient(List<String> command, Path log) throws IOException {
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    static Process startRecorder(String name, String target, Path file) throws IOException {
        List<String> command = List.of("pw-cat", "--record", "--target", target, "--latency=20ms",
                "--properties", properties(Map.of("node.name", name)),
                "--raw", "--format", "f32", "--rate", String.valueOf(SAMPLE_RATE),
                "--channels", String.valueOf(CHANNELS), "--channel-map", "FL,FR", "-");
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(file.toFile()).start();
    }

    static Process startToneProcess(String name, String target, String applicationId) throws IOException {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("node.name", name);
        properties.put("media.name", name);
        properties.put("application.id", applicationId);
        properties.put("application.process.binary", "route-smoke-tone");
        List<String> command = List.of("pw-cat", "--playback", "--target", target, "--latency=20ms",
                "--properties", properties(properties),
                "--raw", "--format", "f32", "--rate", String.valueOf(SAMPLE_RATE),
                "--channels", String.valueOf(CHANNELS), "--channel-map", "FL,FR", "-");
        return new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(Files.createTempFile("route-smoke-tone-", ".log").toFile()).start();
    }

    private static String properties(Map<String, Object> values) {
        try {
            return new ObjectMapper().writeValueAsString(values);
        } catch (IOException error) {
            throw new IllegalStateException("Could not encode the PipeWire node properties", error);
        }
    }

    /** Keeps a tone running for as long as the smoke test needs the stream to exist. */
    static final class ToneWriter {
        private static final int CHUNK_FRAMES = 480;
        private final Process process;
        private final double frequency;
        private final Thread thread;
        private volatile boolean running = true;

        private ToneWriter(Process process, double frequency) {
            this.process = process;
            this.frequency = frequency;
            this.thread = Thread.ofVirtual().name("route-smoke-tone").start(this::write);
        }

        static ToneWriter start(Process process, double frequency) {
            return new ToneWriter(process, frequency);
        }

        private void write() {
            ByteBuffer buffer = ByteBuffer.allocate(CHUNK_FRAMES * CHANNELS * 4).order(ByteOrder.LITTLE_ENDIAN);
            long cursor = 0;
            try {
                OutputStream stream = process.getOutputStream();
                while (running) {
                    buffer.clear();
                    for (int frame = 0; frame < CHUNK_FRAMES; frame++) {
                        double value = TONE_AMPLITUDE * Math.sin(2 * Math.PI * frequency * (cursor + frame) / SAMPLE_RATE);
                        buffer.putFloat((float) value);
                        buffer.putFloat((float) value);
                    }
                    // The pipe back-pressure paces the writer; sleeping here would starve the stream.
                    stream.write(buffer.array());
                    cursor += CHUNK_FRAMES;
                }
            } catch (IOException stopped) {
                // The stream is gone; the smoke test notices the missing signal.
            }
        }

        void stop() {
            running = false;
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // The process may already be gone; the tone thread exits on the next write.
            }
            try {
                thread.join(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Measures the tone amplitude of the last window of a captured f32 stream. */
    static double amplitude(Path file, double frequency, double windowSeconds) throws IOException {
        byte[] data = Files.readAllBytes(file);
        int frames = data.length / (CHANNELS * 4);
        int first = Math.max(0, frames - (int) (windowSeconds * SAMPLE_RATE));
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        double sin = 0, cos = 0;
        int count = 0;
        for (int frame = first; frame < frames; frame++) {
            double left = buffer.getFloat(frame * CHANNELS * 4);
            double right = buffer.getFloat(frame * CHANNELS * 4 + 4);
            double sample = 0.5 * (left + right);
            double phase = 2 * Math.PI * frequency * frame / SAMPLE_RATE;
            sin += sample * Math.sin(phase);
            cos += sample * Math.cos(phase);
            count++;
        }
        return count == 0 ? 0.0 : 2 * Math.hypot(sin, cos) / count;
    }

    /** Measures the routed gains and the cross-leak between the application and event tones. */
    static Map<String, Object> measure(Path primaryCapture, Path eventCapture, double windowSeconds)
            throws IOException {
        double appTone = amplitude(primaryCapture, APP_FREQUENCY, windowSeconds);
        double eventTone = amplitude(eventCapture, EVENT_FREQUENCY, windowSeconds);
        Map<String, Object> measured = new LinkedHashMap<>();
        measured.put("app_gain", appTone / TONE_AMPLITUDE);
        measured.put("event_gain", eventTone / TONE_AMPLITUDE);
        measured.put("app_leak", amplitude(primaryCapture, EVENT_FREQUENCY, windowSeconds) / Math.max(appTone, 1e-6));
        measured.put("event_leak", amplitude(eventCapture, APP_FREQUENCY, windowSeconds) / Math.max(eventTone, 1e-6));
        return measured;
    }

    /** Finds the compiled route that starts at the application source of the fixture graph. */
    static String appRouteId(Map<String, Object> runtime) {
        for (Object candidate : runtime.get("routes") instanceof List<?> routes ? routes : List.of())
            if (candidate instanceof Map<?, ?> route && APP_SOURCE.equals(route.get("from")))
                return String.valueOf(route.get("id"));
        throw new IllegalStateException("The compiled fixture has no route from " + APP_SOURCE);
    }

    /** Collects the owned node ids of the application branch from a pw-dump document. */
    static Map<String, Integer> appBranchNodes(Map<String, Object> runtime, String dump) throws IOException {
        String bus = "audiomatrix_bus_" + PipeWireRouteStaticSupport01.shortHash(APP_SOURCE);
        String route = "audiomatrix_route_" + PipeWireRouteStaticSupport01.shortHash(appRouteId(runtime));
        Map<String, Integer> owned = new LinkedHashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode node : new ObjectMapper().readTree(dump)) {
            String name = node.path("info").path("props").path("node.name").asText("");
            if (name.startsWith(bus) || name.startsWith(route)) owned.put(name, node.path("id").asInt());
        }
        return owned;
    }

    /** Reads the per-node volume and mute state from a pw-dump document. */
    static Map<String, String> nodeLevels(String dump) throws IOException {
        Map<String, String> levels = new LinkedHashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode node : new ObjectMapper().readTree(dump)) {
            com.fasterxml.jackson.databind.JsonNode props = node.path("info").path("props");
            String name = props.path("node.name").asText("");
            if (name.isBlank()) continue;
            com.fasterxml.jackson.databind.JsonNode state = node.path("info").path("params").path("Props");
            levels.put(name, "class=" + props.path("media.class").asText("")
                    + " state=" + node.path("info").path("state").asText("")
                    + " volume=" + state.path("volume").asText("-")
                    + " mute=" + state.path("mute").asText("-")
                    + " channelVolumes=" + state.path("channelVolumes").asText("-"));
        }
        return levels;
    }

    /** Parses the pw-link listing into the connected port pairs. */
    static List<String> links(String listing) {
        List<String> result = new ArrayList<>();
        String current = "";
        for (String line : listing.split("\\R")) {
            if (!line.startsWith(" ") && !line.startsWith("\t") && line.contains(":")) {
                current = line.trim();
            } else if (line.trim().startsWith("|->")) {
                result.add(current + " -> " + line.trim().substring(3).trim());
            }
        }
        return result;
    }

    static boolean linked(String listing, String fromNode, String toNode) {
        return links(listing).stream().anyMatch(link -> link.startsWith(fromNode + ":") && link.contains(" -> " + toNode + ":"));
    }
}
