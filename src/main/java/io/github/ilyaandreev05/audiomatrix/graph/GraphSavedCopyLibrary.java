package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.config.GraphMatrixViewSettings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stores inactive route snapshots and restores them as independent routes. */
final class GraphSavedCopyLibrary {
    private GraphSavedCopyLibrary() {}

    static String save(Map<String, Object> config, String routeId, String label) {
        Map<String, Object> route = route(config, routeId);
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("id", id("copy"));
        copy.put("name", label == null || label.isBlank() ? String.valueOf(route.get("name")) : label);
        copy.put("route", GraphRouteLibrary.deep(route));
        copy.put("profile_id", route.get("profile_id"));
        copy.put("active", false);
        copyUiState(config, routeId, copy);
        copySubgraphs(config, routeId, copy);
        copyControls(config, routeId, copy);
        copy.put("quick_pins", QuickSettingsConfig.copyPinsForRoute(config, routeId));
        GraphRouteLibrary.mutableList(config, "saved_copies").add(copy);
        return String.valueOf(copy.get("id"));
    }

    private static void copyUiState(Map<String, Object> config, String routeId, Map<String, Object> copy) {
        Map<String, Object> layouts = GraphRouteLibrary.mutableMap(config, "graph_ui_layout");
        copy.put("ui_layout", GraphRouteLibrary.deep(GraphRouteLibrary.map(layouts.get(routeId))));
        Object scale = GraphRouteLibrary.mutableMap(config, "graph_ui_scale").get(routeId);
        if (scale instanceof Number) copy.put("ui_scale", scale);
        copy.put("ui_matrix_views", GraphMatrixViewSettings.copyForRoute(config, routeId));
    }

    private static void copySubgraphs(Map<String, Object> config, String routeId, Map<String, Object> copy) {
        copy.put("ui_subgraphs", GraphSubgraphLibrary.encoded(GraphSubgraphLibrary.forRoute(config, routeId)));
    }

    private static void copyControls(Map<String, Object> config, String routeId, Map<String, Object> copy) {
        copy.put("quick_controls", GraphRouteLibrary.maps(config.get("quick_controls")).stream()
                .filter(control -> routeId.equals(control.get("route_id")))
                .map(GraphRouteLibrary::deep).toList());
    }

    static AudioGraph restore(Map<String, Object> config, String copyId, String newName) {
        Map<String, Object> copy = GraphRouteLibrary.find(GraphRouteLibrary.maps(config.get("saved_copies")), copyId);
        if (copy == null) throw new IllegalArgumentException("Saved copy does not exist: " + copyId);
        Map<String, Object> snapshot = routeSnapshot(copy);
        AudioGraph graph = GraphCodec.fromMap(snapshot);
        GraphRouteLibrary.requireValid(graph);
        String temporaryId = id("restore-source");
        String profileId = profileId(config, copy, snapshot);
        String groupId = groupId(config, snapshot, profileId);
        List<Map<String, Object>> routes = GraphRouteLibrary.mutableList(config, "route_library");
        routes.add(temporaryRoute(snapshot, temporaryId, profileId, groupId));
        Map<String, Object> layouts = GraphRouteLibrary.mutableMap(config, "graph_ui_layout");
        Map<String, Object> scales = GraphRouteLibrary.mutableMap(config, "graph_ui_scale");
        Map<String, Object> subgraphs = GraphRouteLibrary.mutableMap(config, "graph_ui_subgraphs");
        Map<String, Object> matrixViews = GraphRouteLibrary.mutableMap(config, "graph_ui_matrix_views");
        List<Map<String, Object>> controls = GraphRouteLibrary.mutableList(config, "quick_controls");
        restoreUi(copy, temporaryId, layouts, scales, subgraphs, matrixViews);
        Map<String, String> controlIds = restoreControls(copy, temporaryId, controls);
        QuickSettingsConfig.restorePinsFromCopy(config, copy.get("quick_pins"), temporaryId, controlIds);
        try {
            String name = newName == null || newName.isBlank() ? graph.name() + " (копия)" : newName;
            return GraphRouteLibrary.duplicateRoute(config, temporaryId, name, profileId, groupId);
        } finally {
            cleanupTemporary(config, temporaryId, routes, layouts, scales, subgraphs, matrixViews, controls);
            QuickSettingsConfig.removeRoute(config, temporaryId);
        }
    }

