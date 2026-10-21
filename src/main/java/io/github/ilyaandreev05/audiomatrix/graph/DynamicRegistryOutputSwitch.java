package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DynamicRegistryOutputSwitch {
    private static final String TRUSTED_HDMI = "alsa_output.pci-0000_09_00.1.hdmi-stereo";
    private DynamicRegistryOutputSwitch() {}

    static boolean ensureOutputSwitchPort(List<GraphNode> configuredNodes, List<GraphNode> effectiveNodes,
            DynamicGeneratorNode generator, String instanceKey, DynamicInstanceRegistry.InstanceSnapshot observation) {
        for (GraphNode node : configuredNodes)
            if (generator.id().equals(node.id()) && node instanceof DynamicGeneratorNode updated) {
                generator = updated;
                break;
            }
        Map<String, Object> connectTo = generator.connectTo();
        String switchId = DynamicRegistryValues.text(connectTo.get("node_id"));
        String inputPortId = DynamicRegistryValues.text(connectTo.get("port_id"));
        if (switchId.isBlank() || inputPortId.isBlank()) return false;
        int switchIndex = indexOf(configuredNodes, switchId);
        if (switchIndex < 0 || !(configuredNodes.get(switchIndex) instanceof SwitchNode switchNode)) return false;
        if (switchNode.inputs().stream().noneMatch(port -> port.id().equals(inputPortId)))
            throw new IllegalArgumentException("Output generator connect_to must name a SwitchNode input port");
        String fingerprint = DynamicRegistryIdentity.outputFingerprint(observation, instanceKey);
        if (fingerprint.isBlank()) return false;
        String portId = DynamicRegistryIdentity.stableOutputPortId(generator.id(), fingerprint);
        List<String> outputPorts = new ArrayList<>(switchNode.outputs().stream().map(AudioPort::id).toList());
        boolean newOutputPort = !outputPorts.contains(portId);
        if (newOutputPort) outputPorts.add(portId);
        List<String> activeLinks = new ArrayList<>(switchNode.activeLinks());
        String activeLink = inputPortId + "->" + portId;
        boolean selectedOutput = selectedReliableOutput(generator, observation);
        boolean consumePolicy = newOutputPort && autoSelectEligible(generator, observation);
        boolean selectedLinkChanged = (selectedOutput || consumePolicy) && !activeLinks.contains(activeLink);
        if (selectedLinkChanged) activeLinks.add(activeLink);
        if (consumePolicy) {
            Map<String, Object> parameters = new LinkedHashMap<>(generator.parameters());
            parameters.put("auto_select_trusted_hdmi_on_first_discovery", false);
            DynamicGeneratorNode updatedGenerator = new DynamicGeneratorNode(generator.id(), generator.name(),
                    generator.selector(), generator.channels(), parameters);
            replace(configuredNodes, generator.id(), updatedGenerator);
        }
        boolean changed = newOutputPort || selectedLinkChanged || consumePolicy;
        if (!changed) return false;
        SwitchNode updatedSwitch = new SwitchNode(switchNode.id(), switchNode.name(),
                switchNode.inputs().getFirst().channels(), outputPorts, activeLinks);
        replaceAt(configuredNodes, switchIndex, updatedSwitch);
        replace(effectiveNodes, switchId, updatedSwitch);
        return true;
    }

    private static boolean selectedReliableOutput(DynamicGeneratorNode generator,
            DynamicInstanceRegistry.InstanceSnapshot observation) {
        return trustedOutput(generator, observation)
                && Boolean.TRUE.equals(observation.runtimeBinding().get("selected"));
    }
    private static boolean autoSelectEligible(DynamicGeneratorNode generator,
            DynamicInstanceRegistry.InstanceSnapshot observation) {
        return Boolean.TRUE.equals(generator.parameters().get("auto_select_trusted_hdmi_on_first_discovery"))
                && trustedOutput(generator, observation)
                && TRUSTED_HDMI.equals(DynamicRegistryValues.text(observation.runtimeBinding().get("deviceId")));
    }
    private static boolean trustedOutput(DynamicGeneratorNode generator,
            DynamicInstanceRegistry.InstanceSnapshot observation) {
        return observation != null && observation.confidence() == DynamicInstanceRegistry.Confidence.HIGH
                && DynamicRegistryIdentity.identityReliable(observation)
                && !"haptic_output".equals(DynamicRegistryValues.text(observation.runtimeBinding().get("role")))
                && !"device:role:haptic_output".equals(generator.selector());
    }
    private static int indexOf(List<GraphNode> nodes, String id) {
        for (int i = 0; i < nodes.size(); i++) if (id.equals(nodes.get(i).id())) return i;
        return -1;
    }
    private static void replace(List<GraphNode> nodes, String id, GraphNode replacement) {
        for (int i = 0; i < nodes.size(); i++) if (id.equals(nodes.get(i).id())) {
            nodes.set(i, replacement);
            return;
        }
    }
    private static void replaceAt(List<GraphNode> nodes, int index, GraphNode replacement) {
        if (index >= 0) nodes.set(index, replacement);
    }
}
