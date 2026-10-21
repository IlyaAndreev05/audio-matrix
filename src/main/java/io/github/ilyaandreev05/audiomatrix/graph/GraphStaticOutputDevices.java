package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps the registry from duplicating a device that a static output or device slot already routes. */
final class GraphStaticOutputDevices {
    private GraphStaticOutputDevices() {}

    /** Returns the devices that the graph's static Output and DeviceSlot nodes already route to. */
    static Set<String> routedDevices(AudioGraph graph) {
        Set<String> devices = new LinkedHashSet<>();
        for (GraphNode node : graph.nodes()) {
            if (node instanceof OutputNode output) devices.add(output.deviceId());
            else if (node instanceof DeviceSlotNode slot) devices.add(slot.deviceId());
        }
        devices.removeIf(id -> id == null || id.isBlank() || "device:unbound".equals(id)
                || "system_default".equals(id));
        return devices;
    }

    /** Drops the discovered devices that a static branch of the graph already owns. */
    static List<DynamicInstanceRegistry.InstanceSnapshot> withoutRoutedDevices(AudioGraph graph,
            List<DynamicInstanceRegistry.InstanceSnapshot> snapshots) {
        Set<String> devices = routedDevices(graph);
        if (devices.isEmpty()) return snapshots;
        List<DynamicInstanceRegistry.InstanceSnapshot> result = new ArrayList<>(snapshots.size());
        for (DynamicInstanceRegistry.InstanceSnapshot snapshot : snapshots)
            if (!alreadyRouted(devices, snapshot)) result.add(snapshot);
        return result;
    }

    private static boolean alreadyRouted(Set<String> devices, DynamicInstanceRegistry.InstanceSnapshot snapshot) {
        Map<String, Object> binding = snapshot.runtimeBinding();
        if (devices.contains(DynamicRegistryValues.text(binding.get("deviceId")))) return true;
        String fingerprint = DynamicRegistryIdentity.identityFingerprint(snapshot);
        return !fingerprint.isBlank() && devices.contains("device:" + fingerprint);
    }
}
