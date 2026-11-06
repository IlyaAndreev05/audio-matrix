package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Conservative proof that two compiled endpoints address different physical positions. */
public final class GraphOutputChannelIsolation {
    private GraphOutputChannelIsolation() {}

    public static boolean disjoint(GraphNode first, GraphNode second) {
        Set<String> left = claim(first), right = claim(second);
        return left != null && right != null && Collections.disjoint(left, right);
    }

    /** Null means that the compiler cannot prove which physical positions are addressed. */
    private static Set<String> claim(GraphNode node) {
        if (node instanceof OutputNode output && output.channelMatrix() != null) {
            List<String> positions = output.channelMatrix().outputs();
            // MONO can be remapped to a physical speaker rather than a literal MONO port.
            return positions.contains("MONO") ? null : Set.copyOf(positions);
        }
        if (!(node instanceof OutputNode) && !(node instanceof DeviceSlotNode)) return null;
        List<String> channels = node.inputs().getFirst().channels();
        // layout() compiles exactly this ordering to the stereo channel_map. Other layouts
        // either do not compile or can be remapped, so they do not prove isolation.
        return channels.equals(List.of("FL", "FR")) ? Set.of("FL", "FR") : null;
    }

    public static String deviceReference(GraphNode node) {
        String id = node instanceof OutputNode output ? output.deviceId()
                : node instanceof DeviceSlotNode slot ? slot.deviceId() : "";
        if (id == null) return "";
        return id.startsWith("device:") ? id.substring("device:".length()) : id;
    }

    /** A forwarding slot is a control node; its downstream OutputNode owns the PCM endpoint. */
    public static boolean isEndpoint(AudioGraph graph, GraphNode node) {
        return node instanceof OutputNode || node instanceof DeviceSlotNode
                && graph.connections().stream().noneMatch(edge -> edge.enabled() && edge.fromNode().equals(node.id()));
    }
}
