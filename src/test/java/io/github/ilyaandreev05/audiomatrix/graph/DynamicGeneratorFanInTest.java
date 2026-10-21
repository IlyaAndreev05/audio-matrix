package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DynamicGeneratorFanInTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test void generatedApplicationStreamsFanIntoOneMergeThroughStableDistinctPorts() {
        DynamicGeneratorNode applications = new DynamicGeneratorNode("apps", "Applications",
                "application:unassigned", STEREO, Map.of("connect_to", Map.of(
                "node_id", "merge", "port_id", "applications")));
        MixerNode merge = new MixerNode("merge", "Application Merge", STEREO,
                Map.of("applications", 1.0, "additional", 1.0));
        SwitchNode multi = new SwitchNode("multi", "MultiSwitch", STEREO, List.of("monitor"), List.of("in->monitor"));
        OutputNode output = new OutputNode("monitor-output", "Monitor", "device:monitor", STEREO);
        AudioGraph graph = new AudioGraph("route-fan-in", "Application fan-in", List.of(applications, merge, multi, output),
                List.of(edge("merge-switch", "merge", "out", "multi", "in"),
                        edge("switch-output", "multi", "monitor", "monitor-output", "in")));
        List<DynamicInstanceRegistry.InstanceSnapshot> streams = List.of(
                DynamicInstanceRegistry.application(stream(1, "org.example.music", "music", "Music")),
                DynamicInstanceRegistry.application(stream(2, "org.example.chat", "chat", "Chat")),
                DynamicInstanceRegistry.application(stream(3, "org.example.video", "video", "Video")));

        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(graph,
                Map.of("apps", streams));
        MixerNode effectiveMerge = reconciled.effectiveGraph().nodes().stream()
                .filter(MixerNode.class::isInstance).map(MixerNode.class::cast).findFirst().orElseThrow();
        List<GraphConnection> fanIn = reconciled.effectiveGraph().connections().stream()
                .filter(edge -> edge.enabled() && edge.toNode().equals("merge") && !edge.toPort().equals("applications"))
                .toList();

        assertEquals(1, reconciled.effectiveGraph().nodes().stream().filter(MixerNode.class::isInstance).count());
        assertEquals(3, fanIn.size());
        assertEquals(3, fanIn.stream().map(GraphConnection::toPort).distinct().count());
        assertTrue(fanIn.stream().allMatch(edge -> effectiveMerge.inputs().stream()
                .anyMatch(port -> port.id().equals(edge.toPort()))));
        assertTrue(fanIn.stream().allMatch(edge -> reconciled.effectiveGraph().nodes().stream()
                .anyMatch(node -> node.id().equals(edge.fromNode()) && node instanceof GainNode)));
        assertEquals(Set.of("applications", "additional"), merge.inputs().stream().map(AudioPort::id)
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of(), GraphValidator.validate(reconciled.effectiveGraph()));

        Map<String, Object> compiled = GraphPipeWireCompiler.compile(reconciled, DefaultConfig.create());
        assertEquals(3, ((List<?>) compiled.get("buses")).size());
        assertEquals(3, ((List<?>) compiled.get("routes")).size());
        assertTrue(((List<?>) compiled.get("routes")).stream().map(DynamicGeneratorFanInTest::object)
                .allMatch(route -> Double.valueOf(1.0).equals(route.get("volume"))));
        DynamicInstanceRegistry.ReconcileResult repeat = DynamicInstanceRegistry.reconcile(
                reconciled.configuredGraph(), Map.of("apps", streams));
        assertFalse(repeat.configChanged());
        assertEquals(fanIn.stream().map(GraphConnection::toPort).toList(), repeat.effectiveGraph().connections().stream()
                .filter(edge -> edge.enabled() && edge.toNode().equals("merge") && !edge.toPort().equals("applications"))
                .map(GraphConnection::toPort).toList());
    }

    @Test void normalOutputWildcardExcludesExplicitHapticRoleAndLeavesNewLinksOff() {
        DynamicGeneratorNode normal = new DynamicGeneratorNode("normal", "Normal outputs", "device:*", STEREO,
                Map.of("registry", "outputs", "connect_to", Map.of("node_id", "multi", "port_id", "in"),
                        "excluded_selectors", List.of("device:role:haptic_output")));
        SwitchNode multi = new SwitchNode("multi", "MultiSwitch", STEREO, List.of("existing"), List.of());
        AudioGraph graph = new AudioGraph("route-output-roles", "Output roles", List.of(normal, multi), List.of());
        AudioNode headphones = output(10, "alsa_output.example.headphones", "Headphones", "pci-audio", "serial-audio",
                "analog-output-headphones");
        AudioNode monitor = output(11, "alsa_output.example.hdmi", "Monitor", "pci-monitor", "serial-monitor", "hdmi-output");
        DynamicInstanceRegistry.InstanceSnapshot hapticSnapshot = DynamicInstanceRegistry.output(headphones, false, null,
                "haptic_output");
        DynamicInstanceRegistry.InstanceSnapshot ordinarySnapshot = DynamicInstanceRegistry.output(monitor, false, null, "");

        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(graph,
                Map.of("normal", List.of(hapticSnapshot, ordinarySnapshot)));

        assertTrue(reconciled.instances().containsKey(new DynamicInstanceRegistry.InstanceAddress(
                "normal", "device:" + monitor.identity().fingerprint())));
        assertFalse(reconciled.instances().containsKey(new DynamicInstanceRegistry.InstanceAddress(
                "normal", "device:" + headphones.identity().fingerprint())));
        SwitchNode selected = reconciled.configuredGraph().nodes().stream().filter(SwitchNode.class::isInstance)
                .map(SwitchNode.class::cast).findFirst().orElseThrow();
        assertEquals(List.of(), selected.activeLinks());
    }

    private static AudioNode stream(int id, String applicationId, String process, String name) {
        return new AudioNode(id, "stream-" + id, name, "Stream/Output/Audio", "running", name, process,
                applicationId, 2, List.of(48_000), 1.0, false, null);
    }

    private static AudioNode output(int id, String name, String description, String busPath, String serial, String port) {
        DeviceIdentity identity = DeviceIdentity.from(Map.of("device.bus-path", busPath, "device.serial", serial,
                "device.profile.name", "stereo", "device.port.name", port, "audio.position", "FL,FR"), 2);
        return new AudioNode(id, name, description, "Audio/Sink", "running", "", "", "", 2,
                List.of(48_000), 0.5, false, null, identity);
    }

    private static GraphConnection edge(String id, String from, String fromPort, String to, String toPort) {
        return new GraphConnection(id, from, fromPort, to, toPort, true, 1.0);
    }

    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

}
