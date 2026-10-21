package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

final class DynamicRegistryReconciler {
    private DynamicRegistryReconciler() {}

    static DynamicInstanceRegistry.ReconcileResult reconcile(AudioGraph configuredGraph,
            Map<String, List<DynamicInstanceRegistry.InstanceSnapshot>> snapshotsByGeneratorId,
            Map<String, String> parentCompositeIds, DynamicInstanceRegistry.ApplicationGainResolver appGainResolver,
            DynamicInstanceRegistry.EndpointGainResolver endpointGainResolver) {
        Objects.requireNonNull(configuredGraph, "configuredGraph");
        Map<String, List<DynamicInstanceRegistry.InstanceSnapshot>> snapshots = snapshotsByGeneratorId == null
                ? Map.of() : snapshotsByGeneratorId;
        List<GraphNode> configuredNodes = new ArrayList<>(configuredGraph.nodes());
        List<GraphNode> effectiveNodes = new ArrayList<>(configuredGraph.nodes());
        List<GraphConnection> effectiveConnections = new ArrayList<>();
        List<GraphSubgraph> generatedSubgraphs = new ArrayList<>();
        Map<DynamicInstanceRegistry.InstanceAddress, DynamicInstanceRegistry.RuntimeInstance> runtimeInstances =
                new LinkedHashMap<>();
        Set<String> generatorIds = configuredGraph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(GraphNode::id).collect(Collectors.toSet());
        effectiveConnections.addAll(configuredGraph.connections().stream().filter(edge ->
                !generatorIds.contains(edge.fromNode()) && !generatorIds.contains(edge.toNode())).toList());
        Map<String, String> parents = parentCompositeIds == null ? Map.of() : parentCompositeIds;
        boolean changed = false;
        for (int generatorIndex = 0; generatorIndex < configuredNodes.size(); generatorIndex++) {
            if (!(configuredNodes.get(generatorIndex) instanceof DynamicGeneratorNode original)) continue;
            List<DynamicInstanceRegistry.InstanceSnapshot> discovered = snapshots.getOrDefault(original.id(), List.of());
            if ("outputs".equals(original.registry()))
                discovered = GraphStaticOutputDevices.withoutRoutedDevices(configuredGraph, discovered);
            Map<String, DynamicInstanceRegistry.InstanceSnapshot> observed =
                    DynamicRegistrySnapshotMatcher.mergeSnapshots(original, discovered);
            Map<String, Object> records = normalizedRecords(original, observed);
            boolean generatorChanged = !DynamicRegistryValues.deepEquals(records, original.instances());
            changed |= generatorChanged;
            DynamicGeneratorNode current = generatorChanged ? original.withInstances(records) : original;
            configuredNodes.set(generatorIndex, current);
            // The effective graph keeps the configured generator: registry state is control-plane data.
            replace(effectiveNodes, original.id(), original);
            List<DynamicRegistryConnections.Target> targets = DynamicRegistryConnections.targets(configuredGraph, original);
            List<String> anchorChannels = DynamicRegistryConnections.anchorChannels(configuredGraph, targets);
            AudioGraph template = templateGraph(original);
            for (Map.Entry<String, Object> recordEntry : records.entrySet()) {
                String instanceKey = recordEntry.getKey();
                Map<String, Object> record = DynamicRegistryValues.object(recordEntry.getValue());
                DynamicInstanceRegistry.InstanceSnapshot observation = observed.get(instanceKey);
                if ("outputs".equals(original.registry()))
                    changed |= DynamicRegistryOutputSwitch.ensureOutputSwitchPort(configuredNodes, effectiveNodes,
                            current, instanceKey, observation);
                Map<String, String> ids = DynamicRegistryValues.nodeIds(record.get("node_ids"));
                Map<String, Object> overrides = DynamicRegistryValues.object(record.get("overrides"));
                boolean available = observation != null && instanceAvailable(observation);
                boolean bindingAvailable = observation != null && bindingAvailable(observation);
                boolean controlAvailable = observation != null && controlAvailable(original, observation);
                DynamicInstanceRegistry.Confidence confidence = observation == null
                        ? DynamicRegistryIdentity.confidenceFromKey(instanceKey) : observation.confidence();
                Map<String, Object> runtimeBinding = observation == null ? Map.of() : observation.runtimeBinding();
                DynamicInstanceRegistry.RuntimeInstance runtime = new DynamicInstanceRegistry.RuntimeInstance(
                        original.id(), instanceKey, ids, DynamicRegistryValues.text(record.get("composite_id")),
                        available, bindingAvailable, controlAvailable, confidence, runtimeBinding, overrides);
                runtimeInstances.put(new DynamicInstanceRegistry.InstanceAddress(original.id(), instanceKey), runtime);
                List<GraphNode> children = DynamicRegistryMaterializer.materialize(original, template, observation,
                        instanceKey, ids, overrides, anchorChannels, appGainResolver, endpointGainResolver);
                effectiveNodes.addAll(children);
                addTemplateConnections(original, template, instanceKey, ids, bindingAvailable, effectiveConnections);
                DynamicRegistryConnections.addBoundaryConnections(original, template, instanceKey, ids, targets,
                        observation, bindingAvailable, effectiveNodes, effectiveConnections);
                generatedSubgraphs.add(new GraphSubgraph(DynamicRegistryValues.text(record.get("composite_id")),
                        instanceName(observation, instanceKey, overrides), children.stream().map(GraphNode::id).toList(),
                        false, DynamicRegistryValues.blankToNull(parents.get(original.id())), original.id()));
            }
        }
        AudioGraph saved = new AudioGraph(configuredGraph.id(), configuredGraph.name(), configuredNodes,
                configuredGraph.connections());
        AudioGraph effective = new AudioGraph(configuredGraph.id(), configuredGraph.name(), effectiveNodes,
                effectiveConnections);
        return new DynamicInstanceRegistry.ReconcileResult(saved, effective, runtimeInstances, generatedSubgraphs, changed);
    }

