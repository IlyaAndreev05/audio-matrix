package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GraphPipeWireCaptureSupport {
    private GraphPipeWireCaptureSupport() {}

    static CaptureSources captureSources(DynamicInstanceRegistry.ReconcileResult reconciled, AudioGraph graph) {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        graph.nodes().forEach(node -> nodes.put(node.id(), node));
        Map<String, DynamicGeneratorNode> generators = new LinkedHashMap<>();
        reconciled.configuredGraph().nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).forEach(generator -> generators.put(generator.id(), generator));
        Set<String> generatedSourceIds = generatedSourceIds(reconciled, graph);
        Set<String> pcmSourceIds = new HashSet<>();
        Set<String> gainNodeIds = new HashSet<>();
        Map<String, Map<String, Object>> bySourceId = new LinkedHashMap<>();
        List<Map<String, Object>> metadata = new ArrayList<>();
        List<DynamicInstanceRegistry.RuntimeInstance> runtimeInstances =
                new ArrayList<>(reconciled.instances().values());
        runtimeInstances.sort(Comparator.comparing(DynamicInstanceRegistry.RuntimeInstance::generatorId)
                .thenComparing(DynamicInstanceRegistry.RuntimeInstance::instanceKey));
        for (DynamicInstanceRegistry.RuntimeInstance runtime : runtimeInstances) {
            Map<String, Object> binding = runtime.runtimeBinding();
            DynamicGeneratorNode generator = generators.get(runtime.generatorId());
            // Application levels come from the applications[] rule, endpoint levels from the device binding.
            if (generator != null && ("applications".equals(generator.registry()) || "outputs".equals(generator.registry())))
                for (String childId : runtime.nodeIds().values())
                    if (nodes.get(childId) instanceof GainNode) gainNodeIds.add(childId);
            boolean logicalBrowserParent = Boolean.TRUE.equals(binding.get("browserParent"))
                    && !Boolean.TRUE.equals(binding.get("hasPcmBinding"));
            if (runtime.available() && runtime.confidence() == DynamicInstanceRegistry.Confidence.HIGH
                    && Boolean.TRUE.equals(binding.get("hasPcmBinding")) && !logicalBrowserParent)
                for (String childId : runtime.nodeIds().values())
                    if (nodes.get(childId) instanceof SourceNode) pcmSourceIds.add(childId);
            if (!runtime.available() || runtime.confidence() != DynamicInstanceRegistry.Confidence.HIGH
                    || logicalBrowserParent) continue;
            String captureKey = bindingText(binding, "captureStreamKey");
            String nodeName = bindingText(binding, "nodeName");
            String applicationRuleId = bindingText(binding, "applicationRuleId");
            if (captureKey.isBlank() || !nodeName.equals("audio-matrix-chrome-" + captureKey)
                    || applicationRuleId.isBlank()) continue;
            SourceNode source = null;
            GainNode gain = null;
            for (String childId : runtime.nodeIds().values()) {
                GraphNode child = nodes.get(childId);
                if (child instanceof SourceNode candidate && candidate.selector().equals("capture:" + captureKey))
                    source = candidate;
                if (child instanceof GainNode candidate) gain = candidate;
            }
            if (source == null || gain == null) continue;
            Map<String, Object> item = GraphPipeWireCompiler.map(
                    "source_id", source.id(), "instance_key", runtime.instanceKey(),
                    "capture_stream_key", captureKey, "node_name", nodeName, "application_rule_id", applicationRuleId,
                    "parent_application_id", bindingText(binding, "parentApplicationId"),
                    "parent_process_name", bindingText(binding, "parentProcessName"),
                    "tab_gain", gain.gain(), "tab_muted", gain.muted());
            bySourceId.put(source.id(), item);
            gainNodeIds.add(gain.id());
            pcmSourceIds.add(source.id());
            metadata.add(item);
        }
        Set<String> nonPcmSourceIds = new HashSet<>(generatedSourceIds);
        nonPcmSourceIds.removeAll(pcmSourceIds);
        return new CaptureSources(Map.copyOf(bySourceId), Set.copyOf(gainNodeIds),
                Set.copyOf(nonPcmSourceIds), List.copyOf(metadata));
    }

    /** Source children materialized for the reconciled instances of the effective graph. */
    static Set<String> generatedSourceIds(DynamicInstanceRegistry.ReconcileResult reconciled, AudioGraph graph) {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        graph.nodes().forEach(node -> nodes.put(node.id(), node));
        Set<String> generated = new HashSet<>();
        for (DynamicInstanceRegistry.RuntimeInstance runtime : reconciled.instances().values())
            for (String childId : runtime.nodeIds().values())
                if (nodes.get(childId) instanceof SourceNode) generated.add(childId);
        return generated;
    }

    static Set<String> generatedSourceIds(AudioGraph graph) {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        graph.nodes().forEach(node -> nodes.put(node.id(), node));
        Set<String> generated = new HashSet<>();
        for (GraphNode node : graph.nodes()) if (node instanceof DynamicGeneratorNode generator) {
            for (Object rawInstance : generator.instances().values()) {
                if (!(rawInstance instanceof Map<?, ?> instance)
                        || !(instance.get("node_ids") instanceof Map<?, ?> ids)) continue;
                for (Object rawId : ids.values())
                    if (rawId instanceof String id && nodes.get(id) instanceof SourceNode) generated.add(id);
            }
        }
        return generated;
    }

    private static String bindingText(Map<String, Object> binding, String key) {
        Object value = binding.get(key);
        return value instanceof String text ? text : "";
    }

    record CaptureSources(Map<String, Map<String, Object>> bySourceId, Set<String> gainNodeIds,
                          Set<String> nonPcmSourceIds, List<Map<String, Object>> metadata) {}
}
