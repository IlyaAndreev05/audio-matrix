package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceSelectorMatcher;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.GraphValidator;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Routes per-output quick mixer changes to the graph's explicit endpoint Gain. */
public final class GnomeGraphOutputGain {
    private GnomeGraphOutputGain() {}

    public static GainNode forOutput(AudioGraph graph, OutputNode output) {
        return graph.nodes().stream().filter(GainNode.class::isInstance).map(GainNode.class::cast)
                .filter(gain -> output.id().equals(gain.endpointNodeId())).findFirst().orElse(null);
    }

    public static Target find(Map<String, Object> config, PipeWireBackend.Snapshot snapshot, AudioNode physical) {
        String routeId = String.valueOf(config.getOrDefault("active_route", ""));
        if (routeId.isBlank()) return null;
        Map<String, Object> route = maps(config.get("route_library")).stream()
                .filter(item -> routeId.equals(item.get("id"))).findFirst().orElse(null);
        if (route == null) return null;
        AudioGraph graph;
        try { graph = GraphCodec.fromMap(route); }
        catch (RuntimeException invalid) { return null; }
        List<Map<String, Object>> devices = maps(config.get("devices"));
        for (GraphNode node : graph.nodes()) {
            if (!(node instanceof OutputNode output)) continue;
            AudioNode resolved = resolve(output, snapshot, devices);
            GainNode gain = forOutput(graph, output);
            if (resolved != null && resolved.id() == physical.id() && gain != null)
                return new Target(routeId, output.id(), gain.id());
        }
        return null;
    }

    public static void set(Map<String, Object> config, Target target, Double level, boolean toggleMute) {
        if (!target.routeId().equals(config.get("active_route")))
            throw new IllegalArgumentException("Output gain is not on the active route");
        Map<String, Object> route = maps(config.get("route_library")).stream()
                .filter(item -> target.routeId().equals(item.get("id"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Output route no longer exists"));
        AudioGraph graph = GraphCodec.fromMap(route);
        GainNode current = graph.nodes().stream().filter(node -> node.id().equals(target.gainNodeId())
                && node instanceof GainNode).map(GainNode.class::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Output Gain node no longer exists"));
        if (!target.outputNodeId().equals(current.endpointNodeId()))
            throw new IllegalArgumentException("Output Gain no longer belongs to this device");
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

    private static AudioNode resolve(OutputNode output, PipeWireBackend.Snapshot snapshot,
                                     List<Map<String, Object>> devices) {
        if ("system_default".equals(output.deviceId()))
            return snapshot.sinks().stream().filter(node -> node.id() == snapshot.defaultSinkId()).findFirst().orElse(null);
        return GnomeOutputState.resolve(snapshot.sinks(), output.deviceId(), devices);
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Output volume is invalid");
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

    public record Target(String routeId, String outputNodeId, String gainNodeId) {}
}
