package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DynamicGeneratorInstanceValidator {
    private static final Set<String> OVERRIDE_FIELDS = Set.of("gain", "muted", "pinned", "expanded",
            "show_children", "show_when_offline", "display_name", "icon", "group_id", "order");
    private static final Set<String> APPLICATION_LEVEL_OVERRIDES = Set.of("gain", "muted");

    private DynamicGeneratorInstanceValidator() {}

    static void validate(DynamicGeneratorNode generator, AudioGraph template,
                         Set<String> configuredNodeIds, Set<String> generatedNodeIds,
                         List<String> errors) {
        Object rawInstances = generator.parameters().get("instances");
        if (!(rawInstances instanceof Map<?, ?> instances)) {
            errors.add("Dynamic generator " + generator.id() + " instances must be an object");
            return;
        }
        Set<String> roles = new LinkedHashSet<>();
        if (template != null) template.nodes().forEach(node -> roles.add(node.id()));
        for (Map.Entry<?, ?> entry : instances.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                errors.add("Dynamic generator " + generator.id() + " has a blank instance key");
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?> instance)) {
                errors.add("Dynamic generator " + generator.id() + " instance " + key + " must be an object");
                continue;
            }
            validateNodeIds(generator, key, instance.get("node_ids"), roles,
                    configuredNodeIds, generatedNodeIds, errors);
            validateComposite(generator, key, instance.get("composite_id"), errors);
            if (!(instance.get("overrides") instanceof Map<?, ?> overrides)) {
                errors.add("Dynamic generator " + generator.id() + " instance " + key + " overrides must be an object");
            } else {
                validateOverrides(generator, key, overrides, errors);
            }
        }
    }

    private static void validateNodeIds(DynamicGeneratorNode generator, String key, Object rawNodeIds,
                                        Set<String> roles, Set<String> configuredNodeIds,
                                        Set<String> generatedNodeIds, List<String> errors) {
        if (!(rawNodeIds instanceof Map<?, ?> nodeIds)) {
            errors.add("Dynamic generator " + generator.id() + " instance " + key + " node_ids must be an object");
            return;
        }
        Set<String> instanceIds = new HashSet<>();
        for (String role : roles) {
            Object rawId = nodeIds.get(role);
            if (!(rawId instanceof String nodeId) || nodeId.isBlank()) {
                errors.add("Dynamic generator " + generator.id() + " instance " + key
                        + " is missing a nonblank node id for template role " + role);
                continue;
            }
            if (!instanceIds.add(nodeId)) {
                errors.add("Dynamic generator " + generator.id() + " instance " + key
                        + " maps multiple template roles to node id " + nodeId);
            } else if (configuredNodeIds.contains(nodeId)) {
                errors.add("Dynamic generator " + generator.id() + " generated node id " + nodeId
                        + " collides with a configured graph node id");
            } else if (!generatedNodeIds.add(nodeId)) {
                errors.add("Dynamic generator " + generator.id() + " has duplicate generated node id " + nodeId);
            }
        }
        for (Map.Entry<?, ?> entry : nodeIds.entrySet()) {
            if (!(entry.getKey() instanceof String role) || role.isBlank()) {
                errors.add("Dynamic generator " + generator.id() + " instance " + key + " has an invalid node_ids role");
            } else if (!roles.contains(role)) {
                errors.add("Dynamic generator " + generator.id() + " instance " + key
                        + " node_ids contains unknown template role " + role);
            }
        }
    }
    private static void validateComposite(DynamicGeneratorNode generator, String key, Object raw,
                                          List<String> errors) {
        if (!(raw instanceof String compositeId) || compositeId.isBlank()) {
            errors.add("Dynamic generator " + generator.id() + " instance " + key + " composite_id must be nonblank");
        }
    }

    private static void validateOverrides(DynamicGeneratorNode generator, String key, Map<?, ?> overrides,
                                          List<String> errors) {
        for (Map.Entry<?, ?> entry : overrides.entrySet()) {
            String name = entry.getKey() instanceof String field ? field : String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (!OVERRIDE_FIELDS.contains(name)) {
                errors.add("Dynamic generator " + generator.id() + " instance " + key
                        + " has an unknown override field " + name);
                continue;
            }
            if (!validOverride(name, value)) {
                errors.add("Dynamic generator " + generator.id() + " instance " + key
                        + " has an invalid " + name + " override");
                continue;
            }
            if ("applications".equals(generator.registry()) && APPLICATION_LEVEL_OVERRIDES.contains(name))
                errors.add("Dynamic application generator " + generator.id() + " instance " + key
                        + " must keep " + name + " in applications[] rather than an instance override");
        }
    }

    private static boolean validOverride(String name, Object value) {
        return switch (name) {
            case "gain" -> value instanceof Number number && Double.isFinite(number.doubleValue())
                    && number.doubleValue() >= 0.0 && number.doubleValue() <= 1.0;
            case "muted", "pinned", "expanded", "show_children", "show_when_offline" -> value instanceof Boolean;
            case "display_name", "icon", "group_id" -> value instanceof String;
            case "order" -> value instanceof Number number && Double.isFinite(number.doubleValue())
                    && number.doubleValue() == Math.rint(number.doubleValue());
            default -> false;
        };
    }
}
