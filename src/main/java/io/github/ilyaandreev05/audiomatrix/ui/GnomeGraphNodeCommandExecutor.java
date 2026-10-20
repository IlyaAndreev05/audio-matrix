package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeOutputState;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/** Applies API v4 graph commands before saving their confirmed state asynchronously. */
final class GnomeGraphNodeCommandExecutor {
    private GnomeGraphNodeCommandExecutor() {}

    static CompletableFuture<Void> execute(String name, String value, ConfigStore store, PipeWireBackend backend,
            PipeWireBackend.Snapshot snapshot, List<AudioNode> sinks,
            Function<Consumer<Map<String, Object>>, CompletableFuture<Void>> graphUpdate,
            Consumer<Throwable> saveFailed, Runnable saved) {
        if ("setRouteEnabled".equals(name)) return GnomeNodeCommandHandler.execute(name, value, graphUpdate);
        String[] fields = value.split("\\t", -1);
        if (fields.length != 3 || fields[0].isBlank() || fields[1].isBlank())
            return CompletableFuture.failedFuture(new IllegalArgumentException("Node command is malformed"));
        GraphNode node = findNode(store.snapshot(), fields[0], fields[1]);
        if (!(node instanceof DeviceSlotNode)) return GnomeNodeCommandHandler.execute(name, value, graphUpdate);
        Boolean muted = null;
        Double volume = null;
        if ("setNodeMuted".equals(name)) {
            if (!List.of("true", "false").contains(fields[2]))
                return CompletableFuture.failedFuture(new IllegalArgumentException("Node mute state is invalid"));
            muted = Boolean.parseBoolean(fields[2]);
        } else {
            try { volume = Double.parseDouble(fields[2]); }
            catch (NumberFormatException invalid) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Node volume is invalid"));
            }
            if (!Double.isFinite(volume))
                return CompletableFuture.failedFuture(new IllegalArgumentException("Node volume is invalid"));
        }
        return setDeviceSlotLevel(store, backend, snapshot, sinks, fields[0], fields[1], volume, muted,
                saveFailed, saved);
    }

    private static CompletableFuture<Void> setDeviceSlotLevel(ConfigStore store, PipeWireBackend backend,
            PipeWireBackend.Snapshot snapshot, List<AudioNode> sinks, String routeId, String nodeId,
            Double volume, Boolean muted, Consumer<Throwable> saveFailed, Runnable saved) {
        Double requestedVolume = volume;
        Boolean requestedMute = muted;
        DeviceSlotNode slot = (DeviceSlotNode) findNode(store.snapshot(), routeId, nodeId);
        AudioNode current = resolve(slot, snapshot, sinks, store.snapshot());
        Consumer<Map<String, Object>> change = config -> {
            if (Boolean.TRUE.equals(requestedMute) && current != null && !current.muted()
                    && Double.isFinite(current.volume()))
                GraphNodeCommand.setVolume(config, routeId, nodeId, Math.clamp(current.volume(), 0, 1));
            if (Boolean.FALSE.equals(requestedMute) && slot.volume() == 0 && current != null
                    && current.muted() && current.volume() > 0 && Double.isFinite(current.volume()))
                GraphNodeCommand.setVolume(config, routeId, nodeId, Math.clamp(current.volume(), 0, 1));
            if (requestedVolume != null)
                GraphNodeCommand.setVolume(config, routeId, nodeId, Math.clamp(requestedVolume, 0, 1));
            if (requestedMute != null) GraphNodeCommand.setMuted(config, routeId, nodeId, requestedMute);
        };
        return GnomeNodeCommandHandler.applyRuntimeThenStore(store, change, true, candidate -> {
            DeviceSlotNode updated = (DeviceSlotNode) findNode(candidate, routeId, nodeId);
            AudioNode output = resolve(updated, snapshot, sinks, candidate);
            if (output == null) return CompletableFuture.completedFuture(null);
            if (requestedVolume != null) return backend.setVolume(output.id(), Math.clamp(requestedVolume, 0, 1));
            if (Boolean.TRUE.equals(requestedMute)) return backend.setMute(output.id(), true, updated.volume());
            return backend.setVolume(output.id(), updated.volume())
                    .thenCompose(ignored -> backend.setMute(output.id(), false, updated.volume()));
        }, saveFailed, saved);
    }

    private static AudioNode resolve(DeviceSlotNode slot, PipeWireBackend.Snapshot snapshot,
                                     List<AudioNode> sinks, Map<String, Object> config) {
        if ("system_default".equals(slot.deviceId()))
            return sinks.stream().filter(node -> node.id() == snapshot.defaultSinkId()).findFirst().orElse(null);
        return GnomeOutputState.resolve(sinks, slot.deviceId(), maps(config.get("devices")));
    }

    private static GraphNode findNode(Map<String, Object> config, String routeId, String nodeId) {
        AudioGraph graph = GraphCodec.fromMap(GraphUiRoutes.route(config, routeId));
        return graph.nodes().stream().filter(node -> node.id().equals(nodeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Audio Matrix node no longer exists"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
