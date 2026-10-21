package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creates node copies with new stable IDs while preserving node parameters and ports. */
final class GraphNodeCopies {
    private GraphNodeCopies() {}

    static GraphNode copy(GraphNode node, String id, Map<String, String> nodeIds) {
        List<String> channels = node instanceof DynamicGeneratorNode generator ? generator.channels()
                : node.outputs().isEmpty()
                ? node.inputs().getFirst().channels()
                : node.outputs().getFirst().channels();
        return switch (node.type()) {
            case "source" -> new SourceNode(id, node.name(), ((SourceNode) node).selector(), channels);
            case "dynamic_generator" -> copyDynamicGenerator((DynamicGeneratorNode) node, id, nodeIds);
            case "device_slot" -> copyDeviceSlot(node, id, channels);
            case "output" -> copyOutput(node, id, channels);
            case "gain" -> copyGain(node, id, channels, nodeIds);
            case "gate" -> copyGate(node, id, channels);
            case "router" -> copyRouter(node, id, channels);
            case "switch" -> new SwitchNode(id, node.name(), channels, outputIds(node), links(node));
            case "selector" -> new SelectorNode(id, node.name(), channels, inputIds(node), links(node));
            case "multi_selector" -> new MultiSelectorNode(id, node.name(), channels, inputIds(node), links(node));
            case "matrix" -> new MatrixNode(id, node.name(), channels, inputIds(node), outputIds(node), links(node));
            case "mixer" -> new MixerNode(id, node.name(), channels, ((MixerNode) node).inputGains(),
                    ((MixerNode) node).inputMutes());
            case "filter" -> copyFilter(node, id, channels);
            default -> throw new IllegalArgumentException("Unsupported graph node type: " + node.type());
        };
    }

    private static DynamicGeneratorNode copyDynamicGenerator(DynamicGeneratorNode generator, String id,
                                                              Map<String, String> nodeIds) {
        Map<String, Object> parameters = new LinkedHashMap<>(generator.parameters());
        Object rawConnectTo = parameters.get("connect_to");
        if (rawConnectTo instanceof Map<?, ?> raw) {
            Map<String, Object> connectTo = stringMap(raw);
            Object connectedNode = connectTo.get("node_id");
            if (connectedNode instanceof String nodeId)
                connectTo.put("node_id", nodeIds.getOrDefault(nodeId, nodeId));
            parameters.put("connect_to", connectTo);
        }
        Object rawInstances = parameters.get("instances");
        if (rawInstances instanceof Map<?, ?> raw) {
            Map<String, Object> instances = new LinkedHashMap<>();
            raw.forEach((rawKey, rawRecord) -> {
                String key = String.valueOf(rawKey);
                if (!(rawRecord instanceof Map<?, ?> rawInstance)) {
                    instances.put(key, rawRecord);
                    return;
                }
                Map<String, Object> instance = stringMap(rawInstance);
                if (instance.get("node_ids") instanceof Map<?, ?> rawNodeIds) {
                    Map<String, Object> copiedNodeIds = new LinkedHashMap<>();
                    rawNodeIds.forEach((rawRole, rawNodeId) -> {
                        String role = String.valueOf(rawRole);
                        copiedNodeIds.put(role, rawNodeId instanceof String
                                ? DynamicInstanceRegistry.stableNodeId(id, key, role)
                                : rawNodeId);
                    });
                    instance.put("node_ids", copiedNodeIds);
                }
                if (instance.get("composite_id") instanceof String compositeId && !compositeId.isBlank())
                    instance.put("composite_id", DynamicInstanceRegistry.stableCompositeId(id, key));
                instances.put(key, instance);
            });
            parameters.put("instances", instances);
        }
        return new DynamicGeneratorNode(id, generator.name(), generator.selector(), generator.channels(), parameters);
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static GraphNode copyDeviceSlot(GraphNode node, String id, List<String> channels) {
        DeviceSlotNode slot = (DeviceSlotNode) node;
        return new DeviceSlotNode(id, node.name(), slot.deviceId(), channels, slot.volume(), slot.muted());
    }

    private static GraphNode copyOutput(GraphNode node, String id, List<String> channels) {
        OutputNode output = (OutputNode) node;
        return new OutputNode(id, node.name(), output.deviceId(), channels, output.channelMatrix());
    }

    private static GraphNode copyGain(GraphNode node, String id, List<String> channels,
                                      Map<String, String> nodeIds) {
        GainNode gain = (GainNode) node;
        return new GainNode(id, node.name(), channels, gain.gain(), gain.muted(),
                nodeIds.getOrDefault(gain.endpointNodeId(), gain.endpointNodeId()));
    }

    private static GraphNode copyGate(GraphNode node, String id, List<String> channels) {
        return new GateNode(id, node.name(), channels, ((GateNode) node).open());
    }

    private static GraphNode copyRouter(GraphNode node, String id, List<String> channels) {
        RouterNode router = (RouterNode) node;
        return new RouterNode(id, node.name(), channels, channels, router.activeLinks(), router.multiple());
    }

    private static GraphNode copyFilter(GraphNode node, String id, List<String> channels) {
        FilterNode filter = (FilterNode) node;
        Map<String, Object> parameters = new LinkedHashMap<>(filter.parameters());
        parameters.remove("processor");
        parameters.remove("enabled");
        return new FilterNode(id, node.name(), channels, filter.processor(), filter.enabled(), parameters);
    }

    private static List<String> inputIds(GraphNode node) {
        return node.inputs().stream().map(AudioPort::id).toList();
    }

    private static List<String> outputIds(GraphNode node) {
        return node.outputs().stream().map(AudioPort::id).toList();
    }

    private static List<String> links(GraphNode node) {
        return ((RouterNode) node).activeLinks();
    }
}
