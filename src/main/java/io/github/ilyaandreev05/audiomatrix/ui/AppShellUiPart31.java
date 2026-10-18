package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.ThemeManager;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeCommandRevision;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeControlBridge;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeGraphOutputGain;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

abstract class AppShellUiPart31 extends AppShellUiPart30 {
    protected AppShellUiPart31(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    @Override
    protected CompletableFuture<Void> handleGnomeCommand(GnomeControlBridge.Command command) {
        if (!Set.of("set_graph_output_volume", "toggle_graph_output_mute").contains(command.name()))
            return super.handleGnomeCommand(command);
        if (GnomeCommandRevision.isStale(command, store.revision()))
            return CompletableFuture.failedFuture(new IllegalStateException("GNOME command is based on an old Audio Matrix revision"));
        return setGnomeGraphOutputLevel(command.value(), "toggle_graph_output_mute".equals(command.name()));
    }

    private CompletableFuture<Void> setGnomeGraphOutputLevel(String value, boolean toggleMute) {
        String[] fields = value.split("\\t", -1);
        if (fields.length != (toggleMute ? 3 : 4)
                || fields[0].isBlank() || fields[1].isBlank() || fields[2].isBlank())
            return CompletableFuture.failedFuture(new IllegalArgumentException("Graph output reference is malformed"));
        Double level = null;
        if (!toggleMute) {
            try { level = Double.parseDouble(fields[3]); }
            catch (NumberFormatException invalid) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Graph output volume is invalid"));
            }
            if (!Double.isFinite(level))
                return CompletableFuture.failedFuture(new IllegalArgumentException("Graph output volume is invalid"));
        }
        GnomeGraphOutputGain.Target target = new GnomeGraphOutputGain.Target(fields[0], fields[1], fields[2]);
        Double requestedLevel = level;
        return updateRouteConfigCommand(t("Изменить уровень выхода маршрута", "Change graph output level"),
                config -> GnomeGraphOutputGain.set(config, target, requestedLevel, toggleMute));
    }

    @Override
    protected void updateGraphMixerInput(String routeId, String nodeId, String port, Double gain, Boolean muted) {
        updateRouteConfig(t("Изменить вход микшера", "Change Mixer input"), config -> {
            Map<String, Object> route = route(config, routeId);
            AudioGraph changed = changeMixerInput(GraphCodec.fromMap(route), nodeId, port, gain, muted);
            GraphRouteLibrary.saveRoute(config, changed, string(route.get("group_id"), ""));
        });
    }

    @Override
    protected CompletableFuture<Void> setGnomeMixerInputLevel(String value, boolean toggleMute) {
        String[] fields = value.split("\\t", -1);
        if (fields.length != (toggleMute ? 3 : 4))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Mixer input command is malformed"));
        Double gain = null;
        if (!toggleMute) {
            try { gain = Double.parseDouble(fields[3]); }
            catch (NumberFormatException invalid) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Mixer input volume is invalid"));
            }
            if (!Double.isFinite(gain))
                return CompletableFuture.failedFuture(new IllegalArgumentException("Mixer input volume is invalid"));
            gain = Math.max(0, Math.min(1, gain));
        }
        Double targetGain = gain;
        return updateRouteConfigCommand(t("Изменить вход микшера из GNOME", "Change Mixer input from GNOME"), config -> {
            if (!fields[0].equals(config.get("active_route")))
                throw new IllegalArgumentException("Mixer input is not on the active route");
            Map<String, Object> route = route(config, fields[0]);
            AudioGraph graph = GraphCodec.fromMap(route);
            MixerNode mixer = mixer(graph, fields[1]);
            boolean muted = toggleMute ? !mixer.inputMuted(fields[2]) : false;
            AudioGraph changed = changeMixerInput(graph, fields[1], fields[2], targetGain,
                    toggleMute ? muted : false);
            GraphRouteLibrary.saveRoute(config, changed, string(route.get("group_id"), ""));
        });
    }

    @Override
    protected CompletableFuture<Void> setGnomeMatrixView(String value) {
        String[] fields = value.split("\\t", -1);
        if (fields.length != 2 || !("MATRIX".equals(fields[1]) || "ARROWS".equals(fields[1])))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Matrix view is invalid"));
        return updateRouteConfigCommand(t("Изменён вид Matrix в GNOME", "GNOME Matrix view changed"), config -> {
            Map<String, Object> control = maps(config.get("quick_controls")).stream()
                    .filter(item -> fields[0].equals(item.get("id"))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Matrix control no longer exists"));
            String routeId = string(control.get("route_id"), string(config.get("active_route"), ""));
            if (!routeId.equals(config.get("active_route")))
                throw new IllegalArgumentException("Matrix control is not on the active route");
            Map<String, Object> route = route(config, routeId);
            AudioGraph graph = GraphCodec.fromMap(route);
            boolean matrix = graph.nodes().stream().anyMatch(node -> node.id().equals(control.get("node_id"))
                    && node instanceof io.github.ilyaandreev05.audiomatrix.graph.MatrixNode);
            if (!matrix || !"active_links".equals(control.get("parameter_id")))
                throw new IllegalArgumentException("Quick control is not a Matrix node");
            control.put("view", fields[1]);
        });
    }

    private AudioGraph changeMixerInput(AudioGraph graph, String nodeId, String port, Double gain, Boolean muted) {
        MixerNode current = mixer(graph, nodeId);
        if (!current.inputGains().containsKey(port))
            throw new IllegalArgumentException("Mixer input no longer exists");
        Map<String, Double> gains = new LinkedHashMap<>(current.inputGains());
        Map<String, Boolean> mutes = new LinkedHashMap<>(current.inputMutes());
        if (gain != null) {
            if (!Double.isFinite(gain) || gain < 0 || gain > 1)
                throw new IllegalArgumentException("Mixer input volume must be between 0 and 1");
            gains.put(port, gain);
            mutes.put(port, false);
        }
        if (muted != null) mutes.put(port, muted);
        MixerNode changed = new MixerNode(current.id(), current.name(), current.outputs().getFirst().channels(), gains, mutes);
        List<GraphNode> nodes = new ArrayList<>(graph.nodes());
        nodes.set(nodes.indexOf(current), changed);
        return new AudioGraph(graph.id(), graph.name(), nodes, graph.connections());
    }

    private MixerNode mixer(AudioGraph graph, String nodeId) {
        return graph.nodes().stream().filter(node -> node.id().equals(nodeId) && node instanceof MixerNode)
                .map(MixerNode.class::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Mixer node is unavailable"));
    }

    private Map<String, Object> route(Map<String, Object> config, String routeId) {
        return maps(config.get("route_library")).stream().filter(item -> routeId.equals(item.get("id")))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Route is unavailable"));
    }
}
