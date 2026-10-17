package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads PipeWire node, stream, client, and default-output state into one snapshot. */
final class PipeWireSnapshotReader {
    private final ObjectMapper json = new ObjectMapper();

    PipeWireBackend.Snapshot read(PipeWireBackend.Snapshot previous) throws Exception {
        requireTools();
        String dump = PipeWireBackend.run(List.of("pw-dump", "-N"), Duration.ofSeconds(6));
        String status = PipeWireBackend.run(List.of("wpctl", "status"), Duration.ofSeconds(3));
        JsonNode root = json.readTree(dump);
        if (!root.isArray()) throw new IOException("pw-dump returned an unexpected document");
        Map<Integer, PipeWireBackend.LevelState> levels = levelStates(status);
        Map<Integer, JsonNode> clients = clientProperties(root);
        List<AudioNode> sinks = new ArrayList<>(), sources = new ArrayList<>(), streams = new ArrayList<>();
        readNodes(root, clients, levels, sinks, sources, streams);
        return snapshot(previous, status, sinks, sources, streams);
    }

    private static void requireTools() throws IOException {
        if (!PipeWireBackend.executable("pw-dump") || !PipeWireBackend.executable("wpctl"))
            throw new IOException("pw-dump or wpctl is not installed");
    }

    private static void readNodes(JsonNode root, Map<Integer, JsonNode> clients,
                                  Map<Integer, PipeWireBackend.LevelState> levels,
                                  List<AudioNode> sinks, List<AudioNode> sources, List<AudioNode> streams) {
        for (JsonNode object : root) {
            JsonNode info = object.path("info"), props = info.path("props");
            String mediaClass = props.path("media.class").asText("");
            String name = props.path("node.name").asText("");
            if (!isSnapshotAudioNode(mediaClass, name)) continue;
            AudioNode node = audioNode(object, info, props, mediaClass, name, clients, levels);
            addNode(node, mediaClass, sinks, sources, streams);
        }
    }

    private static AudioNode audioNode(JsonNode object, JsonNode info, JsonNode props, String mediaClass,
                                       String name, Map<Integer, JsonNode> clients,
                                       Map<Integer, PipeWireBackend.LevelState> levels) {
        int id = object.path("id").asInt(-1);
        String description = props.path("node.description").asText(name);
        String state = info.path("state").asText("unknown").toLowerCase();
        PipeWireBackend.ApplicationIdentity app = applicationIdentity(props, clients);
        int channels = props.path("audio.channels").asInt(2);
        List<Integer> rates = sampleRates(info.path("params").path("EnumFormat"));
        Integer pulseId = props.path("pulse.id").isInt() ? props.path("pulse.id").asInt() : null;
        JsonNode stateProps = activeProps(info.path("params").path("Props"));
        PipeWireBackend.LevelState level = levels.get(id);
        double volume = level == null ? Double.NaN : level.volume();
        boolean muted = level != null ? level.muted() : stateProps.path("mute").asBoolean(false);
        return new AudioNode(id, name, description, mediaClass, state, app.applicationName(),
                app.processName(), app.applicationId(), channels, rates, volume, muted, pulseId);
    }

    private static void addNode(AudioNode node, String mediaClass, List<AudioNode> sinks,
                                List<AudioNode> sources, List<AudioNode> streams) {
        if (mediaClass.startsWith("Stream/Output/Audio")) streams.add(node);
        else if (mediaClass.startsWith("Audio/Sink")) sinks.add(node);
        else if (mediaClass.startsWith("Audio/Source")) sources.add(node);
    }

    private static PipeWireBackend.Snapshot snapshot(PipeWireBackend.Snapshot previous, String status,
                                                      List<AudioNode> sinks, List<AudioNode> sources,
                                                      List<AudioNode> streams) {
        long now = System.currentTimeMillis();
        Map<String, AudioNode> recent = recentStreams(previous, streams, now);
        Map<String, Long> seen = seenStreams(previous, streams, now);
        return new PipeWireBackend.Snapshot(true, "", List.copyOf(sinks), List.copyOf(sources),
                List.copyOf(streams), List.copyOf(recent.values()), Collections.unmodifiableMap(seen),
                defaultSinkId(status), now);
    }

    private static Map<String, AudioNode> recentStreams(PipeWireBackend.Snapshot previous,
                                                         List<AudioNode> streams, long now) {
        Map<String, AudioNode> recent = new LinkedHashMap<>();
        List<AudioNode> prior = new ArrayList<>(previous.streams());
        prior.addAll(previous.recentlyEnded());
        for (AudioNode old : prior)
            if (streams.stream().noneMatch(node -> node.name().equals(old.name()))) recent.put(old.name(), old);
        recent.values().removeIf(node -> now - previous.streamLastSeen().getOrDefault(node.name(), now) > 30_000);
        return recent;
    }

