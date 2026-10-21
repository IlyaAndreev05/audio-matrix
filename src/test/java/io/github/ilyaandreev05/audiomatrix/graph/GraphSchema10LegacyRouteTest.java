package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Behaviour of the schema-10 upgrade for a migrated legacy route with a static output branch. */
class GraphSchema10LegacyRouteTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test void migratedLegacyRouteAnchorsTheApplicationsGeneratorAtTheMerge() {
        Map<String, Object> upgraded = upgrade(legacyConfig());
        Map<String, Object> route = route(upgraded);
        Map<String, Object> generator = generator(route, "octine-main-source");
        assertEquals("applications", generator.get("registry"));
        assertEquals(Map.of("node_id", "octine-main-mixer", "port_id", "applications"),
                generator.get("connect_to"),
                "the generated application children enter the Merge");
        assertEquals("octine-main-mixer", legacyEdgeTarget(route, "octine-main-source"),
                "the legacy control edge follows the inferred Merge anchor");

        AudioGraph migrated = GraphCodec.fromMap(route);
        AudioNode app = new AudioNode(64, "music", "Music", "Stream/Output/Audio", "running",
                "Music player", "music", "org.example.music", 2, List.of(48_000), 0.8, false, null);
        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(migrated,
                Map.of("octine-main-source", List.of(DynamicInstanceRegistry.application(app))));
        assertEquals(List.of(), GraphValidator.validate(reconciled.effectiveGraph()),
                "the migrated route with materialized application children stays valid");
    }

    @Test void migratedLegacyRouteGainsTheNormalOutputsGenerator() {
        Map<String, Object> upgraded = upgrade(legacyConfig());
        Map<String, Object> route = route(upgraded);
        Map<String, Object> outputs = generator(route, null);
        assertNotNull(outputs, "the migrated route gains a normal outputs generator");
        assertEquals("outputs", outputs.get("registry"));
        assertEquals("device:*", outputs.get("selector"));
        assertEquals(Map.of("node_id", "octine-main-switch", "port_id", "in"), outputs.get("connect_to"));
        assertEquals(Boolean.FALSE, outputs.get("auto_select_trusted_hdmi_on_first_discovery"));
        @SuppressWarnings("unchecked")
        List<String> excluded = (List<String>) outputs.get("excluded_selectors");
        assertTrue(excluded.contains("device:role:haptic_output"));
    }

    @Test void migrationSurvivesAStaticOutputWithoutSavedIdentity() {
        Map<String, Object> config = legacyConfig();
        Map<String, Object> upgraded = upgrade(config);
        assertNotNull(route(upgraded));
        assertNotNull(generator(route(upgraded), null), "the outputs generator is still added");
    }

    @Test void aLiveSinkRoutedByAStaticOutputIsNotDuplicatedAsAGeneratedBranch() {
        Map<String, Object> config = legacyConfig();
        Map<String, Object> upgraded = upgrade(config);
        AudioGraph graph = GraphCodec.fromMap(route(upgraded));
        AudioNode live = new AudioNode(71, "benq-hdmi", "Monitor", "Audio/Sink", "running", "", "", "",
                2, List.of(48_000), 1.0, false, null, DeviceIdentity.from(Map.of(
                "device.bus-path", "pci-0000:01:00.0", "device.serial", "serial-monitor",
                "device.profile.name", "hdmi-stereo", "device.port.name", "hdmi-output",
                "audio.position", "FL,FR"), 2));
        DynamicInstanceRegistry.InstanceSnapshot sink = DynamicInstanceRegistry.output(live, false);
        String outputsId = String.valueOf(outputsNode(route(upgraded)).get("id"));

        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(graph,
                Map.of(outputsId, List.of(sink)));

        assertTrue(reconciled.instances().isEmpty(),
                "the device already routed by the static slot must not become a generated branch");
        assertTrue(reconciled.effectiveGraph().nodes().stream()
                .noneMatch(node -> node instanceof OutputNode && "benq-hdmi".equals(((OutputNode) node).deviceId())
                        && !node.id().equals("octine-monitor-output")));
    }

    private static Map<String, Object> legacyConfig() {
        Map<String, Object> config = DefaultConfig.create();
        List<Map<String, Object>> routes = new ArrayList<>();
        routes.add(legacyRoute());
        config.put("route_library", routes);
        config.put("devices", List.of(Map.of("id", "benq-hdmi", "name", "benq-hdmi",
                "selector", Map.of("kind", "name_equals", "value", "benq-hdmi"), "volume", 1.0, "mute", false)));
        return config;
    }

    private static Map<String, Object> legacyRoute() {
        AudioGraph graph = new AudioGraph("main", "Main", List.of(
                new DynamicGeneratorNode("octine-main-source", "Applications", "application:unassigned", STEREO),
                new MixerNode("octine-main-mixer", "Merge", STEREO, Map.of("applications", 1.0, "additional", 1.0)),
                new SwitchNode("octine-main-switch", "MultiSwitch", STEREO, List.of("monitor"), List.of("in->monitor")),
                new DeviceSlotNode("octine-monitor-output", "Monitor", "benq-hdmi", STEREO),
                new DeviceSlotNode("octine-vibro-output", "Vibro", "", STEREO),
                new GainNode("endpoint-gain-1", "Endpoint gain 1", STEREO, 1.0, false),
                new GainNode("endpoint-gain-2", "Endpoint gain 2", STEREO, 1.0, false),
                new GainNode("octine-vibro-gain", "Vibro gain", STEREO, 0.6, false),
                new FilterNode("octine-vibro-limiter", "Limiter", STEREO, "limiter", true, Map.of())), List.of(
                new GraphConnection("source-gain-1", "octine-main-source", "out", "endpoint-gain-1", "in", true, 1.0),
                new GraphConnection("gain-1-gain-2", "endpoint-gain-1", "out", "endpoint-gain-2", "in", true, 1.0),
                new GraphConnection("gain-2-mixer", "endpoint-gain-2", "out", "octine-main-mixer", "applications", true, 1.0),
                new GraphConnection("mixer-switch", "octine-main-mixer", "out", "octine-main-switch", "in", true, 1.0),
                new GraphConnection("switch-monitor", "octine-main-switch", "monitor", "octine-monitor-output", "in", true, 1.0)));
        Map<String, Object> encoded = GraphCodec.toMap(graph);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) encoded.get("nodes");
        for (Map<String, Object> node : nodes)
            if ("octine-main-source".equals(node.get("id")))
                node.put("parameters", Map.of("selector", "application:unassigned"));
        return encoded;
    }

    private static Map<String, Object> upgrade(Map<String, Object> config) {
        return GraphCodec.upgradeSchema10Config(config);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> route(Map<String, Object> config) {
        List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("route_library");
        return routes.stream().filter(item -> "main".equals(item.get("id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> generator(Map<String, Object> route, String id) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) route.get("nodes");
        for (Map<String, Object> node : nodes) {
            if (!"dynamic_generator".equals(node.get("type"))) continue;
            Map<String, Object> parameters = (Map<String, Object>) node.get("parameters");
            if (id == null) {
                if (!"outputs".equals(parameters.get("registry"))) continue;
                return parameters;
            }
            if (id.equals(node.get("id"))) return parameters;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> outputsNode(Map<String, Object> route) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) route.get("nodes");
        for (Map<String, Object> node : nodes) {
            if (!"dynamic_generator".equals(node.get("type"))) continue;
            Map<String, Object> parameters = (Map<String, Object>) node.get("parameters");
            if ("outputs".equals(parameters.get("registry"))) return node;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String legacyEdgeTarget(Map<String, Object> route, String generatorId) {
        List<Map<String, Object>> connections = (List<Map<String, Object>>) route.get("connections");
        return connections.stream().filter(edge -> generatorId.equals(edge.get("from_node")))
                .map(edge -> String.valueOf(edge.get("to_node"))).findFirst().orElse(null);
    }
}
