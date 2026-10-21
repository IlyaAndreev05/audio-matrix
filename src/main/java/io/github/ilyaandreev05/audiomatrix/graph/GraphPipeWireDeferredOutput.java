package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.Map;

final class GraphPipeWireDeferredOutput {
    private GraphPipeWireDeferredOutput() {}

    static boolean isHapticLimiterBoundary(AudioGraph graph, Map<String, GraphNode> nodes, String nodeId) {
        GraphNode anchor = nodes.get(nodeId);
        if (!"octine-vibro-limiter".equals(nodeId)
                || !(anchor instanceof FilterNode limiter) || !"limiter".equals(limiter.processor())) return false;
        for (GraphNode node : graph.nodes()) {
            if (!(node instanceof DynamicGeneratorNode generator)
                    || !"outputs".equals(generator.registry())
                    || !"device:role:haptic_output".equals(generator.selector())) continue;
            Map<String, Object> target = generator.connectTo();
            if (nodeId.equals(target.get("node_id")) && "out".equals(target.get("port_id"))) return true;
        }
        return false;
    }
}