    private static Map<String, Long> seenStreams(PipeWireBackend.Snapshot previous,
                                                  List<AudioNode> streams, long now) {
        Map<String, Long> seen = new HashMap<>(previous.streamLastSeen());
        streams.forEach(node -> seen.put(node.name(), now));
        seen.entrySet().removeIf(entry -> now - entry.getValue() > 30_000);
        return seen;
    }

    static Map<Integer, JsonNode> clientProperties(JsonNode root) {
        Map<Integer, JsonNode> result = new HashMap<>();
        if (root == null || !root.isArray()) return result;
        for (JsonNode object : root) {
            if (!"PipeWire:Interface:Client".equals(object.path("type").asText())) continue;
            int id = object.path("id").asInt(-1);
            JsonNode properties = object.path("info").path("props");
            if (id >= 0 && properties.isObject()) result.put(id, properties);
        }
        return result;
    }

    static PipeWireBackend.ApplicationIdentity applicationIdentity(JsonNode nodeProperties,
                                                                    Map<Integer, JsonNode> clients) {
        JsonNode client = clients.get(nodeProperties.path("client.id").asInt(-1));
        return new PipeWireBackend.ApplicationIdentity(property(nodeProperties, client, "application.name"),
                property(nodeProperties, client, "application.process.binary"),
                property(nodeProperties, client, "application.id"));
    }

    private static String property(JsonNode node, JsonNode client, String name) {
        String value = node.path(name).asText("");
        return !value.isBlank() ? value : client == null ? "" : client.path(name).asText("");
    }

    static boolean isSnapshotAudioNode(String mediaClass, String name) {
        if (mediaClass == null || name == null || name.isBlank()) return false;
        if (!(mediaClass.startsWith("Audio/Sink") || mediaClass.startsWith("Audio/Source")
                || mediaClass.startsWith("Stream/Output/Audio"))) return false;
        return !name.startsWith("audiomatrix_") || PipeWireBackend.SYSTEM_MIXER_SINK_NAME.equals(name);
    }

    static int defaultSinkId(String status) {
        boolean inSinks = false;
        var pattern = java.util.regex.Pattern.compile("\\b(\\d+)\\.");
        for (String line : status.split("\\R")) {
            if (line.contains("Sinks:")) { inSinks = true; continue; }
            if (inSinks && line.matches(".*(Sources|Filters|Streams|Video|Settings):.*")) break;
            if (!inSinks) continue;
            var id = pattern.matcher(line);
            if (id.find() && line.substring(0, id.start()).contains("*")) return Integer.parseInt(id.group(1));
        }
        return -1;
    }

    static Map<Integer, PipeWireBackend.LevelState> levelStates(String status) {
        Map<Integer, PipeWireBackend.LevelState> result = new HashMap<>();
        var ids = java.util.regex.Pattern.compile("\\b(\\d+)\\.");
        var volumes = java.util.regex.Pattern.compile("\\[vol:\\s*([0-9.]+)([^\\]]*)\\]",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        for (String line : status.split("\\R")) addLevel(result, line, ids, volumes);
        return result;
    }

    private static void addLevel(Map<Integer, PipeWireBackend.LevelState> levels, String line,
                                 java.util.regex.Pattern ids, java.util.regex.Pattern volumes) {
        var id = ids.matcher(line); var volume = volumes.matcher(line);
        if (!id.find() || !volume.find()) return;
        int nodeId = Integer.parseInt(id.group(1));
        double value = PipeWireBackend.clamp(Double.parseDouble(volume.group(1)), 0, 1.5);
        boolean muted = volume.group(2).toLowerCase(java.util.Locale.ROOT).contains("muted");
        levels.put(nodeId, new PipeWireBackend.LevelState(value, muted));
    }

    private static JsonNode activeProps(JsonNode props) {
        if (!props.isArray()) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        for (JsonNode item : props) if (item.has("volume") || item.has("mute")) return item;
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static List<Integer> sampleRates(JsonNode formats) {
        List<Integer> result = new ArrayList<>();
        if (formats.isArray()) for (JsonNode format : formats) addSampleRate(result, format.path("rate"));
        return result.stream().distinct().sorted().toList();
    }

    private static void addSampleRate(List<Integer> rates, JsonNode rate) {
        if (rate.isInt()) rates.add(rate.asInt());
        else if (rate.isObject()) for (String key : List.of("default", "min", "max"))
            if (rate.path(key).isInt()) rates.add(rate.path(key).asInt());
    }
}