    private static Map<String, Object> routeSnapshot(Map<String, Object> copy) {
        Object value = copy.get("route");
        if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Saved copy has no route snapshot");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        raw.forEach((key, item) -> snapshot.put(String.valueOf(key), item));
        return snapshot;
    }

    private static String profileId(Map<String, Object> config, Map<String, Object> copy,
                                    Map<String, Object> snapshot) {
        String profileId = String.valueOf(copy.getOrDefault("profile_id",
                snapshot.getOrDefault("profile_id", GraphRouteLibrary.activeProfileId(config))));
        return GraphRouteLibrary.find(GraphRouteLibrary.maps(config.get("profiles")), profileId) == null
                ? GraphRouteLibrary.activeProfileId(config) : profileId;
    }

    private static String groupId(Map<String, Object> config, Map<String, Object> snapshot, String profileId) {
        String groupId = String.valueOf(snapshot.getOrDefault("group_id", ""));
        Map<String, Object> group = GraphRouteLibrary.find(GraphRouteLibrary.maps(config.get("route_groups")), groupId);
        return group == null || !profileId.equals(group.get("profile_id")) ? "" : groupId;
    }

    private static Map<String, Object> temporaryRoute(Map<String, Object> snapshot, String id,
                                                      String profileId, String groupId) {
        Map<String, Object> source = new LinkedHashMap<>(snapshot);
        source.put("id", id);
        source.put("profile_id", profileId);
        source.put("group_id", groupId);
        return source;
    }

    private static void restoreUi(Map<String, Object> copy, String id, Map<String, Object> layouts,
                                  Map<String, Object> scales, Map<String, Object> subgraphs,
                                  Map<String, Object> matrixViews) {
        Map<String, Object> savedLayout = GraphRouteLibrary.map(copy.get("ui_layout"));
        if (!savedLayout.isEmpty()) layouts.put(id, GraphRouteLibrary.deep(savedLayout));
        if (copy.get("ui_scale") instanceof Number scale) scales.put(id, scale);
        if (copy.get("ui_subgraphs") instanceof List<?> list) subgraphs.put(id, GraphRouteLibrary.deepValue(list));
        Map<String, Object> savedViews = GraphRouteLibrary.map(copy.get("ui_matrix_views"));
        if (!savedViews.isEmpty()) matrixViews.put(id, savedViews);
    }

    private static Map<String, String> restoreControls(Map<String, Object> copy, String routeId,
                                                       List<Map<String, Object>> controls) {
        Map<String, String> ids = new LinkedHashMap<>();
        if (!(copy.get("quick_controls") instanceof List<?> savedControls)) return ids;
        for (Object item : savedControls) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> control = new LinkedHashMap<>();
            raw.forEach((key, value) -> control.put(String.valueOf(key), GraphRouteLibrary.deepValue(value)));
            String oldId = String.valueOf(control.getOrDefault("id", ""));
            String newId = id("quick");
            ids.put(oldId, newId);
            control.put("id", newId);
            control.put("route_id", routeId);
            controls.add(control);
        }
        return ids;
    }

    private static void cleanupTemporary(Map<String, Object> config, String id,
                                         List<Map<String, Object>> routes, Map<String, Object> layouts,
                                         Map<String, Object> scales, Map<String, Object> subgraphs,
                                         Map<String, Object> matrixViews,
                                         List<Map<String, Object>> controls) {
        routes.removeIf(route -> id.equals(route.get("id")));
        layouts.remove(id);
        scales.remove(id);
        subgraphs.remove(id);
        matrixViews.remove(id);
        controls.removeIf(control -> id.equals(control.get("route_id")));
    }

    static void delete(Map<String, Object> config, String copyId) {
        List<Map<String, Object>> copies = GraphRouteLibrary.mutableList(config, "saved_copies");
        if (GraphRouteLibrary.indexOf(copies, copyId) < 0)
            throw new IllegalArgumentException("Saved copy does not exist: " + copyId);
        copies.removeIf(copy -> copyId.equals(copy.get("id")));
    }

    private static Map<String, Object> route(Map<String, Object> config, String routeId) {
        Map<String, Object> route = GraphRouteLibrary.find(GraphRouteLibrary.maps(config.get("route_library")), routeId);
        if (route == null) throw new IllegalArgumentException("Route does not exist: " + routeId);
        return route;
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
