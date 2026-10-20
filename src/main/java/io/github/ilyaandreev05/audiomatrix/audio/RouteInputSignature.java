package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.HexFormat;
import java.security.MessageDigest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Stable fingerprint for inputs that can change an automatically applied route graph. */
public final class RouteInputSignature {
    private RouteInputSignature() {}
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public static String create(Map<String, Object> config, PipeWireBackend.Snapshot current,
                                long connectionGeneration) {
        // Sink node ids are transient; a reconnect must not force a route rebuild by itself.
        List<String> sinks = current.sinks().stream()
                .filter(node -> !AudioMatrixOwnedStream.matches(node))
                .map(node -> node.name() + ":" + node.channels() + ":" + node.identity())
                .sorted().toList();
        String defaultSink = current.sinks().stream().filter(node -> node.id() == current.defaultSinkId())
                .map(node -> node.name() + ":" + node.identity()).findFirst().orElse("");
        boolean builtin = String.valueOf(config.get("active_route")).startsWith("octine-");
        List<String> streams = builtin ? List.of() : current.streams().stream()
                .filter(node -> !AudioMatrixOwnedStream.matches(node) && !isTransientShellSound(node))
                .map(node -> node.id() + ":" + node.name() + ":" + node.applicationId() + ":" + node.processName()
                        + ":" + node.channels() + ":" + node.identity())
                .sorted().toList();
        boolean eventInputAvailable = current.sources().stream().anyMatch(EventInputMonitor::isNode);
        try {
            byte[] inputs = JSON.writeValueAsBytes(Arrays.asList(
                    config.get("active_profile"), config.get("active_route"), config.get("route_library"),
                    config.get("common_vibro_graph"), config.get("profiles"), config.get("buses"),
                    config.get("routes"), config.get("applications"), config.get("devices"), config.get("dsp"),
                    config.get("mixer"), config.get("engine"), connectionGeneration, current.connected(),
                    defaultSink, sinks, streams, eventInputAvailable));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inputs));
        } catch (Exception error) {
            throw new IllegalArgumentException("Route inputs cannot be fingerprinted", error);
        }
    }

    private static boolean isTransientShellSound(AudioNode node) {
        String app = node.applicationName() == null ? "" : node.applicationName();
        String process = node.processName() == null ? "" : node.processName();
        return "gnome-shell".equalsIgnoreCase(app) || "gnome-shell".equalsIgnoreCase(process)
                || process.startsWith("gsd-") || "xdg-desktop-portal".equalsIgnoreCase(app);
    }
}
