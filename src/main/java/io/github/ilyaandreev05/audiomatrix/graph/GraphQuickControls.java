package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Keeps route-scoped quick-control references aligned when graph IDs are duplicated. */
final class GraphQuickControls {
    private GraphQuickControls() {}

    static void duplicate(Map<String, Object> config, String fromRoute, String toRoute,
                          Map<String, String> nodeIds) {
        List<Map<String, Object>> controls = GraphRouteLibrary.mutableList(config, "quick_controls");
        List<Map<String, Object>> copies = new ArrayList<>();
        for (Map<String, Object> control : List.copyOf(controls)) {
            if (!fromRoute.equals(control.get("route_id"))) continue;
            String newNodeId = nodeIds.get(String.valueOf(control.get("node_id")));
            if (newNodeId == null) continue;
            Map<String, Object> copy = GraphRouteLibrary.deep(control);
            copy.put("id", "quick-" + java.util.UUID.randomUUID());
            copy.put("route_id", toRoute);
            copy.put("node_id", newNodeId);
            copies.add(copy);
        }
        controls.addAll(copies);
    }
}
