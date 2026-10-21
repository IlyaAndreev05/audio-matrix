package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The generated children of one dynamic generator instance, from the saved records or the runtime. */
final class GraphGeneratedInstances {
    private GraphGeneratedInstances() {}

    /** One instance of a generator with the node ids of its generated children. */
    record Instance(String key, Map<String, String> nodeIds) {}

    /**
     * Returns the instances of the generator. The saved records describe the configured children;
     * the runtime instances describe the children the registry materialized for this session.
     */
    static List<Instance> of(DynamicGeneratorNode generator,
                             Map<DynamicInstanceRegistry.InstanceAddress, DynamicInstanceRegistry.RuntimeInstance> runtimeInstances) {
        List<Instance> result = new ArrayList<>();
        Object raw = generator.parameters().get("instances");
        if (raw instanceof Map<?, ?> instances)
            for (Map.Entry<?, ?> entry : instances.entrySet()) {
                if (!(entry.getKey() instanceof String key)) continue;
                if (!(entry.getValue() instanceof Map<?, ?> instance)) continue;
                if (!(instance.get("node_ids") instanceof Map<?, ?> nodeIds)) continue;
                Map<String, String> ids = new LinkedHashMap<>();
                nodeIds.forEach((role, id) -> {
                    if (role instanceof String name && id instanceof String value) ids.put(name, value);
                });
                if (!ids.isEmpty()) result.add(new Instance(key, ids));
            }
        if (runtimeInstances == null) return result;
        for (Map.Entry<DynamicInstanceRegistry.InstanceAddress, DynamicInstanceRegistry.RuntimeInstance> entry
                : runtimeInstances.entrySet()) {
            if (!generator.id().equals(entry.getKey().generatorId())) continue;
            DynamicInstanceRegistry.RuntimeInstance runtime = entry.getValue();
            if (runtime.nodeIds().isEmpty()) continue;
            result.add(new Instance(runtime.instanceKey(), runtime.nodeIds()));
        }
        return result;
    }
}
