package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DynamicGeneratorValidator {
    private static final Set<String> REGISTRIES = Set.of("applications", "outputs", "browser_tabs");

    private DynamicGeneratorValidator() {}

    static void validateGenerators(AudioGraph graph, Map<String, GraphNode> graphNodes,
                                    Map<DynamicInstanceRegistry.InstanceAddress,
                                    DynamicInstanceRegistry.RuntimeInstance> runtimeInstances,
                                    List<String> errors) {
        Set<String> generatedNodeIds = new HashSet<>();
        Set<String> configuredNodeIds = new HashSet<>(graphNodes.keySet());
        configuredNodeIds.removeAll(claimedNodeIds(graph));
        for (GraphNode node : graph.nodes()) if (node instanceof DynamicGeneratorNode generator)
            validateGenerator(generator, graph, graphNodes, configuredNodeIds, generatedNodeIds, runtimeInstances, errors);
    }

    /** Node ids owned by generated instances; they are children, not configured graph nodes. */
    private static Set<String> claimedNodeIds(AudioGraph graph) {
        Set<String> claimed = new HashSet<>();
        for (GraphNode node : graph.nodes()) if (node instanceof DynamicGeneratorNode generator)
            for (Object rawInstance : generator.instances().values()) {
                if (!(rawInstance instanceof Map<?, ?> instance)) continue;
                if (!(instance.get("node_ids") instanceof Map<?, ?> nodeIds)) continue;
                for (Object rawId : nodeIds.values())
                    if (rawId instanceof String id && !id.isBlank()) claimed.add(id);
            }
        return claimed;
    }

    private static void validateGenerator(DynamicGeneratorNode generator, AudioGraph graph,
                                          Map<String, GraphNode> graphNodes, Set<String> configuredNodeIds,
                                          Set<String> generatedNodeIds,
                                          Map<DynamicInstanceRegistry.InstanceAddress,
                                          DynamicInstanceRegistry.RuntimeInstance> runtimeInstances,
                                          List<String> errors) {
        Map<String, Object> parameters = generator.parameters();
        Object rawRegistry = parameters.get("registry"), rawTemplate = parameters.get("template");
        String registry = rawRegistry instanceof String value ? value : "";
        String templateName = rawTemplate instanceof String value ? value : "";
        if (!REGISTRIES.contains(registry))
            errors.add("Dynamic generator " + generator.id() + " has an unknown registry: " + rawRegistry);
        if (!DynamicGeneratorNode.compatibleTemplate(registry, templateName))
            errors.add("Dynamic generator " + generator.id() + " has an incompatible template " + templateName
                    + " for registry " + registry);
        if (REGISTRIES.contains(registry) && !OutputSelectorValidator.supports(registry, generator.selector()))
            errors.add("Dynamic generator " + generator.id() + " has an unsupported selector " + generator.selector());
        Object initialGain = parameters.get("initial_gain");
        if (!(initialGain instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < 0.0 || number.doubleValue() > 1.0)
            errors.add("Dynamic generator " + generator.id() + " initial_gain must be between 0 and 1");
        requireBoolean(parameters, "auto_pin", generator.id(), errors);
        requireBoolean(parameters, "retain_on_disappear", generator.id(), errors);
        requireBoolean(parameters, "restore_on_reappear", generator.id(), errors);
        if (parameters.containsKey("auto_select_trusted_hdmi_on_first_discovery")) {
            requireBoolean(parameters, "auto_select_trusted_hdmi_on_first_discovery", generator.id(), errors);
            if (!"outputs".equals(registry))
                errors.add("Dynamic generator " + generator.id()
                        + " auto_select_trusted_hdmi_on_first_discovery is only valid for outputs");
        }
        if (HapticOutputAnchorValidator.isHapticOutput(generator, registry)
                && Boolean.TRUE.equals(parameters.get("auto_select_trusted_hdmi_on_first_discovery")))
            errors.add("Haptic-role output generator " + generator.id()
                    + " must disable auto_select_trusted_hdmi_on_first_discovery");
        if (!(parameters.get("group_id") instanceof String))
            errors.add("Dynamic generator " + generator.id() + " group_id must be a string");
        if (!(parameters.get("excluded_selectors") instanceof List<?> selectors)
                || selectors.stream().anyMatch(value -> !(value instanceof String selector) || selector.isBlank()))
            errors.add("Dynamic generator " + generator.id() + " excluded_selectors must contain nonblank strings");

        DynamicGeneratorTemplateValidator.Result template =
                DynamicGeneratorTemplateValidator.validate(generator, registry, errors);
        DynamicGeneratorConnectionValidator.validate(generator, graph, graphNodes, template.boundary(),
                template.graph(), registry, runtimeInstances, errors);
        DynamicGeneratorInstanceValidator.validate(generator, template.graph(), configuredNodeIds,
                generatedNodeIds, errors);
    }

    private static void requireBoolean(Map<String, Object> parameters, String name, String generatorId,
                                       List<String> errors) {
        if (!(parameters.get(name) instanceof Boolean))
            errors.add("Dynamic generator " + generatorId + " " + name + " must be boolean");
    }
}
