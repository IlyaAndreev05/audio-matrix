package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class GraphPipeWireTraversal {
    private final AudioGraph graph;
    private final Map<String, GraphNode> nodes;
    private final List<Map<String, Object>> routes;
    private final List<String> activeRoutes;
    private final Map<String, Object> routeChains;
    private final Map<String, Map<String, Object>> outputLimiters;
    private final Set<String> sourceOutputs;
    private final Set<String> externallyControlledGains;

    GraphPipeWireTraversal(AudioGraph graph, Map<String, GraphNode> nodes, List<Map<String, Object>> routes,
                           List<String> activeRoutes, Map<String, Object> routeChains,
                           Map<String, Map<String, Object>> outputLimiters, Set<String> sourceOutputs,
                           Set<String> externallyControlledGains) {
        this.graph = graph;
        this.nodes = nodes;
        this.routes = routes;
        this.activeRoutes = activeRoutes;
        this.routeChains = routeChains;
        this.outputLimiters = outputLimiters;
        this.sourceOutputs = sourceOutputs;
        this.externallyControlledGains = externallyControlledGains;
    }

    void walkFrom(String sourceId) {
        List<GraphConnection> first = outgoing(graph, sourceId);
        for (GraphConnection edge : first) {
            PathState state = new PathState(sourceId, edge.gain(), false, true, new ArrayList<>(), new ArrayList<>());
            state.edgeIds.add(edge.id());
            walk(edge.toNode(), edge.toPort(), state, new HashSet<>());
        }
    }

    private void walk(String nodeId, String incomingPort, PathState path, Set<String> visiting) {
        if (!visiting.add(nodeId)) throw new IllegalArgumentException("Feedback cycle reached during graph compilation");
        GraphNode node = nodes.get(nodeId);
        if (node == null) throw new IllegalArgumentException("Connection references missing node " + nodeId);
        if (node instanceof DeviceSlotNode slot) {
            String deviceId = GraphPipeWireCompiler.deviceReference(slot.deviceId());
            if (deviceId.isBlank()) {
                visiting.remove(nodeId);
                return;
            }
            List<GraphConnection> next = outgoing(graph, nodeId);
            if (next.isEmpty()) node = new OutputNode(slot.id(), slot.name(), deviceId, slot.inputs().getFirst().channels());
            else for (GraphConnection edge : next) {
                GraphNode target = nodes.get(edge.toNode());
                if (!(target instanceof OutputNode output)
                        || !deviceId.equals(GraphPipeWireCompiler.deviceReference(output.deviceId())))
                    throw new IllegalArgumentException("A bound device slot may only feed its assigned physical output");
            }
        }
        boolean endpointGain = node instanceof GainNode gain && !gain.endpointNodeId().isBlank();
        if (path.outputLimiter != null && !(node instanceof OutputNode) && !(node instanceof RouterNode) && !endpointGain)
            throw new IllegalArgumentException("Peak limiter nodes must be the final processor before an output; move '"
                    + node.name() + "' before the limiter");
        if (node instanceof RouterNode router) {
            boolean protectMix = node instanceof MultiSelectorNode && activeInputCount(router) > 1;
            for (GraphConnection edge : outgoing(graph, nodeId)) {
                // Every branch stays owned; an unselected branch is compiled as a muted route so that
                // switching the selected output only changes levels and never the PipeWire topology.
                PathState branch = path.copy();
                branch.selected &= router.activeLinks().contains(incomingPort + "->" + edge.fromPort());
                branch.protectMixedOutput |= protectMix && branch.selected;
                applyEdgeGain(branch, edge);
                walk(edge.toNode(), edge.toPort(), branch, new HashSet<>(visiting));
            }
            visiting.remove(nodeId);
            return;
        }
        if (node instanceof OutputNode output) {
            if (path.protectMixedOutput && path.outputLimiter == null)
                path.outputLimiter = Map.of("enabled", true, "ceiling_db", -1.0, "lookahead_ms", 5.0, "release_ms", 80.0);
            Map<String, Object> existingLimiter = outputLimiters.get(output.deviceId());
            if (path.outputLimiter != null && existingLimiter != null
                    && !java.util.Objects.equals(existingLimiter, path.outputLimiter))
                throw new IllegalArgumentException("All paths to physical output '" + output.deviceId()
                        + "' must share the same post-mix limiter");
            if (path.outputLimiter != null) outputLimiters.put(output.deviceId(), path.outputLimiter);
            else outputLimiters.putIfAbsent(output.deviceId(), null);
            ChannelMatrix channelMatrix = output.channelMatrix();
            String targetChannels = channelMatrix == null
                    ? String.join(",", output.inputs().getFirst().channels()) : String.join(",", channelMatrix.outputs());
            String targetKey = path.sourceId + "\u0000" + output.deviceId() + "\u0000" + targetChannels;
            if (path.selected && !sourceOutputs.add(targetKey))
                throw new IllegalArgumentException("Multiple paths from source '" + path.sourceId
                        + "' are selected for the same channels of output '" + output.deviceId()
                        + "'; route alternatives through a Switch or Matrix node");
            String id = stableRouteId(path, output);
            boolean muted = path.muted || !path.selected;
            Map<String, Object> route = GraphPipeWireCompiler.map("id", id, "name", path.sourceId + " → " + output.name(),
                    "from", path.sourceId, "to", output.deviceId(), "volume",
                    muted ? 0 : Math.max(0, Math.min(1, path.gain)), "gain_db", 0, "delay_ms", path.delayMs,
                    "mute", muted, "channel_map", "stereo", "enabled", true);
            if (GraphCommonVibro.VIBRO_OUTPUT_ID.equals(output.id())) route.put("role", "vibro");
            if (GraphCommonVibro.isBuiltinRoute(graph.id()) && "octine-event-source".equals(path.sourceId)
                    && !GraphCommonVibro.VIBRO_OUTPUT_ID.equals(output.id()))
                throw new IllegalArgumentException("EVENT must not reach ordinary audio outputs");
            route.put("channel_map", GraphPipeWireCompiler.layout(output.inputs().getFirst().channels()));
            if (channelMatrix != null) route.put("channel_matrix", channelMatrix.toMap());
            if (!path.filters.isEmpty()) {
                String chainId = "graph-chain-" + id;
                route.put("filters_ref", "dsp.route_chains." + chainId);
                routeChains.put(chainId, GraphPipeWireCompiler.map("enabled", true, "filters", List.copyOf(path.filters)));
            }
            routes.add(route);
            activeRoutes.add(id);
            visiting.remove(nodeId);
            return;
        }
        if (node instanceof GainNode gain) {
            if (!externallyControlledGains.contains(gain.id())) {
                path.gain *= gain.gain();
                path.muted |= gain.muted();
            }
        } else if (node instanceof GateNode gate) path.muted |= !gate.open();
        else if (node instanceof FilterNode filter && filter.enabled()) {
            if ("limiter".equals(filter.processor())) {
                if (path.outputLimiter != null)
                    throw new IllegalArgumentException("A path can contain only one post-mix peak limiter");
                Map<String, Object> limiter = new LinkedHashMap<>(filter.parameters());
                limiter.remove("processor");
                limiter.remove("enabled");
                limiter.put("enabled", true);
                path.outputLimiter = Map.copyOf(limiter);
            } else if ("delay".equals(filter.processor())) {
                path.delayMs += GraphFilterCompiler.delayMs(filter);
                if (path.delayMs > 5000.0)
                    throw new IllegalArgumentException("Combined graph delay must not exceed 5000 ms");
            } else path.filters.addAll(GraphFilterCompiler.routeFilters(filter));
        } else if (node instanceof MixerNode mixer) {
            path.gain *= mixer.inputGains().getOrDefault(incomingPort, 0.0);
            path.muted |= mixer.inputMuted(incomingPort);
            path.protectMixedOutput |= graph.connections().stream().filter(GraphConnection::enabled)
                    .filter(edge -> edge.toNode().equals(mixer.id()))
                    .filter(edge -> !mixer.inputMuted(edge.toPort())).map(GraphConnection::toPort).distinct().count() > 1;
        } else if (!(node instanceof DeviceSlotNode))
            throw new IllegalArgumentException("Unsupported processing node " + node.type() + " '" + node.name() + "'");
        List<GraphConnection> next = outgoing(graph, nodeId);
        if(next.isEmpty()&&GraphPipeWireDeferredOutput.isHapticLimiterBoundary(graph,nodes,nodeId)){visiting.remove(nodeId);return;}
        if (next.isEmpty()) throw new IllegalArgumentException("Node '" + node.name() + "' has no connection to an output");
        for (GraphConnection edge : next) {
            if (!edge.enabled()) continue;
            PathState branch = path.copy();
            applyEdgeGain(branch, edge);
            walk(edge.toNode(), edge.toPort(), branch, new HashSet<>(visiting));
        }
        visiting.remove(nodeId);
    }

    private void applyEdgeGain(PathState path, GraphConnection edge) {
        if (path.outputLimiter != null && Math.abs(edge.gain() - 1.0) > 1e-9)
            throw new IllegalArgumentException("A post-mix peak limiter must be the final signal processor before an output");
        path.gain *= edge.gain();
        path.edgeIds.add(edge.id());
    }

    private long activeInputCount(RouterNode router) {
        Set<String> connectedInputs = graph.connections().stream().filter(GraphConnection::enabled)
                .filter(edge -> edge.toNode().equals(router.id())).map(GraphConnection::toPort)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> connectedOutputs = outgoing(graph, router.id()).stream().map(GraphConnection::fromPort)
                .collect(java.util.stream.Collectors.toSet());
        return router.activeLinks().stream().map(link -> link.split("->", 2))
                .filter(parts -> parts.length == 2 && connectedInputs.contains(parts[0]) && connectedOutputs.contains(parts[1]))
                .map(parts -> parts[0]).distinct().count();
    }

    private String stableRouteId(PathState path, OutputNode output) {
        String identity = path.sourceId + "\u0000" + output.id() + "\u0000" + String.join("\u0000", path.edgeIds);
        return "graph-" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private List<GraphConnection> outgoing(AudioGraph graph, String id) {
        return graph.connections().stream().filter(GraphConnection::enabled).filter(edge -> edge.fromNode().equals(id)).toList();
    }

    private static final class PathState {
        final String sourceId;
        double gain;
        double delayMs;
        boolean muted;
        boolean selected;
        boolean protectMixedOutput;
        final List<Map<String, Object>> filters;
        final List<String> edgeIds;
        Map<String, Object> outputLimiter;

        PathState(String sourceId, double gain, boolean muted, boolean selected,
                  List<Map<String, Object>> filters, List<String> edgeIds) {
            this.sourceId = sourceId;
            this.gain = gain;
            this.muted = muted;
            this.selected = selected;
            this.filters = filters;
            this.edgeIds = edgeIds;
        }

        PathState copy() {
            PathState copy = new PathState(sourceId, gain, muted, selected,
                    new ArrayList<>(filters), new ArrayList<>(edgeIds));
            copy.delayMs = delayMs;
            copy.outputLimiter = outputLimiter;
            copy.protectMixedOutput = protectMixedOutput;
            return copy;
        }
    }
}
