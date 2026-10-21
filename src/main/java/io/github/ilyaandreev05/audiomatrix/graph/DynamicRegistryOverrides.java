package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;
import java.util.Map;
import java.util.Optional;

final class DynamicRegistryOverrides {
    private static final java.util.Set<String> OVERRIDE_FIELDS = java.util.Set.of("gain", "muted", "display_name", "icon",
            "pinned", "group_id", "order", "expanded", "show_children", "show_when_offline");
    private DynamicRegistryOverrides() {}

    static boolean setInstanceOverride(Map<String, Object> config, String routeId, String generatorId,
            String instanceKey, String field, Object value) {
        if (!OVERRIDE_FIELDS.contains(field))
            throw new IllegalArgumentException("Unsupported generated instance override: " + field);
        Map<String, Object> route = route(config, routeId);
        Object rawNodes = route.get("nodes");
        if (!(rawNodes instanceof List<?> rawList)) throw new IllegalArgumentException("Route has no graph nodes");
        @SuppressWarnings("unchecked") List<Object> nodes = (List<Object>) rawList;
        for (int index = 0; index < nodes.size(); index++) {
            Map<String, Object> node = DynamicRegistryValues.object(nodes.get(index));
            if (!generatorId.equals(node.get("id")) || !"dynamic_generator".equals(node.get("type"))) continue;
            Map<String, Object> parameters = DynamicRegistryValues.mutableNestedObject(node, "parameters");
            String registry = DynamicRegistryValues.text(parameters.getOrDefault("registry", "applications"));
            if (("applications".equals(registry) || "outputs".equals(registry))
                    && ("gain".equals(field) || "muted".equals(field)))
                throw new IllegalArgumentException("Application/output gain and mute are stored in their canonical rule or endpoint settings");
            Map<String, Object> instances = DynamicRegistryValues.mutableNestedObject(parameters, "instances");
            Object rawRecord = instances.get(instanceKey);
            if (!(rawRecord instanceof Map<?, ?>))
                throw new IllegalArgumentException("Generated instance does not exist: " + instanceKey);
            Map<String, Object> record = DynamicRegistryValues.mutableObject(rawRecord);
            Map<String, Object> overrides = DynamicRegistryValues.mutableObject(record.get("overrides"));
            if (value == null) {
                if (!overrides.containsKey(field)) return false;
                overrides.remove(field);
            } else {
                validateOverrideValue(field, value);
                if (overrides.containsKey(field) && DynamicRegistryValues.deepEquals(overrides.get(field), value))
                    return false;
                overrides.put(field, DynamicRegistryValues.deep(value));
            }
            record.put("overrides", overrides);
            instances.put(instanceKey, record);
            parameters.put("instances", instances);
            node.put("parameters", parameters);
            nodes.set(index, node);
            return true;
        }
        throw new IllegalArgumentException("Dynamic generator does not exist: " + generatorId);
    }

    static Optional<Object> instanceOverride(Map<String, Object> config, String routeId, String generatorId,
                                             String instanceKey, String field) {
        Map<String, Object> route = route(config, routeId);
        if (!(route.get("nodes") instanceof List<?> nodes)) return Optional.empty();
        for (Object item : nodes) {
            Map<String, Object> node = DynamicRegistryValues.object(item);
            if (!generatorId.equals(node.get("id")) || !"dynamic_generator".equals(node.get("type"))) continue;
            Map<String, Object> parameters = DynamicRegistryValues.object(node.get("parameters"));
            Map<String, Object> instances = DynamicRegistryValues.object(parameters.get("instances"));
            Map<String, Object> record = DynamicRegistryValues.object(instances.get(instanceKey));
            Map<String, Object> overrides = DynamicRegistryValues.object(record.get("overrides"));
            return overrides.containsKey(field) ? Optional.ofNullable(DynamicRegistryValues.deep(overrides.get(field)))
                    : Optional.empty();
        }
        throw new IllegalArgumentException("Dynamic generator does not exist: " + generatorId);
    }

