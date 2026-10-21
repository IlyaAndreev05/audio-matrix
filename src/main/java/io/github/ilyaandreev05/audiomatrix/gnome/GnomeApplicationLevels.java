package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves the level one application shows in the GNOME panel. */
final class GnomeApplicationLevels {
    /** The published level of one application. */
    record Level(double volume, boolean muted) {}

    private GnomeApplicationLevels() {}

    /** A saved rule owns the level of the application it claims; every other application carries the generated level. */
    static Level resolve(Map<String, Object> config, AudioGraph effectiveGraph,
                         Map<DynamicInstanceRegistry.InstanceAddress,
                                 DynamicInstanceRegistry.RuntimeInstance> instances,
                         String applicationId, String process) {
        Map<String, Object> rule = rule(config, applicationId, process);
        if (rule != null) return new Level(level(rule.get("volume")), Boolean.TRUE.equals(rule.get("mute")));
        return generated(effectiveGraph, instances, applicationId, process);
    }

    /** The first saved rule that claims the application owns its level. */
    private static Map<String, Object> rule(Map<String, Object> config, String applicationId, String process) {
        for (Map<String, Object> rule : maps(config.get("applications"))) {
            Map<String, Object> match = object(rule.get("match"));
            String ruleApp = text(match.get("app_id"));
            String ruleProcess = text(match.get("process"));
            if (ruleApp.isBlank() && ruleProcess.isBlank()) continue;
            if (!ruleApp.isBlank() && !ruleApp.equals(applicationId)) continue;
            if (!ruleProcess.isBlank() && !ruleProcess.equals(process)) continue;
            return rule;
        }
        return null;
    }

    /** An application without a saved rule is served by one instance of the applications generator. */
    private static Level generated(AudioGraph effectiveGraph,
                                   Map<DynamicInstanceRegistry.InstanceAddress,
                                           DynamicInstanceRegistry.RuntimeInstance> instances,
                                   String applicationId, String process) {
        if (effectiveGraph == null || instances.isEmpty()) return null;
        Set<String> generators = applicationGenerators(effectiveGraph);
        for (DynamicInstanceRegistry.RuntimeInstance instance : instances.values()) {
            if (!generators.contains(instance.generatorId())) continue;
            Map<String, Object> binding = instance.runtimeBinding();
            String app = text(binding.get("applicationId"));
            String name = text(binding.get("processName"));
            boolean owned = !app.isBlank() ? app.equals(applicationId) : !name.isBlank() && name.equals(process);
            if (!owned) continue;
            GainNode gain = gain(effectiveGraph, instance.nodeIds().get("gain"));
            if (gain != null) return new Level(level(gain.gain()), gain.muted());
        }
        return null;
    }

    private static Set<String> applicationGenerators(AudioGraph graph) {
        Set<String> result = new LinkedHashSet<>();
        for (GraphNode node : graph.nodes())
            if (node instanceof DynamicGeneratorNode generator && "applications".equals(generator.registry()))
                result.add(generator.id());
        return result;
    }

    private static GainNode gain(AudioGraph graph, String gainId) {
        if (gainId == null || gainId.isBlank()) return null;
        for (GraphNode node : graph.nodes())
            if (node instanceof GainNode gain && gain.id().equals(gainId)) return gain;
        return null;
    }

    private static double level(Object value) {
        double gain = value instanceof Number number ? number.doubleValue() : 1.0;
        return Double.isFinite(gain) ? Math.max(0, Math.min(1, gain)) : 1.0;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Object value) {
        return value instanceof String text ? text : "";
    }
}
