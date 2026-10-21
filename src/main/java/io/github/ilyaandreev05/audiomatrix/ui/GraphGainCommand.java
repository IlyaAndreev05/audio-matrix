package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Persisted level/mute for a pinned graph Gain node. */
final class GraphGainCommand {
    private GraphGainCommand() {}
    static void set(Map<String, Object> config, String value, boolean toggleMute) {
        String[] fields = value.split("\\t", -1);
        if (fields.length != (toggleMute ? 2 : 3) || fields[0].isBlank() || fields[1].isBlank())
            throw new IllegalArgumentException("Graph Gain command is malformed");
        Double level = null;
        if (!toggleMute) {
            try { level = Double.parseDouble(fields[2]); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Graph Gain level is invalid"); }
            if (!Double.isFinite(level)) throw new IllegalArgumentException("Graph Gain level is invalid");
            level = Math.max(0, Math.min(1, level));
        }
        Map<String, Object> route = GraphUiRoutes.route(config, fields[0]);
        AudioGraph graph = GraphCodec.fromMap(route);
        GainNode current = graph.nodes().stream().filter(node -> fields[1].equals(node.id()) && node instanceof GainNode)
                .map(GainNode.class::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Pinned Gain no longer exists"));
        GainNode changed = new GainNode(current.id(), current.name(), current.inputs().getFirst().channels(),
                level == null ? current.gain() : level,
                toggleMute ? !current.muted() : current.muted(), current.endpointNodeId());
        List<GraphNode> nodes = new ArrayList<>(graph.nodes());
        nodes.set(nodes.indexOf(current), changed);
        AudioGraph next = new AudioGraph(graph.id(), graph.name(), nodes, graph.connections());
        List<String> errors = GraphValidator.validate(next);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        GraphRouteLibrary.saveRoute(config, next, String.valueOf(route.getOrDefault("group_id", "")));
    }
}