    static DynamicInstanceRegistry.GainState instanceGainBaseline(Map<String, Object> config, String routeId,
                                                                   String generatorId) {
        AudioGraph graph = GraphCodec.fromMap(route(config, routeId));
        DynamicGeneratorNode generator = graph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).filter(node -> node.id().equals(generatorId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Dynamic generator does not exist: " + generatorId));
        if (!"browser_tabs".equals(generator.registry()))
            throw new IllegalArgumentException("Per-instance gain overrides are available only for browser tabs");
        AudioGraph template = templateGraph(generator);
        GainNode gain = template.nodes().stream().filter(GainNode.class::isInstance).map(GainNode.class::cast)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Browser tab template has no Gain node"));
        return new DynamicInstanceRegistry.GainState(generator.initialGain(), gain.muted());
    }
    static boolean instanceMutedBaseline(Map<String, Object> config, String routeId, String generatorId) {
        return instanceGainBaseline(config, routeId, generatorId).muted();
    }

    static boolean rehomeInstanceGroupOverrides(Map<String, Object> config, String oldGroupId, String newGroupId) {
        if (oldGroupId == null || oldGroupId.isBlank()) return false;
        boolean changed = false;
        if (config.get("route_library") instanceof List<?> rawRoutes) {
            @SuppressWarnings("unchecked") List<Object> routes = (List<Object>) rawRoutes;
            for (int i = 0; i < routes.size(); i++) {
                Map<String, Object> route = DynamicRegistryValues.object(routes.get(i));
                if (rehomeGraphMap(route, oldGroupId, newGroupId)) {
                    routes.set(i, route);
                    changed = true;
                }
            }
        }
        if (config.get("saved_copies") instanceof List<?> rawCopies) {
            @SuppressWarnings("unchecked") List<Object> copies = (List<Object>) rawCopies;
            for (int i = 0; i < copies.size(); i++) {
                Map<String, Object> copy = DynamicRegistryValues.object(copies.get(i));
                Map<String, Object> route = DynamicRegistryValues.object(copy.get("route"));
                if (rehomeGraphMap(route, oldGroupId, newGroupId)) {
                    copy.put("route", route);
                    copies.set(i, copy);
                    changed = true;
                }
            }
        }
        if (config.get("common_vibro_graph") instanceof Map<?, ?> rawCommon) {
            Map<String, Object> common = DynamicRegistryValues.object(rawCommon);
            if (rehomeGraphMap(common, oldGroupId, newGroupId)) {
                config.put("common_vibro_graph", common);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean rehomeGraphMap(Map<String, Object> graph, String oldGroupId, String newGroupId) {
        if (!(graph.get("nodes") instanceof List<?> rawNodes)) return false;
        @SuppressWarnings("unchecked") List<Object> nodes = (List<Object>) rawNodes;
        boolean changed = false;
        for (int i = 0; i < nodes.size(); i++) {
            Map<String, Object> node = DynamicRegistryValues.object(nodes.get(i));
            if (!"dynamic_generator".equals(node.get("type"))) continue;
            Map<String, Object> parameters = DynamicRegistryValues.object(node.get("parameters"));
            boolean generatorChanged = false;
            if (oldGroupId.equals(parameters.get("group_id"))) {
                parameters.put("group_id", newGroupId == null ? "" : newGroupId);
                generatorChanged = true;
            }
            Map<String, Object> instances = DynamicRegistryValues.mutableObject(parameters.get("instances"));
            boolean instancesChanged = false;
            for (Map.Entry<String, Object> instance : instances.entrySet()) {
                Map<String, Object> record = DynamicRegistryValues.object(instance.getValue());
                Map<String, Object> overrides = DynamicRegistryValues.mutableObject(record.get("overrides"));
                if (!oldGroupId.equals(overrides.get("group_id"))) continue;
                if (newGroupId == null || newGroupId.isBlank()) overrides.remove("group_id");
                else overrides.put("group_id", newGroupId);
                record.put("overrides", overrides);
                instance.setValue(record);
                instancesChanged = true;
            }
            if (instancesChanged) {
                parameters.put("instances", instances);
                generatorChanged = true;
            }
            if (generatorChanged) {
                node.put("parameters", parameters);
                nodes.set(i, node);
                changed = true;
            }
        }
        return changed;
    }

    private static void validateOverrideValue(String field, Object value) {
        switch (field) {
            case "gain" -> {
                double gain = DynamicRegistryValues.number(value, Double.NaN);
                if (!Double.isFinite(gain) || gain < 0 || gain > 1)
                    throw new IllegalArgumentException("Instance gain must be between 0 and 1");
            }
            case "muted", "pinned", "expanded", "show_children", "show_when_offline" -> {
                if (!(value instanceof Boolean))
                    throw new IllegalArgumentException("Instance override " + field + " must be boolean");
            }
            case "display_name", "icon", "group_id" -> {
                if (!(value instanceof String))
                    throw new IllegalArgumentException("Instance override " + field + " must be a string");
            }
            case "order" -> {
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
                    throw new IllegalArgumentException("Instance override order must be numeric");
            }
            default -> throw new IllegalArgumentException("Unsupported generated instance override: " + field);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> route(Map<String, Object> config, String routeId) {
        Object raw = config.get("route_library");
        if (raw instanceof List<?> routes) for (Object item : routes) {
            if (item instanceof Map<?, ?> map && routeId.equals(map.get("id")))
                return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Route does not exist: " + routeId);
    }
    private static AudioGraph templateGraph(DynamicGeneratorNode generator) {
        Map<String, Object> encoded = new java.util.LinkedHashMap<>();
        encoded.put("id", "template-" + generator.id());
        encoded.put("name", generator.name() + " template");
        encoded.put("nodes", generator.templateNodes());
        encoded.put("connections", generator.templateConnections());
        return GraphCodec.fromMap(encoded);
    }
}
