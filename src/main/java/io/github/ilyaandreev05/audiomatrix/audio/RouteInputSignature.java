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
        try {
            List<String> sinks = current.sinks().stream()
                    .filter(node -> !AudioMatrixOwnedStream.matches(node))
                    .map(RouteInputSignature::sinkInput).sorted().toList();
            String defaultSink = current.sinks().stream()
                    .filter(node -> node.id() == current.defaultSinkId())
                    .map(RouteInputSignature::sinkInput).findFirst().orElse("");
            List<String> streams = current.streams().stream()
                    .filter(node -> !AudioMatrixOwnedStream.matches(node))
                    .filter(node -> !isTransientShellSound(node))
                    .map(RouteInputSignature::streamInput).sorted().toList();
            boolean eventInputAvailable = current.sources().stream().anyMatch(EventInputMonitor::isNode);
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

    private static String sinkInput(AudioNode node) {
        DeviceIdentity identity = node.identity();
        return node.name() + "\u0000" + identity + "\u0000" + node.channels() + "\u0000"
                + (identity == null ? "" : identity.channelPositions()) + "\u0000" + node.state();
    }

    private static String streamInput(AudioNode node) {
        return node.id() + "\u0000" + node.name() + "\u0000" + node.applicationId() + "\u0000"
                + node.processName() + "\u0000" + node.channels() + "\u0000" + node.identity() + "\u0000"
                + node.state();
    }
    private static boolean isTransientShellSound(AudioNode node) {
        String app = node.applicationName() == null ? "" : node.applicationName();
        String process = node.processName() == null ? "" : node.processName();
        return "gnome-shell".equalsIgnoreCase(app) || "gnome-shell".equalsIgnoreCase(process)
                || process.startsWith("gsd-") || "xdg-desktop-portal".equalsIgnoreCase(app);
    }
}