    private static Map<String, Object> normalizedRecords(DynamicGeneratorNode generator,
            Map<String, DynamicInstanceRegistry.InstanceSnapshot> observed) {
        Map<String, Object> records = DynamicRegistryValues.mutableObject(generator.instances());
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : records.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank())
                normalized.put(entry.getKey(), DynamicRegistryValues.deep(entry.getValue()));
        }
        for (Map.Entry<String, DynamicInstanceRegistry.InstanceSnapshot> entry : observed.entrySet()) {
            Map<String, Object> record = DynamicRegistryValues.object(normalized.get(entry.getKey()));
            Map<String, String> ids = DynamicRegistryValues.nodeIds(record.get("node_ids"));
            for (Map<String, Object> templateNode : generator.templateNodes()) {
                String role = DynamicRegistryValues.text(templateNode.get("id"));
                if (!role.isBlank()) ids.putIfAbsent(role,
                        DynamicRegistryIdentity.stableNodeId(generator.id(), entry.getKey(), role));
            }
            record.put("node_ids", ids);
            record.putIfAbsent("composite_id", DynamicRegistryIdentity.stableCompositeId(generator.id(), entry.getKey()));
            record.putIfAbsent("overrides", new LinkedHashMap<String, Object>());
            normalized.put(entry.getKey(), record);
        }
        if (!generator.retainOnDisappear())
            for (String instanceKey : List.copyOf(normalized.keySet()))
                if (!observed.containsKey(instanceKey)) normalized.remove(instanceKey);
        return normalized;
    }

    private static void addTemplateConnections(DynamicGeneratorNode generator, AudioGraph template, String instanceKey,
            Map<String, String> ids, boolean bindingAvailable, List<GraphConnection> result) {
        for (GraphConnection edge : template.connections()) {
            String from = ids.get(edge.fromNode()), to = ids.get(edge.toNode());
            if (from == null || to == null) continue;
            result.add(new GraphConnection(DynamicRegistryIdentity.stableConnectionId(generator.id(), instanceKey,
                    "template:" + edge.id()), from, edge.fromPort(), to, edge.toPort(),
                    bindingAvailable && edge.enabled(), edge.gain()));
        }
    }

    private static boolean instanceAvailable(DynamicInstanceRegistry.InstanceSnapshot snapshot) {
        return !"applications".equals(snapshot.registry())
                || Boolean.TRUE.equals(snapshot.runtimeBinding().get("hasPcmBinding"));
    }
    private static boolean bindingAvailable(DynamicInstanceRegistry.InstanceSnapshot snapshot) {
        return switch (snapshot.registry()) {
            case "applications" -> Boolean.TRUE.equals(snapshot.runtimeBinding().get("hasPcmBinding"))
                    && snapshot.confidence() == DynamicInstanceRegistry.Confidence.HIGH
                    && (snapshot.selector().startsWith("application:") || snapshot.selector().startsWith("process:")
                    || DynamicRegistrySystemInput.SELECTOR.equals(snapshot.selector()));
            case "outputs" -> snapshot.confidence() == DynamicInstanceRegistry.Confidence.HIGH
                    && !DynamicRegistryValues.text(snapshot.runtimeBinding().get("deviceId")).isBlank()
                    && !DynamicRegistryIdentity.identityFingerprint(snapshot).isBlank()
                    && DynamicRegistryIdentity.identityReliable(snapshot);
            case "browser_tabs" -> !DynamicRegistryValues.text(snapshot.runtimeBinding().get("captureStreamKey")).isBlank()
                    && ("audio-matrix-chrome-" + DynamicRegistryValues.text(snapshot.runtimeBinding().get("captureStreamKey")))
                    .equals(DynamicRegistryValues.text(snapshot.runtimeBinding().get("nodeName")));
            default -> false;
        };
    }
    private static boolean controlAvailable(DynamicGeneratorNode generator,
            DynamicInstanceRegistry.InstanceSnapshot snapshot) {
        if ("browser_tabs".equals(generator.registry()))
            return bindingAvailable(snapshot)
                    && !DynamicRegistryValues.text(snapshot.runtimeBinding().get("applicationRuleId")).isBlank()
                    && "captured".equals(snapshot.runtimeBinding().get("volumeCapability"));
        return bindingAvailable(snapshot);
    }
    private static void replace(List<GraphNode> nodes, String id, GraphNode replacement) {
        for (int i = 0; i < nodes.size(); i++) if (id.equals(nodes.get(i).id())) {
            nodes.set(i, replacement);
            return;
        }
    }
    private static AudioGraph templateGraph(DynamicGeneratorNode generator) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("id", "template-" + generator.id());
        encoded.put("name", generator.name() + " template");
        encoded.put("nodes", generator.templateNodes());
        encoded.put("connections", generator.templateConnections());
        return GraphCodec.fromMap(encoded);
    }
    private static String instanceName(DynamicInstanceRegistry.InstanceSnapshot observation, String key,
                                       Map<String, Object> overrides) {
        Object override = overrides.get("display_name");
        if (overrides.containsKey("display_name") && override instanceof String value && !value.isBlank()) return value;
        return observation == null ? key : DynamicRegistryValues.firstNonBlank(observation.displayName(), key);
    }
}
