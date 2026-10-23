package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Mixer input and Matrix view commands of the GNOME graph editor. */
final class GnomeGraphMixerCommands {
    private GnomeGraphMixerCommands() {}

    static void updateMixerInput(AppShellUiPart31 shell, String routeId, String nodeId, String port, Double gain, Boolean muted) {
        shell.updateRouteConfig(shell.t("Изменить вход микшера", "Change Mixer input"), config ->
                changeMixerInput(config, routeId, nodeId, port, gain, muted, false,
                        liveRegistry(shell, config, routeId, port)));
    }

    static CompletableFuture<Void> setMixerInputLevel(AppShellUiPart31 shell, String value, boolean toggleMute) {
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
        return shell.updateRouteConfigCommand(shell.t("Изменить вход микшера из GNOME", "Change Mixer input from GNOME"), config -> {
            if (!GraphUiRoutes.available(config, fields[0]))
                throw new IllegalArgumentException("Mixer input is not on the active route");
            changeMixerInput(config, fields[0], fields[1], fields[2], targetGain, null, toggleMute,
                    liveRegistry(shell, config, fields[0], fields[2]));
        });
    }

    static CompletableFuture<Void> setMatrixView(AppShellUiPart31 shell, String value) {
        String[] fields = value.split("\\t", -1);
        if (fields.length != 2 || !("MATRIX".equals(fields[1]) || "ARROWS".equals(fields[1])))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Matrix view is invalid"));
        return shell.updateRouteConfigCommand(shell.t("Изменён вид Matrix в GNOME", "GNOME Matrix view changed"), config -> {
            Map<String, Object> control = AppShellBase.maps(config.get("quick_controls")).stream()
                    .filter(item -> fields[0].equals(item.get("id"))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Matrix control no longer exists"));
            String routeId = AppShellBase.string(control.get("route_id"), AppShellBase.string(config.get("active_route"), ""));
            if (!GraphUiRoutes.available(config, routeId))
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

    /**
     * The single validated Mixer input mutation of both command entrypoints.
     *
     * <p>A runtime-generated port exists only in the effective graph. It is written into the saved
     * {@code input_gains}/{@code input_mutes} only after the exact stable port, its registered
     * instance, the live binding, and the enabled boundary connection have been revalidated against
     * the current route inside the atomic save. Both starting values come from the effective Mixer,
     * so the saved port reproduces the level the user currently observes and keeps it across every
     * later reconciliation. Nothing else of the effective graph is ever serialized.</p>
     */
    static void changeMixerInput(Map<String, Object> config, String routeId, String nodeId, String port,
            Double gain, Boolean muted, boolean toggleMute, DynamicInstanceRegistry.ReconcileResult registry) {
        if (gain != null && (!Double.isFinite(gain) || gain < 0 || gain > 1))
            throw new IllegalArgumentException("Mixer input volume must be between 0 and 1");
        Map<String, Object> savedRoute = route(config, routeId);
        AudioGraph graph = GraphCodec.fromMap(savedRoute);
        MixerNode current = mixer(graph, nodeId);
        boolean generated = DynamicInstanceRegistry.isGeneratedMixerInputPort(port);
        boolean saved = current.inputGains().containsKey(port);
        if (!generated && !saved) throw new IllegalArgumentException("Mixer input no longer exists");
        // A generated port has no other owner than the current runtime discovery: an unsaved one is
        // materialized from it and a saved one is revalidated against it before every edit.
        MixerNode observed = generated ? liveMixerInput(graph, nodeId, port, registry) : current;
        Double baseGain = observed.inputGains().get(port);
        if (baseGain == null || !Double.isFinite(baseGain) || baseGain < 0 || baseGain > 1)
            throw new IllegalArgumentException("Mixer input has no live owner");
        boolean baseMuted = observed.inputMuted(port);
        Map<String, Double> gains = new LinkedHashMap<>(current.inputGains());
        Map<String, Boolean> mutes = new LinkedHashMap<>(current.inputMutes());
        gains.putIfAbsent(port, baseGain);
        mutes.putIfAbsent(port, baseMuted);
        if (gain != null) {
            gains.put(port, gain);
            mutes.put(port, false);
        }
        if (toggleMute) mutes.put(port, !baseMuted);
        else if (muted != null) mutes.put(port, muted);
        MixerNode changed = new MixerNode(current.id(), current.name(), current.outputs().getFirst().channels(), gains, mutes);
        List<GraphNode> nodes = new ArrayList<>(graph.nodes());
        nodes.set(nodes.indexOf(current), changed);
        GraphRouteLibrary.saveRoute(config, new AudioGraph(graph.id(), graph.name(), nodes, graph.connections()),
                AppShellBase.string(savedRoute.get("group_id"), ""));
    }

    /** Reconciles the current discovery only for a generated port of the active route. */
    private static DynamicInstanceRegistry.ReconcileResult liveRegistry(AppShellUiPart31 shell,
            Map<String, Object> config, String routeId, String port) {
        return DynamicInstanceRegistry.isGeneratedMixerInputPort(port) && GraphUiRoutes.available(config, routeId)
                ? shell.currentMixerGraph(config) : null;
    }

    /**
     * Resolves the one instance that owns this exact generated port and requires a live, enabled
     * boundary into this Mixer port; the returned Mixer is the effective one that carries its values.
     */
    private static MixerNode liveMixerInput(AudioGraph graph, String nodeId, String port,
            DynamicInstanceRegistry.ReconcileResult registry) {
        if (registry == null || !graph.id().equals(registry.configuredGraph().id()))
            throw new IllegalArgumentException("Mixer input registry is unavailable");
        DynamicInstanceRegistry.RuntimeInstance instance = null;
        for (DynamicInstanceRegistry.RuntimeInstance candidate : registry.instances().values())
            if (port.equals(DynamicInstanceRegistry.stableMixerInputPortId(
                    candidate.generatorId(), candidate.instanceKey()))) {
                instance = candidate;
                break;
            }
        if (instance == null) throw new IllegalArgumentException("Mixer input has no live owner");
        DynamicInstanceRegistry.RuntimeInstance owner = instance;
        DynamicGeneratorNode configured = graph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).filter(node -> node.id().equals(owner.generatorId()))
                .findFirst().orElse(null);
        if (configured == null || !"applications".equals(configured.registry())
                || !owner.available() || !owner.bindingAvailable()
                || !GnomeGraphNodeTargetResolver.sameGeneratorDefinition(configured,
                        DynamicInstanceRegistry.generator(registry, owner.generatorId())))
            throw new IllegalArgumentException("Mixer input has no live owner");
        boolean boundary = registry.effectiveGraph().connections().stream().anyMatch(edge ->
                edge.enabled() && edge.toNode().equals(nodeId) && edge.toPort().equals(port)
                        && owner.nodeIds().containsValue(edge.fromNode()));
        if (!boundary) throw new IllegalArgumentException("Mixer input has no live owner");
        MixerNode effective = mixer(registry.effectiveGraph(), nodeId);
        if (!effective.inputGains().containsKey(port))
            throw new IllegalArgumentException("Mixer input has no live owner");
        return effective;
    }

    private static MixerNode mixer(AudioGraph graph, String nodeId) {
        return graph.nodes().stream().filter(node -> node.id().equals(nodeId) && node instanceof MixerNode)
                .map(MixerNode.class::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Mixer node is unavailable"));
    }

    private static Map<String, Object> route(Map<String, Object> config, String routeId) {
        return GraphUiRoutes.route(config, routeId);
    }
}
