package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphEndpointGainFactory;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/** Keeps endpoint sliders attached to stable graph Gain nodes. */
public final class GraphEndpointGainQuickControls {
    private GraphEndpointGainQuickControls() {}

    public static void ensure(Map<String, Object> config, AudioGraph graph,
                              Set<String> newEndpointIds, Set<String> migratedEndpointIds) {
        for (SourceNode source : graph.nodes().stream().filter(SourceNode.class::isInstance)
                .map(SourceNode.class::cast).toList()) {
            if (!shouldPin(config, graph.id(), source.id(), newEndpointIds, migratedEndpointIds)) continue;
            GainNode gain = GraphEndpointGainFactory.gainFor(graph, source);
            if (gain == null) continue;
            ensurePin(config, graph.id(), source.id(), "Источники");
            Map<String, Object> pin = ensurePin(config, graph.id(), gain.id(), "Источники");
            ensureSourceControl(config, graph.id(), source, gain, pin);
        }
        for (OutputNode output : graph.nodes().stream().filter(OutputNode.class::isInstance)
                .map(OutputNode.class::cast).toList()) {
            if (!shouldPin(config, graph.id(), output.id(), newEndpointIds, migratedEndpointIds)) continue;
            ensurePin(config, graph.id(), output.id(), "Выходные устройства");
        }
    }

    private static boolean shouldPin(Map<String, Object> config, String routeId, String endpointId,
                                     Set<String> newIds, Set<String> migratedIds) {
        return newIds.contains(endpointId) || migratedIds.contains(endpointId)
                && QuickSettingsConfig.pins(config).stream().anyMatch(pin -> routeId.equals(pin.get("route_id"))
                && endpointId.equals(pin.get("node_id")));
    }

    private static Map<String, Object> ensurePin(Map<String, Object> config, String routeId,
                                                  String nodeId, String groupName) {
        Map<String, Object> existing = QuickSettingsConfig.pins(config).stream()
                .filter(pin -> routeId.equals(pin.get("route_id")) && nodeId.equals(pin.get("node_id")))
                .findFirst().orElse(null);
        if (existing != null) return existing;
        Map<String, Object> group = QuickSettingsConfig.findOrCreateGroup(config, groupName);
        int order = QuickSettingsConfig.pins(config).stream()
                .filter(pin -> group.get("id").equals(pin.get("group_id")))
                .mapToInt(pin -> pin.get("order") instanceof Number number ? number.intValue() : -1)
                .max().orElse(-1) + 1;
        return QuickSettingsConfig.pinNode(config, routeId, nodeId, String.valueOf(group.get("id")),
                order, "EXPANDED");
    }

    private static void ensureSourceControl(Map<String, Object> config, String routeId, SourceNode source,
                                            GainNode gain, Map<String, Object> pin) {
        List<Map<String, Object>> controls = controls(config);
        Map<String, Object> control = controls.stream().filter(item -> routeId.equals(item.get("route_id"))
                && gain.id().equals(item.get("node_id")) && "gain".equals(item.get("parameter_id")))
                .findFirst().orElse(null);
        if (control == null) {
            String id = "quick-endpoint-" + UUID.randomUUID();
            control = DefaultConfig.map("id", id, "route_id", routeId, "node_id", gain.id(),
                    "parameter_id", "gain", "label", source.name() + " · уровень",
                    "section", pinGroup(config, pin), "group_id", pin.get("group_id"), "order", pin.get("order"));
            controls.add(control);
        }
        QuickSettingsConfig.attachControl(config, routeId, gain.id(), String.valueOf(control.get("id")));
    }

    private static String pinGroup(Map<String, Object> config, Map<String, Object> pin) {
        return QuickSettingsConfig.groups(config).stream()
                .filter(group -> pin.get("group_id").equals(group.get("id")))
                .map(group -> String.valueOf(group.get("name"))).findFirst().orElse("Источники");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> controls(Map<String, Object> config) {
        if (config.get("quick_controls") instanceof List<?> list)
            return (List<Map<String, Object>>) list;
        List<Map<String, Object>> created = new ArrayList<>();
        config.put("quick_controls", created);
        return created;
    }
}
