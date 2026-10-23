package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Resolves stable node targets to the saved property and live runtime binding they control. */
final class GnomeGraphNodeTargetResolver {
    private GnomeGraphNodeTargetResolver() {}

    enum Kind { GRAPH_GAIN, SAVED_DEVICE, GENERATED_APPLICATION, GENERATED_DEVICE, GENERATED_TAB }

    record ResolvedTarget(Kind kind, String routeId, String nodeId, String instanceKey,
                          GraphNode node, DynamicInstanceRegistry.RuntimeInstance instance,
                          GainNode gain, Map<String, Object> runtimeBinding, String bindingId,
                          String applicationSelector) {}

    static Object propertyValue(Map<String, Object> config, GnomeNodeCommandHandler.PropertyKey key,
            DynamicInstanceRegistry.ReconcileResult registry, PipeWireBackend.Snapshot snapshot,
            List<AudioNode> sinks) {
        ResolvedTarget target = resolve(config, target(key), key.property(), registry, snapshot, sinks);
        String property = key.property();
        Object value;
        String identity;
        switch (target.kind()) {
            case GRAPH_GAIN -> {
                value = GraphNodeCommand.propertyValue(config, key.routeId(), key.nodeId(), property);
                identity = "graph:" + key.routeId() + ":" + key.nodeId();
            }
            case SAVED_DEVICE -> {
                value = GraphNodeCommand.propertyValue(config, key.routeId(), key.nodeId(), property);
                identity = "device:" + target.bindingId();
            }
            case GENERATED_APPLICATION -> {
                Map<String, Object> rule = GnomeApplicationRuleResolver.find(config,
                        target.applicationSelector(), target.runtimeBinding());
                value = rule == null
                        ? ("volume".equals(property) ? 1.0 : false)
                        : rule.getOrDefault("volume".equals(property) ? "volume" : "mute",
                                "volume".equals(property) ? 1.0 : false);
                identity = "application:" + key.routeId() + ":" + key.nodeId() + ":" + key.instanceKey();
            }
            case GENERATED_DEVICE -> {
                Map<String, Object> binding = GraphNodeCommand.deviceBinding(config, target.bindingId());
                value = binding.getOrDefault("volume".equals(property) ? "volume" : "mute",
                        "volume".equals(property) ? 1.0 : false);
                identity = "device:" + target.bindingId();
            }
            case GENERATED_TAB -> {
                String field = "volume".equals(property) ? "gain" : "muted";
                Optional<Object> override = DynamicInstanceRegistry.instanceOverride(config,
                        key.routeId(), key.nodeId(), key.instanceKey(), field);
                value = override.orElseGet(() -> "volume".equals(property)
                        ? DynamicInstanceRegistry.instanceGainBaseline(config, key.routeId(), key.nodeId()).gain()
                        : DynamicInstanceRegistry.instanceMutedBaseline(config, key.routeId(), key.nodeId()));
                identity = "tab:" + key.routeId() + ":" + key.nodeId() + ":" + key.instanceKey()
                        + ":" + target.gain().id();
            }
            default -> throw new IllegalStateException("Unsupported node target");
        }
        return new GnomeGraphNodeCommandExecutor.PropertyValue(identity, value);
    }

    private static GnomeNodeCommandHandler.Target target(GnomeNodeCommandHandler.PropertyKey key) {
        return new GnomeNodeCommandHandler.Target(key.routeId(), key.nodeId(), key.instanceKey());
    }

