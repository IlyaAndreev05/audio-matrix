package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.*;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.UnifiedGraphConfig;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DynamicRouteRuntimeStaticAliasTest {
    @Test void catalogueAliasExcludesOnlyTheResolvedStaticDeviceFromDiscovery() {
        var config = DefaultConfig.create();
        Map<String,Object> route = maps(config.get("route_library")).getFirst();
        AudioGraph graph = GraphCodec.fromMap(route);
        List<GraphNode> nodes = new ArrayList<>(graph.nodes());
        // As in GraphStaticOutputDevices, even an unconnected explicit slot reserves its device.
        nodes.add(new DeviceSlotNode("static-device", "Static", "device:catalogue-id", List.of("FL", "FR")));
        Map<String,Object> saved = new LinkedHashMap<>(route);
        saved.putAll(GraphCodec.toMap(new AudioGraph(graph.id(), graph.name(), nodes, graph.connections())));
        config.put("route_library", List.of(saved));
        config.put("devices", List.of(Map.of("id", "catalogue-id", "selector", Map.of("kind", "name_equals", "value", "generic.sink"))));
        AudioNode assigned = sink(10, "generic.sink", "first");
        AudioNode other = sink(11, "other.sink", "second");
        var snapshot = new PipeWireBackend.Snapshot(true, "", List.of(assigned, other), List.of(), List.of(), List.of(), Map.of(), 10, 0);
        try (var runtime = new DynamicRouteRuntime(null)) {
            var result = runtime.reconcileNow(config, snapshot, List.of(), 1);
            assertFalse(result.instances().values().stream().anyMatch(i -> i.available() && ("device:" + assigned.identity().fingerprint()).equals(i.instanceKey())));
            assertTrue(result.instances().values().stream().anyMatch(i -> i.available() && ("device:" + other.identity().fingerprint()).equals(i.instanceKey())));
        }
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value) { return (List<Map<String,Object>>) value; }
    private static AudioNode sink(int id, String name, String serial) {
        DeviceIdentity identity = DeviceIdentity.from(Map.of("device.bus-path", "generic-path", "device.serial", serial,
                "device.profile.name", "generic-profile", "device.port.name", "generic-port", "audio.position", "FL,FR"), 2);
        return new AudioNode(id, name, name, "Audio/Sink", "running", "", "", "", 2, List.of(48000), 1.0, false, null, identity, PortAvailability.UNKNOWN);
    }
}
