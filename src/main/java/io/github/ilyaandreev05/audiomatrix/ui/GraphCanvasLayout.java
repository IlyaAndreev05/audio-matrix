package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.EventSoundStudioSource;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.RouterNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;

/** Deterministic presentation-only layout for graph nodes that have no saved position. */
final class GraphCanvasLayout {
    static final double NODE_WIDTH = 220;
    static final double LANE_GAP = 80;
    static final double ROW_GAP = 24;
    static final double LEFT_MARGIN = 24;
    static final double TOP_MARGIN = 24;

    record Position(double x, double y) {}
    record Layout(Map<String, Position> positions, double width, double height) {}

    private GraphCanvasLayout() {}

    static Layout arrange(AudioGraph graph, ToDoubleFunction<GraphNode> heightOf) {
        List<GraphNode> nodes = graph.nodes();
        Map<String, GraphNode> byId = new LinkedHashMap<>();
        Map<String, List<String>> successors = new LinkedHashMap<>();
        Map<String, List<String>> predecessors = new LinkedHashMap<>();
        for (GraphNode node : nodes) {
            byId.put(node.id(), node);
            successors.put(node.id(), new ArrayList<>());
            predecessors.put(node.id(), new ArrayList<>());
        }
        for (GraphConnection edge : graph.connections()) {
            if (!byId.containsKey(edge.fromNode()) || !byId.containsKey(edge.toNode())) continue;
            successors.get(edge.fromNode()).add(edge.toNode());
            predecessors.get(edge.toNode()).add(edge.fromNode());
        }

        List<SourceNode> sources = nodes.stream().filter(SourceNode.class::isInstance)
                .map(SourceNode.class::cast).toList();
        List<SourceNode> ordinary = sources.stream().filter(node -> !isEventSource(node)).toList();
        List<SourceNode> events = sources.stream().filter(GraphCanvasLayout::isEventSource).toList();
        Map<String, Integer> lanes = new LinkedHashMap<>();
        for (int index = 0; index < ordinary.size(); index++) assignReachableLane(ordinary.get(index), index, successors, lanes);
        for (int index = 0; index < events.size(); index++) assignReachableLane(events.get(index), ordinary.size() + index, successors, lanes);
        int nextLane = ordinary.size() + events.size();
        for (GraphNode node : nodes) if (!lanes.containsKey(node.id())) lanes.put(node.id(), nextLane++);

        Map<String, Integer> columns = new LinkedHashMap<>();
        for (GraphNode node : nodes) columns.put(node.id(), column(node, predecessors, byId));

        Map<Integer, Map<Integer, List<GraphNode>>> rows = new TreeMap<>();
        for (GraphNode node : nodes) rows.computeIfAbsent(lanes.get(node.id()), ignored -> new LinkedHashMap<>())
                .computeIfAbsent(columns.get(node.id()), ignored -> new ArrayList<>()).add(node);

        Map<String, Position> positions = new LinkedHashMap<>();
        double y = TOP_MARGIN;
        int maxColumn = 0;
        for (Map.Entry<Integer, Map<Integer, List<GraphNode>>> lane : rows.entrySet()) {
            double laneHeight = lane.getValue().values().stream().mapToDouble(columnNodes ->
                    columnNodes.stream().mapToDouble(heightOf).sum() + Math.max(0, columnNodes.size() - 1) * ROW_GAP)
                    .max().orElse(0);
            for (Map.Entry<Integer, List<GraphNode>> column : lane.getValue().entrySet()) {
                int columnIndex = column.getKey();
                maxColumn = Math.max(maxColumn, columnIndex);
                double rowY = y;
                for (GraphNode node : column.getValue()) {
                    positions.put(node.id(), new Position(LEFT_MARGIN + columnIndex * (NODE_WIDTH + LANE_GAP), rowY));
                    rowY += heightOf.applyAsDouble(node) + ROW_GAP;
                }
            }
            y += laneHeight + ROW_GAP;
        }
        double width = LEFT_MARGIN * 2 + (maxColumn + 1) * NODE_WIDTH + maxColumn * LANE_GAP;
        double height = Math.max(390, y + TOP_MARGIN - ROW_GAP);
        return new Layout(Map.copyOf(positions), width, height);
    }

    static double contentWidth(Map<String, Position> positions, ToDoubleFunction<String> widthOf) {
        return positions.entrySet().stream().mapToDouble(entry -> entry.getValue().x() + widthOf.applyAsDouble(entry.getKey()))
                .max().orElse(NODE_WIDTH) + LEFT_MARGIN;
    }

    private static int column(GraphNode node, Map<String, List<String>> predecessors, Map<String, GraphNode> byId) {
        if (node instanceof DynamicGeneratorNode || node instanceof SourceNode) return 0;
        if (node instanceof DeviceSlotNode || node instanceof OutputNode) return 5;
        if (node instanceof GainNode gain) {
            GraphNode endpoint = byId.get(gain.endpointNodeId());
            if (endpoint instanceof SourceNode || predecessors.get(node.id()).stream().map(byId::get).anyMatch(SourceNode.class::isInstance)) return 1;
            if (endpoint instanceof OutputNode || endpoint instanceof DeviceSlotNode
                    || predecessors.get(node.id()).stream().map(byId::get).anyMatch(RouterNode.class::isInstance)) return 4;
            return 2;
        }
        if (node instanceof MixerNode) return 2;
        if (node instanceof RouterNode) return 3;
        return 2;
    }

    private static boolean isEventSource(SourceNode source) {
        return EventSoundStudioSource.SELECTOR.equals(source.selector());
    }

    private static void assignReachableLane(SourceNode source, int lane, Map<String, List<String>> successors,
                                            Map<String, Integer> lanes) {
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        queue.add(source.id());
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (!visited.add(id)) continue;
            lanes.merge(id, lane, (previous, candidate) -> previous >= lane ? previous : candidate);
            queue.addAll(successors.getOrDefault(id, List.of()));
        }
    }
}
