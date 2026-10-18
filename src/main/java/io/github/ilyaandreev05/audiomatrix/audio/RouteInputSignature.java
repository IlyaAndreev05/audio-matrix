package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Stable fingerprint for inputs that can change an automatically applied route graph. */
public final class RouteInputSignature {
    private RouteInputSignature() {}

    public static String create(Map<String, Object> config, PipeWireBackend.Snapshot current,
                                long connectionGeneration) {
        List<String> sinks = current.sinks().stream()
                .filter(node -> !isAudioMatrixOwned(node))
                .map(node -> node.id() + ":" + node.name())
                .sorted().toList();
        List<String> streams = current.streams().stream()
                .filter(node -> !isAudioMatrixOwned(node))
                .map(node -> node.id() + ":" + node.name() + ":" + node.applicationId() + ":" + node.processName())
                .sorted().toList();
        boolean eventInputAvailable = current.sources().stream().anyMatch(EventInputMonitor::isNode);
        int hash = Objects.hash(config.get("active_profile"), config.get("active_route"), config.get("route_library"), config.get("profiles"), config.get("buses"),
                config.get("routes"), config.get("applications"), config.get("devices"), config.get("dsp"),
                config.get("mixer"), config.get("engine"), connectionGeneration, current.connected(),
                current.defaultSinkId(), sinks, streams, eventInputAvailable);
        return Integer.toHexString(hash);
    }

    private static boolean isAudioMatrixOwned(AudioNode node) {
        return node.name() != null && node.name().startsWith("audiomatrix_");
    }
}
