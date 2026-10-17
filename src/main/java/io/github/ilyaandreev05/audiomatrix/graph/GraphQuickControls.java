package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps route-scoped quick-control references aligned when graph IDs are duplicated. */
final class GraphQuickControls {
    private GraphQuickControls() {}

    static void duplicate(Map<String, Object> config, String fromRoute, String toRoute,
                          Map<String, String> nodeIds) {
        List<Map<String, Object>> controls = GraphRouteLibrary.mutableList(config, "quick_controls");
        List<Map<String, Object>> copies = new ArrayList<>();
        Map<String, String> controlIds = new LinkedHashMap<>();
        for (Map<String, Object> control : List.copyOf(controls)) {
            if (!fromRoute.equals(control.get("route_id"))) continue;
            String newNodeId = nodeIds.get(String.valueOf(control.get("node_id")));
            if (newNodeId == null) continue;
            Map<String, Object> copy = GraphRouteLibrary.deep(control);
            String newControlId = "quick-" + java.util.UUID.randomUUID();
            controlIds.put(String.valueOf(control.get("id")), newControlId);
            copy.put("id", newControlId);
            copy.put("route_id", toRoute);
            copy.put("node_id", newNodeId);
            copies.add(copy);
        }
        controls.addAll(copies);
        QuickSettingsConfig.duplicateRoute(config, fromRoute, toRoute, nodeIds, controlIds);
    }
}
