package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DynamicGeneratorNodeTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test void generatorHasNoPcmPortsAndRoundTripsParametersAndInstanceExtensions() {
        String key = "app:org.example.player";
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("node_ids", Map.of(
                "source", DynamicInstanceRegistry.stableNodeId("apps", key, "source"),
                "gain", DynamicInstanceRegistry.stableNodeId("apps", key, "gain")));
        instance.put("composite_id", DynamicInstanceRegistry.stableCompositeId("apps", key));
        instance.put("overrides", Map.of("muted", false, "icon", "audio-x-generic"));
        instance.put("instance_extension", Map.of("nested", List.of("kept", 7)));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("instances", Map.of(key, instance));
        parameters.put("parameter_extension", Map.of("nested", List.of(1, true, "kept")));

        DynamicGeneratorNode generator = new DynamicGeneratorNode(
                "apps", "Applications", "application:unassigned", STEREO, parameters);

        assertTrue(generator.ports().isEmpty());
        assertFalse(SourceNode.class.isAssignableFrom(generator.getClass()));
        assertEquals(STEREO, generator.channels());
        assertEquals("applications", generator.registry());
        assertEquals("default-application", generator.template());
        assertEquals(List.of("source", "gain"), generator.templateNodes().stream().map(node -> node.get("id")).toList());
        assertEquals(Map.of("nested", List.of(1, true, "kept")), generator.parameters().get("parameter_extension"));

        AudioGraph roundTripped = GraphCodec.fromMap(GraphCodec.toMap(
                new AudioGraph("route", "Route", List.of(generator), List.of())));
        DynamicGeneratorNode decoded = (DynamicGeneratorNode) roundTripped.nodes().getFirst();

        assertTrue(decoded.ports().isEmpty());
        assertEquals(generator.parameters(), decoded.parameters());
        assertEquals(generator.instances().get(key), decoded.instances().get(key));
        assertEquals(List.of(), GraphValidator.validate(roundTripped));
    }

    @Test void defaultsSelectTheRegistrySpecificTemplateGraphs() {
        DynamicGeneratorNode applications = new DynamicGeneratorNode("apps", "Apps", "application:unassigned", STEREO);
        DynamicGeneratorNode tabs = new DynamicGeneratorNode("tabs", "Tabs", "browser:*", STEREO,
                Map.of("registry", "browser_tabs"));
        DynamicGeneratorNode outputs = new DynamicGeneratorNode("outputs", "Outputs", "device:*", STEREO,
                Map.of("registry", "outputs"));

        assertEquals("default-application", applications.template());
        assertEquals(List.of("source", "gain"), applications.templateNodes().stream().map(node -> node.get("id")).toList());
        assertEquals("source-gain", applications.templateConnections().getFirst().get("id"));
        assertEquals("default-tab", tabs.template());
        assertEquals(List.of("source", "gain"), tabs.templateNodes().stream().map(node -> node.get("id")).toList());
        assertEquals("default-output", outputs.template());
        assertEquals(List.of("gain", "output"), outputs.templateNodes().stream().map(node -> node.get("id")).toList());
        assertEquals("device:unbound", map(outputs.templateNodes().get(1).get("parameters")).get("device_id"));
        assertEquals(1.0, applications.initialGain());
        assertTrue(applications.autoPin());
        assertTrue(applications.retainOnDisappear());
        assertTrue(applications.restoreOnReappear());
        assertEquals(List.of(), applications.excludedSelectors());
        assertEquals(Map.of(), applications.connectTo());
    }

    @Test void schemaUpgradeDisablesLegacyOutputAutoSelectionButPreservesExplicitPolicy() {
        Map<String, Object> legacyRoute = Map.of("id", "legacy-output", "name", "Legacy output",
                "nodes", List.of(legacyOutputGenerator("legacy", false, false)), "connections", List.of());
        Map<String, Object> explicitRoute = Map.of("id", "explicit-output", "name", "Explicit output",
                "nodes", List.of(legacyOutputGenerator("explicit", true, true)), "connections", List.of());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("route_library", List.of(legacyRoute, explicitRoute));

        List<Map<String, Object>> upgraded = maps(GraphCodec.upgradeSchema10Config(config).get("route_library"));
        Map<String, Object> legacyParameters = map(maps(upgraded.get(0).get("nodes")).getFirst().get("parameters"));
        Map<String, Object> explicitParameters = map(maps(upgraded.get(1).get("nodes")).getFirst().get("parameters"));

        assertEquals(Boolean.FALSE, legacyParameters.get("auto_select_trusted_hdmi_on_first_discovery"));
        assertEquals(Boolean.TRUE, explicitParameters.get("auto_select_trusted_hdmi_on_first_discovery"));
        DynamicGeneratorNode fresh = new DynamicGeneratorNode("fresh-output", "Fresh", "device:*", STEREO,
                Map.of("registry", "outputs"));
        assertFalse(fresh.autoSelectTrustedHdmiOnFirstDiscovery());
        assertTrue(fresh.withParameter("auto_select_trusted_hdmi_on_first_discovery", true)
                .autoSelectTrustedHdmiOnFirstDiscovery());
        DynamicGeneratorNode updated = fresh
                .withParameter("auto_select_trusted_hdmi_on_first_discovery", false)
                .withParameter("extension", "retained");
        assertFalse(updated.autoSelectTrustedHdmiOnFirstDiscovery());
        assertEquals("retained", updated.parameters().get("extension"));
    }

    @Test void schemaTenUpgradeNormalizesOnlyGeneratorParametersAndInfersLegacyTarget() {
        Map<String, Object> legacyEdge = Map.of("id", "legacy-link", "from_node", "apps", "from_port", "out",
                "to_node", "mixer", "to_port", "applications", "enabled", true, "gain", 1.0);
        Map<String, Object> legacyNode = legacyGenerator("apps");
        Map<String, Object> otherNode = Map.of("id", "mixer", "name", "Mixer", "type", "gain",
                "channels", STEREO, "parameters", Map.of("gain", 1.0, "muted", false), "extension", "untouched");
        Object extension = Map.of("preserved", List.of("value"));
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("id", "route-id");
        route.put("name", "Route");
        route.put("route_extension", Map.of("nested", "untouched"));
        route.put("nodes", new ArrayList<>(List.of(legacyNode, otherNode)));
        route.put("connections", new ArrayList<>(List.of(legacyEdge)));
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("id", "copy-id");
        copy.put("copy_extension", "untouched");
        copy.put("route", route);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("opaque", extension);
        config.put("route_library", new ArrayList<>(List.of(route)));
        config.put("saved_copies", new ArrayList<>(List.of(copy)));
        config.put("common_vibro_graph", route);

        Map<String, Object> upgraded = GraphCodec.upgradeSchema10Config(config);
        Map<String, Object> upgradedRoute = maps(upgraded.get("route_library")).getFirst();
        Map<String, Object> upgradedNode = maps(upgradedRoute.get("nodes")).getFirst();
        Map<String, Object> upgradedParameters = map(upgradedNode.get("parameters"));
        Map<String, Object> upgradedCopy = maps(upgraded.get("saved_copies")).getFirst();
        Map<String, Object> copiedRoute = map(upgradedCopy.get("route"));
        Map<String, Object> copiedGenerator = maps(copiedRoute.get("nodes")).getFirst();
        Map<String, Object> commonGenerator = maps(map(upgraded.get("common_vibro_graph")).get("nodes")).getFirst();

        assertEquals(extension, upgraded.get("opaque"));
        assertEquals("route-id", upgradedRoute.get("id"));
        assertEquals("route-id", copiedRoute.get("id"));
        assertEquals("copy-id", upgradedCopy.get("id"));
        assertEquals("untouched", map(upgradedRoute.get("route_extension")).get("nested"));
        assertEquals("untouched", maps(upgradedRoute.get("nodes")).get(1).get("extension"));
        assertEquals(Map.of("retained", List.of(3, "x")), upgradedParameters.get("generator_extension"));
        assertEquals(Map.of("retained", true), upgradedNode.get("node_extension"));
        assertEquals(List.of(legacyEdge), upgradedRoute.get("connections"));
        assertEquals(Map.of("node_id", "mixer", "port_id", "applications"), upgradedParameters.get("connect_to"));
        assertEquals("applications", upgradedParameters.get("registry"));
        assertEquals("default-application", upgradedParameters.get("template"));
        assertTrue(upgradedParameters.containsKey("template_nodes"));
        assertTrue(upgradedParameters.containsKey("template_connections"));
        assertTrue(map(copiedGenerator.get("parameters")).containsKey("instances"));
        assertTrue(map(commonGenerator.get("parameters")).containsKey("restore_on_reappear"));
        assertEquals("application:unassigned", map(legacyNode.get("parameters")).get("selector"));
        assertFalse(map(legacyNode.get("parameters")).containsKey("registry"));
    }

    @Test void copyRemapsConnectionAndInstanceIdsWhileRetainingExtensions() {
        String key = "app:org.example.player";
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("node_ids", Map.of("source", "old-source", "gain", "old-gain"));
        record.put("composite_id", "old-composite");
        record.put("overrides", Map.of("pinned", false));
        record.put("record_extension", Map.of("kept", true));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("registry", "applications");
        parameters.put("connect_to", Map.of("node_id", "mixer", "port_id", "applications", "anchor_extension", "kept"));
        parameters.put("instances", Map.of(key, record));
        parameters.put("parameter_extension", List.of("kept", Map.of("value", 8)));
        DynamicGeneratorNode original = new DynamicGeneratorNode("apps", "Apps", "application:unassigned", STEREO, parameters);

        DynamicGeneratorNode copied = (DynamicGeneratorNode) GraphNodeCopies.copy(original, "apps-copy",
                Map.of("mixer", "mixer-copy"));
        Map<String, Object> copiedRecord = map(copied.instances().get(key));
        Map<String, Object> copiedNodeIds = map(copiedRecord.get("node_ids"));

        assertEquals("mixer-copy", copied.connectTo().get("node_id"));
        assertEquals("kept", copied.connectTo().get("anchor_extension"));
        assertEquals(DynamicInstanceRegistry.stableNodeId("apps-copy", key, "source"), copiedNodeIds.get("source"));
        assertEquals(DynamicInstanceRegistry.stableNodeId("apps-copy", key, "gain"), copiedNodeIds.get("gain"));
        assertEquals(DynamicInstanceRegistry.stableCompositeId("apps-copy", key), copiedRecord.get("composite_id"));
        assertEquals(Map.of("pinned", false), copiedRecord.get("overrides"));
        assertEquals(Map.of("kept", true), copiedRecord.get("record_extension"));
        assertEquals(List.of("kept", Map.of("value", 8)), copied.parameters().get("parameter_extension"));
    }

    @Test void validatorAcceptsLegacyGeneratorControlEdgesAndOutputSwitchAnchors() {
        DynamicGeneratorNode applications = new DynamicGeneratorNode("apps", "Apps", "application:unassigned", STEREO);
        GainNode target = new GainNode("target", "Target", STEREO, 1.0, false);
        AudioGraph legacy = new AudioGraph("legacy", "Legacy", List.of(applications, target),
                List.of(new GraphConnection("legacy-link", "apps", "out", "target", "in", true, 0.5)));
        assertEquals(List.of(), GraphValidator.validate(legacy));

        DynamicGeneratorNode outputs = new DynamicGeneratorNode("outputs", "Outputs", "device:*", STEREO,
                Map.of("registry", "outputs", "connect_to", Map.of("node_id", "switch", "port_id", "in")));
        SwitchNode multiSwitch = new SwitchNode("switch", "MultiSwitch", STEREO, List.of("monitor"), List.of());
        AudioGraph outputGraph = new AudioGraph("output-route", "Outputs", List.of(outputs, multiSwitch), List.of());
        assertEquals(List.of(), GraphValidator.validate(outputGraph));
    }

    @Test void outputGeneratorsAllowDirectBranchesAndRequireDisjointSelectors() {
        String hapticSelector = "device:haptic-fingerprint";
        DynamicGeneratorNode broad = new DynamicGeneratorNode("all-outputs", "All outputs", "device:*", STEREO,
                Map.of("registry", "outputs", "connect_to", Map.of("node_id", "switch", "port_id", "in")));
        DynamicGeneratorNode exact = new DynamicGeneratorNode("haptic-output", "Haptic", hapticSelector, STEREO,
                Map.of("registry", "outputs", "connect_to", Map.of("node_id", "limiter", "port_id", "out")));
        SwitchNode multiSwitch = new SwitchNode("switch", "MultiSwitch", STEREO, List.of("monitor"), List.of());
        FilterNode limiter = new FilterNode("limiter", "Limiter", STEREO, "limiter", true, Map.of());
        AudioGraph overlapping = new AudioGraph("output-overlap", "Outputs",
                List.of(broad, exact, multiSwitch, limiter), List.of());
        assertTrue(GraphValidator.validate(overlapping).stream().anyMatch(message -> message.contains("overlap")));

        DynamicGeneratorNode partitioned = new DynamicGeneratorNode("all-outputs", "All outputs", "device:*", STEREO,
                Map.of("registry", "outputs", "connect_to", Map.of("node_id", "switch", "port_id", "in"),
                        "excluded_selectors", List.of(hapticSelector)));
        AudioGraph disjoint = new AudioGraph("output-disjoint", "Outputs",
                List.of(partitioned, exact, multiSwitch, limiter), List.of());
        assertEquals(List.of(), GraphValidator.validate(disjoint));
    }

    @Test void validatorRejectsUnknownRegistryInvalidTemplatesAndMalformedInstanceRecords() {
        DynamicGeneratorNode unknown = new DynamicGeneratorNode("unknown", "Unknown", "selector", STEREO,
                Map.of("registry", "unlisted", "instances", Map.of("key", "not-an-instance-record")));
        List<String> errors = GraphValidator.validate(new AudioGraph("unknown-route", "Unknown", List.of(unknown), List.of()));
        assertTrue(errors.stream().anyMatch(message -> message.contains("unknown registry")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("incompatible template")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("instance key must be an object")));

        DynamicGeneratorNode defaults = new DynamicGeneratorNode("apps", "Apps", "application:unassigned", STEREO);
        Map<String, Object> parameters = new LinkedHashMap<>(defaults.parameters());
        List<Map<String, Object>> connections = new ArrayList<>(defaults.templateConnections());
        Map<String, Object> badConnection = new LinkedHashMap<>(connections.getFirst());
        badConnection.put("from_port", "missing");
        connections.set(0, badConnection);
        parameters.put("template_connections", connections);
        DynamicGeneratorNode invalidPort = new DynamicGeneratorNode("bad-port", "Bad port", "application:unassigned", STEREO, parameters);
        List<String> templateErrors = GraphValidator.validate(new AudioGraph("bad-port-route", "Bad port", List.of(invalidPort), List.of()));
        assertTrue(templateErrors.stream().anyMatch(message -> message.contains("no output port")
                && message.contains("missing")));
    }

    @Test void validatorRejectsTemplateCyclesChannelMismatchesOverridesAndInvalidConnectTo() {
        DynamicGeneratorNode defaults = new DynamicGeneratorNode("apps", "Apps", "application:unassigned", STEREO);
        Map<String, Object> cycleParameters = new LinkedHashMap<>(defaults.parameters());
        List<Map<String, Object>> cycleConnections = new ArrayList<>(defaults.templateConnections());
        cycleConnections.add(Map.of("id", "feedback", "from_node", "gain", "from_port", "out",
                "to_node", "source", "to_port", "in", "enabled", true, "gain", 1.0));
        cycleParameters.put("template_connections", cycleConnections);
        DynamicGeneratorNode cyclic = new DynamicGeneratorNode("cycle", "Cycle", "application:unassigned",
                STEREO, cycleParameters);
        List<String> cycleErrors = GraphValidator.validate(
                new AudioGraph("cycle-route", "Cycle", List.of(cyclic), List.of()));
        assertTrue(cycleErrors.stream().anyMatch(message -> message.contains("feedback cycle")));

        Map<String, Object> channelParameters = new LinkedHashMap<>(defaults.parameters());
        List<Map<String, Object>> channelNodes = new ArrayList<>();
        for (Map<String, Object> templateNode : defaults.templateNodes()) {
            Map<String, Object> node = new LinkedHashMap<>(templateNode);
            if ("gain".equals(node.get("id"))) node.put("channels", List.of("MONO"));
            channelNodes.add(node);
        }
        channelParameters.put("template_nodes", channelNodes);
        DynamicGeneratorNode wrongChannels = new DynamicGeneratorNode("channels", "Channels",
                "application:unassigned", STEREO, channelParameters);
        List<String> channelErrors = GraphValidator.validate(
                new AudioGraph("channel-route", "Channels", List.of(wrongChannels), List.of()));
        assertTrue(channelErrors.stream().anyMatch(message -> message.contains("Channel layout mismatch")));

        String key = "app:org.example";
        DynamicGeneratorNode badOverride = defaults.withInstances(Map.of(key, Map.of(
                "node_ids", Map.of("source", DynamicInstanceRegistry.stableNodeId(defaults.id(), key, "source"),
                        "gain", DynamicInstanceRegistry.stableNodeId(defaults.id(), key, "gain")),
                "composite_id", DynamicInstanceRegistry.stableCompositeId(defaults.id(), key),
                "overrides", Map.of("gain", 1.1))));
        List<String> overrideErrors = GraphValidator.validate(
                new AudioGraph("override-route", "Override", List.of(badOverride), List.of()));
        assertTrue(overrideErrors.stream().anyMatch(message -> message.contains("invalid gain override")));

        DynamicGeneratorNode badAnchor = new DynamicGeneratorNode("anchor", "Anchor", "application:unassigned",
                STEREO, Map.of("connect_to", Map.of("node_id", "target", "port_id", "missing")));
        AudioGraph anchorGraph = new AudioGraph("anchor-route", "Anchor",
                List.of(badAnchor, new GainNode("target", "Target", STEREO, 1.0, false)), List.of());
        assertTrue(GraphValidator.validate(anchorGraph).stream()
                .anyMatch(message -> message.contains("connect_to has no input port")));
    }

    @Test void compilerDoesNotTreatGeneratorControlNodeAsPcmSource() {
        DynamicGeneratorNode generator = new DynamicGeneratorNode("apps", "Apps", "application:unassigned", STEREO);
        GainNode gain = new GainNode("gain", "Gain", STEREO, 1.0, false);
        OutputNode output = new OutputNode("output", "Output", "monitor", STEREO);
        AudioGraph graph = new AudioGraph("control-only", "Control only", List.of(generator, gain, output), List.of(
                new GraphConnection("generator-anchor", "apps", "out", "gain", "in", true, 1.0),
                new GraphConnection("gain-output", "gain", "out", "output", "in", true, 1.0)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GraphPipeWireCompiler.compile(graph, DefaultConfig.create()));
        assertTrue(error.getMessage().contains("at least one source"));
    }

    @Test void capturedTabCompilationCarriesExactRuntimeBindingWithoutRouteGain() {
        String instanceKey = "tab:chrome:instance:profile:session";
        String captureKey = "capture-42";
        String sourceId = DynamicInstanceRegistry.stableNodeId("tabs", instanceKey, "source");
        String gainId = DynamicInstanceRegistry.stableNodeId("tabs", instanceKey, "gain");
        String compositeId = DynamicInstanceRegistry.stableCompositeId("tabs", instanceKey);
        Map<String, Object> instanceRecord = Map.of(
                "node_ids", Map.of("source", sourceId, "gain", gainId),
                "composite_id", compositeId,
                "overrides", Map.of("gain", 0.4, "muted", true));
        DynamicGeneratorNode tabs = new DynamicGeneratorNode("tabs", "Browser tabs", "browser:*", STEREO,
                Map.of("registry", "browser_tabs", "connect_to", Map.of("node_id", "mixer", "port_id", "tab"),
                        "instances", Map.of(instanceKey, instanceRecord)));
        MixerNode mixer = new MixerNode("mixer", "Tabs input", STEREO, Map.of("tab", 1.0));
        SourceNode source = new SourceNode(sourceId, "Tab", "capture:" + captureKey, STEREO);
        GainNode gain = new GainNode(gainId, "Tab volume", STEREO, 0.4, true);
        OutputNode output = new OutputNode("output", "Monitor", "monitor", STEREO);
        AudioGraph configuredGraph = new AudioGraph("captured-route", "Captured tab", List.of(tabs, mixer), List.of());
        AudioGraph graph = new AudioGraph("captured-route", "Captured tab",
                List.of(tabs, mixer, source, gain, output), List.of(
                new GraphConnection("source-gain", sourceId, "out", gainId, "in", true, 1.0),
                new GraphConnection("gain-output", gainId, "out", output.id(), "in", true, 1.0)));
        Map<String, Object> runtimeBinding = Map.of(
                "captureStreamKey", captureKey,
                "nodeName", "audio-matrix-chrome-" + captureKey,
                "applicationRuleId", "chrome-tab-rule",
                "parentApplicationId", "com.google.Chrome",
                "parentProcessName", "chrome");
        DynamicInstanceRegistry.RuntimeInstance runtime = new DynamicInstanceRegistry.RuntimeInstance(
                "tabs", instanceKey, Map.of("source", sourceId, "gain", gainId), compositeId,
                true, DynamicInstanceRegistry.Confidence.HIGH, runtimeBinding, Map.of("gain", 0.4, "muted", true));
        DynamicInstanceRegistry.ReconcileResult reconciled = new DynamicInstanceRegistry.ReconcileResult(
                configuredGraph, graph, Map.of(new DynamicInstanceRegistry.InstanceAddress("tabs", instanceKey), runtime),
                List.of(), false);

        Map<String, Object> baseConfig = DefaultConfig.create();
        Map<String, Object> compiled = GraphPipeWireCompiler.compile(reconciled, baseConfig);
        assertFalse(baseConfig.containsKey("graph_capture_sources"));
        Map<String, Object> capture = maps(compiled.get("graph_capture_sources")).getFirst();
        Map<String, Object> route = maps(compiled.get("routes")).getFirst();

        assertEquals(Map.of(
                "source_id", sourceId,
                "instance_key", instanceKey,
                "capture_stream_key", captureKey,
                "node_name", "audio-matrix-chrome-" + captureKey,
                "application_rule_id", "chrome-tab-rule",
                "parent_application_id", "com.google.Chrome",
                "parent_process_name", "chrome",
                "tab_gain", 0.4,
                "tab_muted", true), capture);
        assertEquals(List.of(), maps(compiled.get("applications")));
        assertEquals(1.0, route.get("volume"));
        assertEquals(false, route.get("mute"));
        assertThrows(IllegalArgumentException.class,
                () -> GraphPipeWireCompiler.compile(graph, baseConfig));
    }

    private static Map<String, Object> legacyOutputGenerator(String id, boolean includePolicy, boolean policy) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("selector", "device:*");
        parameters.put("registry", "outputs");
        if (includePolicy) parameters.put("auto_select_trusted_hdmi_on_first_discovery", policy);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("name", "Outputs");
        node.put("type", "dynamic_generator");
        node.put("channels", STEREO);
        node.put("parameters", parameters);
        return node;
    }

    private static Map<String, Object> legacyGenerator(String id) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("name", "Applications");
        node.put("type", "dynamic_generator");
        node.put("channels", STEREO);
        node.put("parameters", new LinkedHashMap<>(Map.of("selector", "application:unassigned",
                "generator_extension", Map.of("retained", List.of(3, "x")))));
        node.put("node_extension", Map.of("retained", true));
        return node;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