    static ResolvedTarget resolve(Map<String, Object> config, GnomeNodeCommandHandler.Target target,
            String property, DynamicInstanceRegistry.ReconcileResult registry, PipeWireBackend.Snapshot snapshot,
            List<AudioNode> sinks) {
        AudioGraph graph = GraphCodec.fromMap(GraphUiRoutes.route(config, target.routeId()));
        if (target.instanceKey().isEmpty()) {
            GraphNode node = graph.nodes().stream().filter(item -> item.id().equals(target.nodeId())
                            && (item instanceof GainNode || item instanceof DeviceSlotNode
                                || !GraphNodeCommand.savedDeviceId(item).isBlank()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Volume node no longer exists"));
            if (node instanceof GainNode)
                return new ResolvedTarget(Kind.GRAPH_GAIN, target.routeId(), target.nodeId(), "",
                        node, null, (GainNode) node, Map.of(), "", "");
            String bindingId = GnomeGraphNodeDeviceResolver.deviceId(GraphNodeCommand.savedDeviceId(node));
            GraphNodeCommand.deviceBinding(config, bindingId);
            return new ResolvedTarget(Kind.SAVED_DEVICE, target.routeId(), target.nodeId(), "",
                    node, null, null, Map.of(), bindingId, "");
        }

        if (!(graph.nodes().stream().filter(node -> node.id().equals(target.nodeId())).findFirst()
                .orElse(null) instanceof DynamicGeneratorNode))
            throw new IllegalArgumentException("Generated node no longer exists");
        if (registry == null || !graph.id().equals(registry.configuredGraph().id()))
            throw new IllegalArgumentException("Generated target registry is unavailable or stale");
        DynamicGeneratorNode configuredGenerator = graph.nodes().stream()
                .filter(DynamicGeneratorNode.class::isInstance).map(DynamicGeneratorNode.class::cast)
                .filter(node -> node.id().equals(target.nodeId())).findFirst().orElseThrow();
        DynamicGeneratorNode currentGenerator = DynamicInstanceRegistry.generator(registry, target.nodeId());
        if (!sameGeneratorDefinition(configuredGenerator, currentGenerator))
            throw new IllegalArgumentException("Generated target registry is stale");
        DynamicInstanceRegistry.RuntimeInstance instance = DynamicInstanceRegistry.requireInstance(
                registry, target.nodeId(), target.instanceKey(), true);
        if (!instance.bindingAvailable())
            throw new IllegalArgumentException("Generated target has no live runtime binding");
        if ("volume".equals(property) && !instance.gainControlAvailable()) {
            if ("browser_tabs".equals(configuredGenerator.registry())
                    && text(instance.runtimeBinding().get("applicationRuleId")).isBlank())
                throw new IllegalArgumentException("Generated tab parent application rule is unavailable");
            throw new IllegalArgumentException("Generated target gain control is unavailable");
        }
        if ("muted".equals(property) && !instance.muteControlAvailable())
            throw new IllegalArgumentException("Generated target mute control is unavailable");
        GainNode gain = DynamicInstanceRegistry.generatedGain(registry, target.nodeId(), target.instanceKey());
        Map<String, Object> binding = instance.runtimeBinding();
        if (binding.containsKey("streams")) {
            verifyApplicationStreams(binding, snapshot);
            String sourceId = DynamicInstanceRegistry.generatedNodeId(
                    registry, target.nodeId(), target.instanceKey(), "source");
            SourceNode source = registry.effectiveGraph().nodes().stream()
                    .filter(node -> node.id().equals(sourceId) && node instanceof SourceNode)
                    .map(SourceNode.class::cast).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Generated application source is unavailable"));
            GnomeApplicationRuleResolver.find(config, source.selector(), binding);
            return new ResolvedTarget(Kind.GENERATED_APPLICATION, target.routeId(), target.nodeId(),
                    target.instanceKey(), null, instance, gain, binding, "", source.selector());
        }
        if (binding.containsKey("deviceId")) {
            String bindingId = GnomeGraphNodeDeviceResolver.deviceId(String.valueOf(binding.get("deviceId")));
            GraphNodeCommand.deviceBinding(config, bindingId);
            return new ResolvedTarget(Kind.GENERATED_DEVICE, target.routeId(), target.nodeId(),
                    target.instanceKey(), null, instance, gain, binding, bindingId, "");
        }
        if (binding.containsKey("browser") || binding.containsKey("tabId")
                || binding.containsKey("sessionToken")) {
            verifyCapturedTab(binding, snapshot);
            verifyTabParentRule(config, binding);
            return new ResolvedTarget(Kind.GENERATED_TAB, target.routeId(),
                    target.nodeId(), target.instanceKey(), null, instance, gain, binding, "", "");
        }
        throw new IllegalArgumentException("Generated instance has no supported runtime binding");
    }

    static boolean sameGeneratorDefinition(DynamicGeneratorNode saved, DynamicGeneratorNode runtime) {
        return saved.registry().equals(runtime.registry())
                && saved.selector().equals(runtime.selector())
                && saved.template().equals(runtime.template())
                && saved.channels().equals(runtime.channels())
                && saved.templateNodes().equals(runtime.templateNodes())
                && saved.templateConnections().equals(runtime.templateConnections())
                && saved.connectTo().equals(runtime.connectTo());
    }

    private static void verifyCapturedTab(Map<String, Object> binding, PipeWireBackend.Snapshot snapshot) {
        String captureKey = text(binding.get("captureStreamKey"));
        String nodeName = text(binding.get("nodeName"));
        String expectedNodeName = "audio-matrix-chrome-" + captureKey;
        if (snapshot == null || !snapshot.connected() || captureKey.isBlank()
                || !expectedNodeName.equals(nodeName)
                || snapshot.streams().stream().noneMatch(stream -> expectedNodeName.equals(stream.name())))
            throw new IllegalArgumentException("Generated tab capture stream is unavailable");
    }

    private static void verifyTabParentRule(Map<String, Object> config, Map<String, Object> binding) {
        String applicationId = text(binding.get("parentApplicationId"));
        String processName = text(binding.get("parentProcessName"));
        String expectedRuleId = text(binding.get("applicationRuleId"));
        if (expectedRuleId.isBlank())
            throw new IllegalArgumentException("Generated tab parent application rule is unavailable");
        if (applicationId.isBlank() && processName.isBlank())
            throw new IllegalArgumentException("Generated tab parent application identity is unavailable");
        String selector = applicationId.isBlank() ? "process:" + processName : "application:" + applicationId;
        Map<String, Object> parentBinding = Map.of("applicationId", applicationId, "processName", processName);
        Map<String, Object> rule = GnomeApplicationRuleResolver.find(config, selector, parentBinding);
        if (rule == null || !expectedRuleId.equals(text(rule.get("id"))))
            throw new IllegalArgumentException("Generated tab parent application rule is stale");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static void verifyApplicationStreams(Map<String, Object> binding,
            PipeWireBackend.Snapshot snapshot) {
        if (snapshot == null || !snapshot.connected())
            throw new IllegalArgumentException("Generated application stream is unavailable");
        Object raw = binding.get("streams");
        if (!(raw instanceof List<?> streams) || streams.isEmpty())
            throw new IllegalArgumentException("Generated application has no live stream binding");
        String appId = text(binding.get("applicationId"));
        String process = text(binding.get("processName"));
        for (Object stream : streams) {
            if (!(stream instanceof Map<?, ?> item) || !(item.get("nodeId") instanceof Number id))
                throw new IllegalArgumentException("Generated application stream binding is stale");
            AudioNode live = snapshot.streams().stream()
                    .filter(candidate -> candidate.id() == id.intValue()).findFirst().orElse(null);
            if (live == null || (!appId.isBlank() ? !appId.equals(live.applicationId())
                    : process.isBlank() || !process.equals(live.processName())))
                throw new IllegalArgumentException("Generated application stream binding is stale");
        }
    }


}
