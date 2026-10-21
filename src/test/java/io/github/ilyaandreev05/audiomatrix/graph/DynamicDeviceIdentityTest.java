package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DynamicDeviceIdentityTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test void deviceIdentityNotTransientPipeWireNodeIdControlsSavedOutputNodesAndLinks() {
        DeviceIdentity identity = DeviceIdentity.from(Map.of("device.bus-path", "pci-physical-audio",
                "device.serial", "serial-physical-audio", "device.profile.name", "analog-stereo",
                "device.port.name", "analog-output-headphones", "audio.position", "FL,FR"), 2);
        AudioNode firstDiscovery = output(21, "alsa_output.physical.headphones", identity);
        AudioNode afterReconnect = output(84, "alsa_output.physical.headphones", identity);
        assertEquals(DynamicInstanceRegistry.output(firstDiscovery).stableKey(),
                DynamicInstanceRegistry.output(afterReconnect).stableKey());

        DynamicGeneratorNode outputs = new DynamicGeneratorNode("outputs", "Outputs", "device:*", STEREO,
                Map.of("registry", "outputs", "connect_to", Map.of("node_id", "multi", "port_id", "in")));
        SwitchNode multi = new SwitchNode("multi", "MultiSwitch", STEREO, List.of("existing"), List.of());
        AudioGraph graph = new AudioGraph("route-device", "Device identity", List.of(outputs, multi), List.of());
        DynamicInstanceRegistry.ReconcileResult first = DynamicInstanceRegistry.reconcile(graph,
                Map.of("outputs", List.of(DynamicInstanceRegistry.output(firstDiscovery))));
        String key = DynamicInstanceRegistry.output(firstDiscovery).stableKey();
        DynamicInstanceRegistry.RuntimeInstance firstState = first.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("outputs", key));
        DynamicInstanceRegistry.ReconcileResult missing = DynamicInstanceRegistry.reconcile(first.configuredGraph(), Map.of());
        DynamicInstanceRegistry.RuntimeInstance offline = missing.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("outputs", key));
        DynamicInstanceRegistry.ReconcileResult returned = DynamicInstanceRegistry.reconcile(missing.configuredGraph(),
                Map.of("outputs", List.of(DynamicInstanceRegistry.output(afterReconnect))));
        DynamicInstanceRegistry.RuntimeInstance returnedState = returned.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("outputs", key));
        SwitchNode returnedSwitch = returned.configuredGraph().nodes().stream().filter(SwitchNode.class::isInstance)
                .map(SwitchNode.class::cast).findFirst().orElseThrow();

        assertEquals(firstState.nodeIds(), offline.nodeIds());
        assertFalse(offline.available());
        assertEquals(firstState.nodeIds(), returnedState.nodeIds());
        assertEquals(84, returnedState.runtimeBinding().get("nodeId"));
        assertTrue(returnedSwitch.outputs().stream().anyMatch(port -> port.id().equals(
                DynamicInstanceRegistry.stableOutputPortId("outputs", identity.fingerprint()))));
        assertTrue(returnedSwitch.activeLinks().isEmpty());
    }

    private static AudioNode output(int id, String name, DeviceIdentity identity) {
        return new AudioNode(id, name, "Headphones", "Audio/Sink", "running", "", "", "", 2,
                List.of(48_000), 0.5, false, null, identity);
    }
}
