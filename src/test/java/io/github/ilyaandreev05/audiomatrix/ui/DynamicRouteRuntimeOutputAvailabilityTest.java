package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.audio.PortAvailability;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicRouteRuntimeOutputAvailabilityTest {
    @TempDir Path temp;

    @Test
    void generatedOutputRegistryOmitsOnlyExplicitlyUnavailablePhysicalEndpoints() throws Exception {
        DeviceIdentity unavailableIdentity = identity("unavailable");
        DeviceIdentity unknownIdentity = identity("unknown");
        AudioNode unavailable = sink(10, "alsa_output.unavailable", unavailableIdentity, PortAvailability.UNAVAILABLE);
        AudioNode unknown = sink(11, "alsa_output.unknown", unknownIdentity, PortAvailability.UNKNOWN);
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(unavailable, unknown),
                List.of(), List.of(), List.of(), Map.of(), unknown.id(), 0);

        try (ConfigStore store = new ConfigStore(temp.resolve("config.json"))) {
            DynamicRouteRuntime runtime = new DynamicRouteRuntime(store);
            try {
                DynamicInstanceRegistry.ReconcileResult result = runtime.reconcileNow(
                        DefaultConfig.create(), snapshot, List.of(), 1);
                String unavailableKey = "device:" + unavailableIdentity.fingerprint();
                String unknownKey = "device:" + unknownIdentity.fingerprint();

                assertFalse(result.instances().values().stream().anyMatch(instance -> unavailableKey.equals(instance.instanceKey())));
                assertTrue(result.instances().values().stream().anyMatch(instance -> unknownKey.equals(instance.instanceKey())));
            } finally {
                runtime.close();
            }
        }
    }

    @Test
    void savedGeneratedOutputStaysOfflineAndReturnsWithStableIdentityAfterReconnect() throws Exception {
        DeviceIdentity endpointIdentity = identity("reconnect-cycle");
        String instanceKey = "device:" + endpointIdentity.fingerprint();
        AudioNode firstNode = sink(20, "alsa_output.reconnect-cycle", endpointIdentity, PortAvailability.AVAILABLE);
        Map<String, Object> config = DefaultConfig.create();

        try (ConfigStore store = new ConfigStore(temp.resolve("reconnect-config.json"))) {
            DynamicRouteRuntime runtime = new DynamicRouteRuntime(store);
            try {
                DynamicInstanceRegistry.ReconcileResult firstResult = runtime.reconcileNow(
                        config, snapshot(firstNode), List.of(), 1);
                DynamicInstanceRegistry.RuntimeInstance first = outputInstance(firstResult, instanceKey, null);
                assertTrue(first.available());

                Map<String, Object> savedRoute = GraphCodec.toMap(firstResult.configuredGraph());
                config.put("route_library", new ArrayList<>(List.of(savedRoute)));

                AudioNode unavailable = sink(900, firstNode.name(), endpointIdentity, PortAvailability.UNAVAILABLE);
                DynamicInstanceRegistry.ReconcileResult offlineResult = runtime.reconcileNow(
                        config, snapshot(unavailable), List.of(), 2);
                DynamicInstanceRegistry.RuntimeInstance offline = outputInstance(
                        offlineResult, instanceKey, first.generatorId());

                assertFalse(offline.available());
                assertEquals(first.nodeIds(), offline.nodeIds(),
                        "the saved generated output retains its child IDs while its hardware endpoint is offline");

                AudioNode reconnected = sink(999, firstNode.name(), endpointIdentity, PortAvailability.AVAILABLE);
                DynamicInstanceRegistry.ReconcileResult reconnectResult = runtime.reconcileNow(
                        config, snapshot(reconnected), List.of(), 3);
                DynamicInstanceRegistry.RuntimeInstance restored = outputInstance(
                        reconnectResult, instanceKey, first.generatorId());

                assertTrue(restored.available());
                assertEquals(first.nodeIds(), restored.nodeIds());
                assertEquals(999, ((Number) restored.runtimeBinding().get("nodeId")).intValue(),
                        "the live binding follows the new PipeWire node ID without changing stable identity");
            } finally {
                runtime.close();
            }
        }
    }

    private static DynamicInstanceRegistry.RuntimeInstance outputInstance(
            DynamicInstanceRegistry.ReconcileResult result, String instanceKey, String generatorId) {
        return result.instances().values().stream()
                .filter(instance -> instanceKey.equals(instance.instanceKey()))
                .filter(instance -> generatorId == null || generatorId.equals(instance.generatorId()))
                .findFirst().orElseThrow();
    }

    private static PipeWireBackend.Snapshot snapshot(AudioNode sink) {
        return new PipeWireBackend.Snapshot(true, "", List.of(sink), List.of(), List.of(), List.of(), Map.of(),
                sink.id(), 0);
    }

    private static AudioNode sink(int id, String name, DeviceIdentity identity, PortAvailability availability) {
        return new AudioNode(id, name, name, "Audio/Sink", "running", "", "", "", 2, List.of(48000),
                1.0, false, null, identity, availability);
    }

    private static DeviceIdentity identity(String serial) {
        return DeviceIdentity.from(Map.of("device.bus-path", "pci-test", "device.serial", serial,
                "device.profile.name", "analog-stereo", "device.port.name", "analog-output", "audio.position", "FL,FR"), 2);
    }
}
