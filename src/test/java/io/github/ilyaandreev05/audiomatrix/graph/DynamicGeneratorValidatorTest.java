package io.github.ilyaandreev05.audiomatrix.graph;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.STEREO;
import static org.junit.jupiter.api.Assertions.*;

class DynamicGeneratorValidatorTest {
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
}
