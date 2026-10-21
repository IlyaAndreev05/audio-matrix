package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;
import java.util.Map;

/** Pure graph checks; validation never creates PipeWire nodes or plays audio. */
public final class GraphValidator {
    private GraphValidator() {}

    public static List<String> validate(AudioGraph graph) {
        return GraphTopologyValidator.validate(graph);
    }

    /** Validates a graph whose generated children were materialized for the given runtime instances. */
    public static List<String> validate(AudioGraph graph,
                                         Map<DynamicInstanceRegistry.InstanceAddress,
                                         DynamicInstanceRegistry.RuntimeInstance> runtimeInstances) {
        return GraphTopologyValidator.validate(graph, runtimeInstances);
    }
}
