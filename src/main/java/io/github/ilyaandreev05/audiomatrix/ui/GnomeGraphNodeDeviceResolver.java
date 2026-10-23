package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeOutputState;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Resolves saved endpoint identities and applies their canonical device binding level. */
final class GnomeGraphNodeDeviceResolver {
    private GnomeGraphNodeDeviceResolver() {}

    static AudioNode resolveSavedOutput(GnomeGraphNodeTargetResolver.ResolvedTarget target,
            Map<String, Object> config, PipeWireBackend.Snapshot snapshot, List<AudioNode> sinks) {
        if (!snapshot.connected()) return null;
        return GnomeOutputState.resolve(sinks, GraphNodeCommand.savedDeviceId(target.node()),
                maps(config.get("devices")));
    }

    static AudioNode resolveGeneratedOutput(GnomeGraphNodeTargetResolver.ResolvedTarget target,
            Map<String, Object> config, PipeWireBackend.Snapshot snapshot, List<AudioNode> sinks) {
        if (!snapshot.connected()) return null;
        Object rawId = target.runtimeBinding().get("nodeId");
        if (!(rawId instanceof Number id))
            throw new IllegalArgumentException("Generated output has no live device binding");
        AudioNode output = sinks.stream().filter(node -> node.id() == id.intValue()).findFirst().orElse(null);
        if (output == null) return null;
        AudioNode configured = GnomeOutputState.resolve(sinks, "device:" + target.bindingId(),
                maps(config.get("devices")));
        if (configured == null || configured.id() != output.id())
            throw new IllegalArgumentException("Generated output device binding is stale");
        return output;
    }

    /** Apply and rollback restore both properties, including a slider's implicit unmute. */
    static CompletableFuture<?> applyLevel(PipeWireBackend backend, int nodeId,
            Map<String, Object> config, String bindingId) {
        Map<String, Object> binding = GraphNodeCommand.deviceBinding(config, bindingId);
        double savedVolume = number(binding.get("volume"), 1.0);
        boolean muted = Boolean.TRUE.equals(binding.get("mute"));
        return backend.setVolume(nodeId, savedVolume)
                .thenCompose(ignored -> backend.setMute(nodeId, muted, savedVolume));
    }

    static String deviceId(String reference) {
        String id = reference != null && reference.startsWith("device:")
                ? reference.substring("device:".length()) : reference;
        if (id == null || id.isBlank() || "system_default".equals(id))
            throw new IllegalArgumentException("Device output has no saved binding");
        return id;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
}
