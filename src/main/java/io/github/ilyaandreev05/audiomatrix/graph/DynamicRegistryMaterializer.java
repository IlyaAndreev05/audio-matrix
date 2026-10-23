package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class DynamicRegistryMaterializer {
    private DynamicRegistryMaterializer() {}

    static List<GraphNode> materialize(DynamicGeneratorNode generator, AudioGraph template,
            DynamicInstanceRegistry.InstanceSnapshot observation, String instanceKey, Map<String, String> ids,
            Map<String, Object> overrides, List<String> anchorChannels,
            DynamicInstanceRegistry.ApplicationGainResolver resolver,
            DynamicInstanceRegistry.EndpointGainResolver endpointGainResolver) {
        List<GraphNode> result = new ArrayList<>();
        String display = instanceName(observation, instanceKey, overrides);
        String selector = observation == null ? offlineSelector(generator.registry(), instanceKey) : observation.selector();
        for (GraphNode node : template.nodes()) {
            String role = node.id();
            String id = ids.getOrDefault(role, DynamicRegistryIdentity.stableNodeId(generator.id(), instanceKey, role));
            // The channel layout is a graph property: the anchor this instance feeds decides it.
            // A live stream may carry fewer channels (a mono headset, a captured tab); the runtime
            // maps its ports onto the bus, so adopting the observed layout here would only break
            // the generated boundary connection and invalidate the whole effective graph.
            List<String> nodeChannels = anchorChannels.isEmpty()
                    ? DynamicRegistryValues.channels(node) : anchorChannels;
            String name = childName(display, node.name(), role);
            if (node instanceof SourceNode source) {
                String childSelector = selector.isBlank() ? source.selector() : selector;
                result.add(new SourceNode(id, name, childSelector, nodeChannels));
            } else if (node instanceof GainNode gain) {
                DynamicInstanceRegistry.GainState level = gainState(generator, gain, observation, instanceKey, overrides,
                        selector, nodeChannels, resolver, endpointGainResolver);
                result.add(new GainNode(id, name, nodeChannels, level.gain(), level.muted(),
                        endpointNodeId(generator, gain, instanceKey, ids)));
            } else if (node instanceof OutputNode output) {
                String deviceId = DynamicRegistryValues.text(observation == null ? null
                        : observation.runtimeBinding().get("deviceId"));
                if (deviceId.isBlank()) deviceId = output.deviceId();
                if (deviceId.isBlank() || "device:unbound".equals(deviceId))
                    deviceId = DynamicRegistryIdentity.fallbackDeviceId(instanceKey);
                result.add(new OutputNode(id, name, deviceId, nodeChannels, output.channelMatrix()));
            } else {
                result.add(GraphNodeCopies.copy(node, id, ids));
            }
        }
        return result;
    }

    /** The generated output gain is the endpoint gain of its generated output node. */
    private static String endpointNodeId(DynamicGeneratorNode generator, GainNode gain, String instanceKey,
                                         Map<String, String> ids) {
        if (!gain.endpointNodeId().isBlank()) return gain.endpointNodeId();
        if (!"outputs".equals(generator.registry())) return "";
        String outputId = ids.get("output");
        return outputId == null || outputId.isBlank()
                ? DynamicInstanceRegistry.stableNodeId(generator.id(), instanceKey, "output") : outputId;
    }

    private static DynamicInstanceRegistry.GainState gainState(DynamicGeneratorNode generator, GainNode gain,
            DynamicInstanceRegistry.InstanceSnapshot observation, String instanceKey, Map<String, Object> overrides, String selector,
            List<String> channels, DynamicInstanceRegistry.ApplicationGainResolver resolver,
            DynamicInstanceRegistry.EndpointGainResolver endpointGainResolver) {
        double value = gain.gain();
        boolean muted = gain.muted();
        if ("applications".equals(generator.registry())) {
            value = generator.initialGain();
            DynamicInstanceRegistry.InstanceSnapshot resolverInput = observation == null
                    ? offlineApplicationSnapshot(instanceKey, selector, channels) : observation;
            DynamicInstanceRegistry.GainState resolved = resolver == null ? null : resolver.resolve(selector, resolverInput);
            if (resolved != null) {
                value = resolved.gain();
                muted = resolved.muted();
            }
        } else if ("outputs".equals(generator.registry())) {
            DynamicInstanceRegistry.GainState saved = endpointGainResolver == null ? null
                    : endpointGainResolver.resolve(instanceKey, observation);
            if (saved != null) {
                value = saved.gain();
                muted = saved.muted();
            } else if (observation != null) {
                Map<String, Object> binding = observation.runtimeBinding();
                value = DynamicRegistryValues.number(binding.get("savedEndpointGain"),
                        DynamicRegistryValues.number(binding.get("volume"), generator.initialGain()));
                Object savedMute = binding.containsKey("savedEndpointMuted")
                        ? binding.get("savedEndpointMuted") : binding.get("muted");
                muted = Boolean.TRUE.equals(savedMute);
            } else {
                value = generator.initialGain();
            }
        }
        if ("browser_tabs".equals(generator.registry())) {
            value = DynamicRegistryValues.number(overrides.get("gain"), generator.initialGain());
            if (overrides.get("muted") instanceof Boolean savedMute) muted = savedMute;
        }
        return new DynamicInstanceRegistry.GainState(value, muted);
    }

    private static DynamicInstanceRegistry.InstanceSnapshot offlineApplicationSnapshot(String instanceKey,
            String selector, List<String> channels) {
        java.util.Map<String, Object> binding = new java.util.LinkedHashMap<>();
        binding.put("applicationId", instanceKey.startsWith("app:") ? instanceKey.substring("app:".length()) : "");
        binding.put("processName", instanceKey.startsWith("process:")
                ? instanceKey.substring("process:".length()) : "");
        binding.put("audioMatrixOwned", false);
        return new DynamicInstanceRegistry.InstanceSnapshot("applications", instanceKey, instanceKey, selector,
                channels, binding, DynamicRegistryIdentity.confidenceFromKey(instanceKey));
    }
    private static String offlineSelector(String registry, String instanceKey) {
        if ("applications".equals(registry)) {
            if (instanceKey.startsWith("app:")) return "application:" + instanceKey.substring("app:".length());
            if (instanceKey.startsWith("process:")) return "process:" + instanceKey.substring("process:".length());
            return "metadata:" + instanceKey.substring("stream:".length());
        }
        if ("browser_tabs".equals(registry))
            return "capture:unavailable-" + DynamicRegistryValues.sha256(instanceKey);
        return "";
    }
    private static String instanceName(DynamicInstanceRegistry.InstanceSnapshot observation, String instanceKey,
                                       Map<String, Object> overrides) {
        Object override = overrides.get("display_name");
        if (overrides.containsKey("display_name") && override instanceof String value && !value.isBlank()) return value;
        return observation == null ? instanceKey
                : DynamicRegistryValues.firstNonBlank(observation.displayName(), instanceKey);
    }
    private static String childName(String display, String templateName, String role) {
        if ("source".equals(role) || "output".equals(role)) return display;
        if ("gain".equals(role)) return display + " · громкость";
        return display + " · " + DynamicRegistryValues.firstNonBlank(templateName, role);
    }
}
