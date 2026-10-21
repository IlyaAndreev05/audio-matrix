package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.config.VersionedConfig;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import java.util.*;
import java.util.concurrent.*;

/** Persists only registry-generated state onto the latest saved configuration. */
final class DynamicRegistryStore {
    private final ConfigStore store;

    DynamicRegistryStore(ConfigStore store) { this.store = store; }

    CompletableFuture<Void> persist(Map<String,Object> config, String routeId, AudioGraph baseGraph,
            DynamicInstanceRegistry.ReconcileResult result, List<Map<String,Object>> newRules, boolean enabled) {
        long expected = config instanceof VersionedConfig versioned ? versioned.revision() : store.revision();
        return CompletableFuture.runAsync(() -> { try { store.requireRevision(expected); } catch (Exception e) { throw new CompletionException(e); } })
                .thenCompose(ignored -> {
                    if (!enabled || (newRules.isEmpty() && (result == null || !result.configChanged()))) return CompletableFuture.completedFuture(null);
                    return store.updateAsync(latest -> {
                        if (store.revision() != expected) throw new IllegalStateException("Configuration changed before dynamic graph discovery could be saved");
                        if (result != null && result.configChanged()) mergeRegistryChanges(latest, routeId, baseGraph, result);
                        mergeApplicationRules(latest, newRules);
                    });
                });
    }

    private static void mergeRegistryChanges(Map<String,Object> latest, String routeId, AudioGraph baseGraph,
            DynamicInstanceRegistry.ReconcileResult result) {
        List<Map<String,Object>> routes = mutableMaps(latest.get("route_library"));
        Map<String,Object> route = routes.stream().filter(r -> routeId.equals(r.get("id"))).findFirst().orElseThrow(
                () -> new IllegalStateException("Route disappeared before dynamic registry state could be saved"));
        List<Map<String,Object>> nodes = mutableMaps(route.get("nodes")); Map<String,Map<String,Object>> raw = new LinkedHashMap<>();
        nodes.forEach(n -> raw.put(text(n.get("id")), n)); Map<String,GraphNode> generated = index(result.configuredGraph());
        for (GraphNode old : baseGraph.nodes()) {
            GraphNode next = generated.get(old.id()); Map<String,Object> item = raw.get(old.id());
            if (old instanceof DynamicGeneratorNode before && next instanceof DynamicGeneratorNode after && item != null) {
                Map<String,Object> params = mutableObject(item.get("parameters")), saved = mutableObject(params.get("instances"));
                mergeInstances(saved, before.instances(), after.instances(), before.retainOnDisappear()); params.put("instances", saved);
                if (Boolean.FALSE.equals(after.parameters().get("auto_select_trusted_hdmi_on_first_discovery"))) params.put("auto_select_trusted_hdmi_on_first_discovery", false);
                item.put("parameters", params);
            } else if (old instanceof SwitchNode a && next instanceof SwitchNode b && item != null) mergeSwitch(item, a, b);
        }
        route.put("nodes", nodes); latest.put("route_library", routes);
        // A discovered instance keeps its own canvas container, so the saved configuration stays valid.
        GraphSubgraphLibrary.ensureGeneratorComposites(latest, GraphCodec.fromMap(route));
    }

    private static void mergeInstances(Map<String,Object> saved, Map<String,Object> before, Map<String,Object> after, boolean retain) {
        after.forEach((key,value) -> { Map<String,Object> g = object(value), c = mutableObject(saved.get(key)), ids = mutableObject(c.get("node_ids")); object(g.get("node_ids")).forEach(ids::putIfAbsent); c.put("node_ids", ids); c.putIfAbsent("composite_id", g.get("composite_id")); c.putIfAbsent("overrides", new LinkedHashMap<String,Object>()); saved.put(key, c); });
        if (!retain) before.keySet().stream().filter(key -> !after.containsKey(key)).forEach(saved::remove);
    }

    private static void mergeSwitch(Map<String,Object> raw, SwitchNode before, SwitchNode after) {
        List<String> added = after.outputs().stream().map(AudioPort::id).filter(id -> before.outputs().stream().noneMatch(p -> p.id().equals(id))).toList();
        List<String> links = after.activeLinks().stream().filter(link -> !before.activeLinks().contains(link)).toList();
        if (added.isEmpty() && links.isEmpty()) return;
        Map<String,Object> params = mutableObject(raw.get("parameters")); List<String> ports = strings(params.get("output_ports")), active = strings(params.get("active_links"));
        added.forEach(p -> { if (!ports.contains(p)) ports.add(p); }); links.forEach(link -> { int i = link.indexOf("->"); if (i > 0 && ports.contains(link.substring(i + 2)) && !active.contains(link)) active.add(link); });
        params.put("output_ports", ports); params.put("active_links", active); raw.put("parameters", params);
    }

    private static void mergeApplicationRules(Map<String,Object> latest, List<Map<String,Object>> added) {
        if (added.isEmpty()) return;
        List<Map<String,Object>> rules = mutableMaps(latest.get("applications"));
        added.forEach(rule -> { if (rules.stream().noneMatch(existing -> Objects.equals(existing.get("match"), rule.get("match")))) rules.add(new LinkedHashMap<>(rule)); });
        latest.put("applications", rules);
    }

    private static Map<String,GraphNode> index(AudioGraph graph) { Map<String,GraphNode> result = new LinkedHashMap<>(); graph.nodes().forEach(n -> result.put(n.id(), n)); return result; }

    /** Signature input without runtime-generated registry records, so discovery never re-triggers an apply. */
    static Map<String,Object> stableConfig(Map<String,Object> config) {
        Map<String,Object> result = new LinkedHashMap<>(config); List<Map<String,Object>> routes = mutableMaps(config.get("route_library"));
        for (Map<String,Object> route : routes) {
            List<Map<String,Object>> nodes = mutableMaps(route.get("nodes"));
            for (Map<String,Object> node : nodes) {
                Map<String,Object> params = mutableObject(node.get("parameters"));
                if ("dynamic_generator".equals(node.get("type"))) params.remove("instances");
                else if ("switch".equals(node.get("type")) || "multi_switch".equals(node.get("type"))) {
                    params.put("output_ports", strings(params.get("output_ports")).stream().filter(port -> !port.startsWith("generated-output-")).toList());
                    params.put("active_links", strings(params.get("active_links")).stream().filter(link -> !link.contains("->generated-output-")).toList());
                }
                node.put("parameters", params);
            }
            route.put("nodes", nodes);
        }
        result.put("route_library", routes);
        return result;
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value) { return value instanceof List<?> list ? (List<Map<String,Object>>)list : List.of(); }
    private static List<Map<String,Object>> mutableMaps(Object value) { List<Map<String,Object>> result = new ArrayList<>(); for (Map<String,Object> item : maps(value)) result.add(new LinkedHashMap<>(item)); return result; }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { return value instanceof Map<?,?> map ? (Map<String,Object>)map : Map.of(); }
    private static Map<String,Object> mutableObject(Object value) { return new LinkedHashMap<>(object(value)); }
    private static List<String> strings(Object value) { return value instanceof List<?> list ? new ArrayList<>(list.stream().map(String::valueOf).toList()) : new ArrayList<>(); }
    private static String text(Object value) { return value instanceof String text ? text : ""; }
}
