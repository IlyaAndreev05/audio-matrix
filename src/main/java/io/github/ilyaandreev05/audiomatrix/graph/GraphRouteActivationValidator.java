package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.Map;

/** Rejects incomplete routes before they replace the currently usable route. */
public final class GraphRouteActivationValidator {
    private GraphRouteActivationValidator() {}

    public static void requireActivatable(Map<String, Object> config, Map<String, Object> route) {
        AudioGraph graph = GraphCodec.fromMap(route);
        GraphPipeWireCompiler.compile(graph, config);
    }
}
