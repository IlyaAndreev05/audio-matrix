package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Separates immutable graph inputs from transient stream bindings and runtime levels. */
public final class RouteInputSignature {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final List<String> RUNTIME_LEVEL_KEYS = List.of(
            "volume", "mute", "muted", "gain", "global_volume", "global_mute", "tab_gain", "tab_muted",
            "graph_capture_sources");
    private static final Set<String> LEVEL_ID_KEYS = Set.of(
            "id", "node_id", "route_id", "instance_key", "generator_id", "source_id", "profile_id",
            "application_rule_id", "parent_application_id", "parent_process_name", "capture_stream_key", "node_name");

    private RouteInputSignature() {}

    /** Static route identity; stream churn and live levels are reconciled by stable branch owners. */
    public static String create(Map<String, Object> config, PipeWireBackend.Snapshot current,
                                long connectionGeneration) {
        if (current == null) return digest(Arrays.asList(topologyConfig(config), connectionGeneration, false));
        List<String> outputs = current.sinks().stream()
                .filter(node -> !AudioMatrixOwnedStream.matches(node))
                .map(node -> node.name() + ":" + node.identity().fingerprint())
                .sorted().toList();
        String defaultOutput = current.sinks().stream().filter(node -> node.id() == current.defaultSinkId())
                .map(node -> node.name() + ":" + node.identity().fingerprint()).findFirst().orElse("");
        boolean eventInputAvailable = current.sources().stream().anyMatch(EventInputMonitor::isNode);
        return digest(Arrays.asList(topologyConfig(config), connectionGeneration, current.connected(),
                defaultOutput, outputs, eventInputAvailable));
    }

    /** Runtime-only trigger for callers that must reconcile stream insertions/removals without rebuilding topology. */
    public static String dynamicBindings(PipeWireBackend.Snapshot current) {
        if (current == null) return digest(List.of("offline"));
        List<String> bindings = current.streams().stream()
                .filter(node -> !AudioMatrixOwnedStream.matches(node))
                .map(node -> node.id() + ":" + node.name() + ":" + node.applicationId() + ":"
                        + node.processName() + ":" + node.channels())
                .sorted().toList();
        return digest(bindings);
    }
    /** Runtime-level trigger used beside the static signature; changing it never rebuilds link topology. */
    public static String dynamicLevels(Map<String, Object> config) {
        return digest(levelConfig(config));
    }


    private static Object topologyConfig(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> stable = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                if (!RUNTIME_LEVEL_KEYS.contains(name)) stable.put(name, topologyConfig(item));
            });
            return stable;
        }
        if (value instanceof List<?> list) return list.stream().map(RouteInputSignature::topologyConfig).toList();
        return value;
    }
    private static Object levelConfig(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> levels = new LinkedHashMap<>();
            Map<String, Object> identity = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                if (RUNTIME_LEVEL_KEYS.contains(name) || "gain_db".equals(name)) levels.put(name, item);
                else if (LEVEL_ID_KEYS.contains(name)) identity.put(name, item);
                else {
                    Object child = levelConfig(item);
                    if (child != null) levels.put(name, child);
                }
            });
            if (levels.isEmpty()) return null;
            levels.putAll(identity);
            return levels;
        }
        if (value instanceof List<?> list) {
            List<Object> levels = list.stream().map(RouteInputSignature::levelConfig)
                    .filter(java.util.Objects::nonNull).toList();
            return levels.isEmpty() ? null : levels;
        }
        return null;
    }


    private static String digest(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value)));
        } catch (Exception error) {
            throw new IllegalArgumentException("Route inputs cannot be fingerprinted", error);
        }
    }
}
