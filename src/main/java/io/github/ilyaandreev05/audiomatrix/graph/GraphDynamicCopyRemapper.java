package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministically remaps generator-owned IDs when a route is copied. */
final class GraphDynamicCopyRemapper {
    private GraphDynamicCopyRemapper() {}

    record Mapping(Map<String, String> nodeIds, Map<String, String> portIds) {
        Mapping {
            nodeIds = Map.copyOf(nodeIds);
            portIds = Map.copyOf(portIds);
        }
    }

    static Mapping create(AudioGraph graph, Map<String, String> copiedGeneratorIds) {
        Map<String, String> childIds = new LinkedHashMap<>();
        Map<String, String> outputPorts = new LinkedHashMap<>();
        for (GraphNode node : graph.nodes()) {
            if (!(node instanceof DynamicGeneratorNode generator)) continue;
            String copiedGeneratorId = copiedGeneratorIds.get(generator.id());
            if (copiedGeneratorId == null) continue;
            for (Map.Entry<String, Object> entry : generator.instances().entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> instance)) continue;
                if (instance.get("node_ids") instanceof Map<?, ?> rawNodeIds)
                    remapNodeIds(generator, copiedGeneratorId, entry.getKey(), rawNodeIds, childIds);
                if (instance.get("composite_id") instanceof String compositeId && !compositeId.isBlank())
                    childIds.put(compositeId, DynamicInstanceRegistry.stableCompositeId(copiedGeneratorId, entry.getKey()));
                if ("outputs".equals(generator.registry()))
                    remapOutputPort(generator, copiedGeneratorId, entry.getKey(), outputPorts);
            }
        }
        return new Mapping(childIds, outputPorts);
    }

    private static void remapNodeIds(DynamicGeneratorNode generator, String copiedGeneratorId, String instanceKey,
                                     Map<?, ?> rawNodeIds, Map<String, String> result) {
        rawNodeIds.forEach((rawRole, rawId) -> {
            if (!(rawRole instanceof String role) || !(rawId instanceof String nodeId)) return;
            result.put(nodeId, DynamicInstanceRegistry.stableNodeId(copiedGeneratorId, instanceKey, role));
        });
    }

    private static void remapOutputPort(DynamicGeneratorNode generator, String copiedGeneratorId,
                                        String instanceKey, Map<String, String> result) {
        if (!instanceKey.startsWith("device:")) return;
        String fingerprint = instanceKey.substring("device:".length());
        if (fingerprint.isBlank() || fingerprint.indexOf(':') >= 0) return;
        result.put(DynamicInstanceRegistry.stableOutputPortId(generator.id(), fingerprint),
                DynamicInstanceRegistry.stableOutputPortId(copiedGeneratorId, fingerprint));
    }
}
