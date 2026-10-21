package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.AudioPort;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphPipeWireCompiler;
import io.github.ilyaandreev05.audiomatrix.graph.GraphValidator;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SwitchNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigGraphDeviceAssignmentTest {

    @Test void newGraphStartsWithMusicOnlyOnMonitorAndVibroUnassigned() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph graph = route(config, UnifiedGraphConfig.ROUTE_ID);
        SwitchNode multiSwitch = graph.nodes().stream().filter(SwitchNode.class::isInstance)
                .map(SwitchNode.class::cast).findFirst().orElseThrow();
        DynamicGeneratorNode ordinary = generator(graph, UnifiedGraphConfig.MONITOR_OUTPUT_ID);
        DynamicGeneratorNode vibro = generator(graph, GraphCommonVibro.VIBRO_OUTPUT_ID);
        assertEquals(List.of("in"), multiSwitch.inputs().stream().map(AudioPort::id).toList());
        assertEquals(List.of(), multiSwitch.activeLinks(),
                "a fresh graph selects no output before a device is discovered");
        assertEquals("device:*", ordinary.selector());
        assertEquals(Map.of("node_id", UnifiedGraphConfig.SWITCH_ID, "port_id", "in"), ordinary.connectTo());
        assertEquals("device:role:haptic_output", vibro.selector());
        assertEquals(Map.of(), vibro.instances(), "the vibro device stays unassigned until it is discovered");
        assertEquals(List.of(), ConfigValidator.validate(config));
    }

    @Test void oneGraphCannotAssignMonitorAndVibroToTheSamePhysicalOutput() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph graph = route(config, UnifiedGraphConfig.ROUTE_ID);
        assertEquals(List.of("device:role:haptic_output"),
                generator(graph, UnifiedGraphConfig.MONITOR_OUTPUT_ID).excludedSelectors());

        AudioGraph shared = withSelector(graph, UnifiedGraphConfig.MONITOR_OUTPUT_ID, "device:*", List.of());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GraphPipeWireCompiler.compile(shared, config));
        assertTrue(error.getMessage().contains("octine-monitor-output")
                && error.getMessage().contains("octine-vibro-output")
                && error.getMessage().contains("device:role:haptic_output"), error.getMessage());

        AudioNode device = sharedDevice();
        DynamicInstanceRegistry.InstanceSnapshot observation = DynamicInstanceRegistry.output(device, false,
                new DynamicInstanceRegistry.GainState(0.5, false), "haptic_output");
        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(graph,
                Map.of(UnifiedGraphConfig.MONITOR_OUTPUT_ID, List.of(observation),
                        GraphCommonVibro.VIBRO_OUTPUT_ID, List.of(observation)));
        String instanceKey = "device:" + device.identity().fingerprint();
        assertTrue(reconciled.instances().keySet().stream().noneMatch(address ->
                UnifiedGraphConfig.MONITOR_OUTPUT_ID.equals(address.generatorId())));
        assertEquals(instanceKey, reconciled.instances().keySet().stream()
                .filter(address -> GraphCommonVibro.VIBRO_OUTPUT_ID.equals(address.generatorId()))
                .map(DynamicInstanceRegistry.InstanceAddress::instanceKey).findFirst().orElseThrow());
        List<OutputNode> bound = reconciled.effectiveGraph().nodes().stream().filter(OutputNode.class::isInstance)
                .map(OutputNode.class::cast).filter(node -> device.name().equals(node.deviceId())).toList();
        assertEquals(1, bound.size(), "the physical output must not feed the monitor and the vibro at once");
        assertEquals(DynamicInstanceRegistry.stableNodeId(GraphCommonVibro.VIBRO_OUTPUT_ID, instanceKey, "output"),
                bound.getFirst().id());
    }

    @Test void oneGraphCannotAssignTheSamePhysicalOutputToTwoSlots() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph graph = route(config, UnifiedGraphConfig.ROUTE_ID);
        AudioGraph distinct = withSelector(graph, UnifiedGraphConfig.MONITOR_OUTPUT_ID,
                "device:" + sharedDevice().identity().fingerprint(), List.of("device:role:haptic_output"));
        assertEquals(List.of(), GraphValidator.validate(distinct));
        assertEquals("main", GraphPipeWireCompiler.compile(distinct, config).get("active_route"));
    }

    @SuppressWarnings("unchecked")
    private static AudioGraph route(Map<String, Object> config, String routeId) {
        List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("route_library");
        return GraphCodec.fromMap(routes.stream()
                .filter(item -> routeId.equals(item.get("id"))).findFirst().orElseThrow());
    }

    private static DynamicGeneratorNode generator(AudioGraph graph, String generatorId) {
        return graph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).filter(node -> generatorId.equals(node.id()))
                .findFirst().orElseThrow();
    }

    private static AudioGraph withSelector(AudioGraph graph, String generatorId, String selector,
                                           List<String> excludedSelectors) {
        List<GraphNode> nodes = new ArrayList<>();
        for (GraphNode node : graph.nodes()) {
            if (!(node instanceof DynamicGeneratorNode generator) || !generator.id().equals(generatorId)) {
                nodes.add(node);
                continue;
            }
            Map<String, Object> parameters = new LinkedHashMap<>(generator.parameters());
            parameters.put("selector", selector);
            parameters.put("excluded_selectors", excludedSelectors);
            nodes.add(new DynamicGeneratorNode(generator.id(), generator.name(), selector,
                    generator.channels(), parameters));
        }
        return new AudioGraph(graph.id(), graph.name(), nodes, graph.connections());
    }

    private static AudioNode sharedDevice() {
        DeviceIdentity identity = DeviceIdentity.from(Map.of("device.serial", "shared-output",
                "device.profile.name", "analog-stereo", "device.port.name", "front-output",
                "audio.position", "FL,FR"), 2);
        return new AudioNode(51, "alsa_output.shared", "Shared output", "Audio/Sink", "running",
                "", "", "", 2, List.of(48000), 0.6, false, null, identity);
    }
}
