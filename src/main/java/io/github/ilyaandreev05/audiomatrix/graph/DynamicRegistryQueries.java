package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.LinkedHashSet;
import java.util.Set;

final class DynamicRegistryQueries {
    private DynamicRegistryQueries() {}

    static DynamicInstanceRegistry.RuntimeInstance requireInstance(DynamicInstanceRegistry.ReconcileResult result,
            String generatorId, String instanceKey, boolean requireAvailable) {
        DynamicInstanceRegistry.RuntimeInstance instance = result.instances()
                .get(new DynamicInstanceRegistry.InstanceAddress(generatorId, instanceKey));
        if (instance == null) throw new IllegalArgumentException("Generated instance does not exist: " + instanceKey);
        if (requireAvailable && !instance.available())
            throw new IllegalArgumentException("Generated instance is no longer available: " + instanceKey);
        return instance;
    }
    static DynamicGeneratorNode generator(DynamicInstanceRegistry.ReconcileResult result, String generatorId) {
        return result.configuredGraph().nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).filter(node -> node.id().equals(generatorId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Dynamic generator does not exist: " + generatorId));
    }
    static GainNode generatedGain(DynamicInstanceRegistry.ReconcileResult result, String generatorId,
                                 String instanceKey) {
        DynamicInstanceRegistry.RuntimeInstance instance = requireInstance(result, generatorId, instanceKey, false);
        Set<String> generatedIds = new LinkedHashSet<>(instance.nodeIds().values());
        return result.effectiveGraph().nodes().stream().filter(GainNode.class::isInstance).map(GainNode.class::cast)
                .filter(node -> generatedIds.contains(node.id())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Generated instance has no Gain node: " + instanceKey));
    }
    static String generatedNodeId(DynamicInstanceRegistry.ReconcileResult result, String generatorId,
                                  String instanceKey, String role) {
        String id = requireInstance(result, generatorId, instanceKey, false).nodeIds().get(role);
        if (id == null) throw new IllegalArgumentException("Generated role does not exist: " + role);
        return id;
    }
}
