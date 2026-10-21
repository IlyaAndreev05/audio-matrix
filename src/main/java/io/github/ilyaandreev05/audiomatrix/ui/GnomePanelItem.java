package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphSubgraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import java.util.Map;

record GnomePanelItem(String kind, String label, String routeId, String nodeId, String instanceKey, String groupId,
                      Map<String, Object> pin, GraphNode node, GraphSubgraph composite,
                      DynamicInstanceRegistry.RuntimeInstance runtime) {
    String key() { return kind + "\u0001" + routeId + "\u0001" + nodeId + "\u0001" + instanceKey + "\u0001" + groupId; }
}
