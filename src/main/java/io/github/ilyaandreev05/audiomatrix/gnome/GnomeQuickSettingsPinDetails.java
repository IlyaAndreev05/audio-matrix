package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioMatrixOwnedStream;
import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

final class GnomeQuickSettingsPinDetails {
    private GnomeQuickSettingsPinDetails() {}

    /** The catch-all application generator publishes the same application list as a plain source node. */
    static List<Map<String, Object>> applicationsFor(String selector,
                                                      List<Map<String, Object>> applications,
                                                      List<String> claims) {
        return applications.stream().filter(app -> matches(selector, app, claims)).toList();
    }

    /** Only a source the user authored claims an application; a materialized generator instance never claims one. */
    static List<String> claimSelectors(AudioGraph graph, Set<String> generated) {
        if (graph == null) return List.of();
        return graph.nodes().stream().filter(node -> !generated.contains(node.id())).flatMap(node -> {
            if (node instanceof SourceNode source) return Stream.of(source.selector());
            if (node instanceof DynamicGeneratorNode generator && "applications".equals(generator.registry()))
                return Stream.of(generator.selector());
            return Stream.<String>empty();
        }).filter(value -> !value.equals("application:*") && !value.equals("application:unassigned"))
                .toList();
    }

    static boolean matches(String selector, Map<String, Object> app, List<String> claims) {
        String appId = String.valueOf(app.getOrDefault("application_id", ""));
        String process = String.valueOf(app.getOrDefault("process", ""));
        if ("io.github.ilyaandreev05.eventsoundstudio".equals(appId)
                || AudioMatrixOwnedStream.matches(applicationNode(app))) return false;
        if (selector.startsWith("application:") && !selector.equals("application:*")
                && !selector.equals("application:unassigned"))
            return selector.substring("application:".length()).equals(appId);
        if (selector.startsWith("process:")) return selector.substring("process:".length()).equals(process);
        if (selector.equals("application:unassigned") || selector.equals("application:*"))
            return claims.stream().noneMatch(value -> value.equals("application:" + appId)
                    || value.equals("process:" + process));
        return false;
    }

    private static AudioNode applicationNode(Map<String, Object> app) {
        return new AudioNode(-1, "", "", "Stream/Output/Audio", "",
                String.valueOf(app.getOrDefault("name", "")), String.valueOf(app.getOrDefault("process", "")),
                String.valueOf(app.getOrDefault("application_id", "")), 2, List.of(), 1.0, false, null);
    }

    static Map<String, Object> outputFor(OutputNode output, AudioGraph graph,
                                         PipeWireBackend.Snapshot snapshot, Map<String, Object> config,
                                         List<Map<String, Object>> outputs) {
        List<Map<String, Object>> devices = maps(config.get("devices"));
        AudioNode resolved = output.deviceId().equals("system_default")
                ? snapshot.sinks().stream().filter(node -> node.id() == snapshot.defaultSinkId()).findFirst().orElse(null)
                : GnomeOutputState.resolve(snapshot.sinks(), output.deviceId(), devices);
        Map<String, Object> state = resolved == null
                ? new LinkedHashMap<>(Map.of("available", false, "id", output.deviceId(), "name", output.name()))
                : outputs.stream().filter(item -> resolved.name().equals(item.get("name")))
                    .findFirst().map(item -> withAvailability(item, true))
                    .orElseGet(() -> new LinkedHashMap<>(Map.of("available", false,
                            "id", output.deviceId(), "name", output.name())));
        GainNode gain = GnomeGraphOutputGain.forOutput(graph, output);
        if (gain == null) return state;
        state.put("volume", gain.gain());
        state.put("muted", gain.muted());
        state.put("graphGainNodeId", gain.id());
        return state;
    }

    private static Map<String, Object> withAvailability(Map<String, Object> value, boolean available) {
        Map<String, Object> copy = new LinkedHashMap<>(value);
        copy.put("available", available);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
