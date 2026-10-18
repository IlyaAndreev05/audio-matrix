package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.GraphValidator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps each source's parent level in its graph Gain, separate from child stream levels. */
public final class GnomeGraphSourceGain {
    private GnomeGraphSourceGain() {}

    public static GainNode forSource(AudioGraph graph, String sourceNodeId) {
        return graph.nodes().stream().filter(GainNode.class::isInstance).map(GainNode.class::cast)
                .filter(gain -> sourceNodeId.equals(gain.endpointNodeId())).findFirst().orElse(null);
    }

    public static void set(Map<String, Object> config, Target target, Double level, boolean toggleMute) {
        if (!target.routeId().equals(config.get("active_route")))
            throw new IllegalArgumentException("Source gain is not on the active route");
        Map<String, Object> route = maps(config.get("route_library")).stream()
                .filter(item -> target.routeId().equals(item.get("id"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Source route no longer exists"));
        AudioGraph graph = GraphCodec.fromMap(route);
        GainNode current = graph.nodes().stream().filter(node -> node.id().equals(target.gainNodeId())
                && node instanceof GainNode).map(GainNode.class::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Source Gain node no longer exists"));
        if (!target.sourceNodeId().equals(current.endpointNodeId()))
            throw new IllegalArgumentException("Source Gain no longer belongs to this input");
        double gain = level == null ? current.gain() : clamp(level);
        boolean muted = toggleMute ? !current.muted() : level == null ? current.muted() : false;
        List<GraphNode> nodes = new ArrayList<>(graph.nodes());
        nodes.set(nodes.indexOf(current), new GainNode(current.id(), current.name(),
                current.inputs().getFirst().channels(), gain, muted, current.endpointNodeId()));
        AudioGraph changed = new AudioGraph(graph.id(), graph.name(), nodes, graph.connections());
        List<String> problems = GraphValidator.validate(changed);
        if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
        GraphRouteLibrary.saveRoute(config, changed, String.valueOf(route.getOrDefault("group_id", "")));
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Source volume is invalid");
        return Math.max(0, Math.min(1, value));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> raw) {
            Map<String, Object> copy = new LinkedHashMap<>();
            raw.forEach((key, field) -> copy.put(String.valueOf(key), field));
            result.add(copy);
        }
        return result;
    }

    public record Target(String routeId, String sourceNodeId, String gainNodeId) {}
}
