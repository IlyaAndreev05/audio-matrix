package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.UnifiedGraphConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.AudioPort;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.MatrixNode;
import io.github.ilyaandreev05.audiomatrix.graph.RouterNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The GNOME panel toggles MultiSwitch links, including the runtime-generated output branches. */
class GraphRouteLinkCommandTest {
    private static final String GENERATED_PORT = "generated-output-11111111-2222-3333-4444-555555555555";
    private static final String GENERATED_LINK = "in->" + GENERATED_PORT;

    @Test void generatedOutputBranchTogglesWithoutASavedConnectionEdge() {
        Map<String, Object> config = DefaultConfig.create();
        addGeneratedOutputPort(config, GENERATED_PORT);
        AudioGraph before = route(config);

        GraphNodeCommand.setRouteEnabled(config, "main", UnifiedGraphConfig.SWITCH_ID, GENERATED_LINK, true);

        RouterNode enabled = switchNode(config);
        assertEquals(List.of(GENERATED_LINK), enabled.activeLinks());
        assertTrue(enabled.outputs().stream().anyMatch(port -> port.id().equals(GENERATED_PORT)),
                "the generated output port must survive the toggle");
        assertOnlyActiveLinksChanged(before, route(config));

        GraphNodeCommand.setRouteEnabled(config, "main", UnifiedGraphConfig.SWITCH_ID, GENERATED_LINK, false);

        assertEquals(List.of(), switchNode(config).activeLinks());
        assertOnlyActiveLinksChanged(before, route(config));
    }

    @Test void unknownOutputPortStaysRejected() {
        Map<String, Object> config = DefaultConfig.create();

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () ->
                GraphNodeCommand.setRouteEnabled(config, "main", UnifiedGraphConfig.SWITCH_ID, "in->missing", true));
        assertEquals("Route link is not connected", missing.getMessage());
        IllegalArgumentException unknownGenerated = assertThrows(IllegalArgumentException.class, () ->
                GraphNodeCommand.setRouteEnabled(config, "main", UnifiedGraphConfig.SWITCH_ID,
                        "in->generated-output-99999999-9999-9999-9999-999999999999", true));
        assertEquals("Route link is not connected", unknownGenerated.getMessage());
        assertEquals(List.of(), switchNode(config).activeLinks());
    }

    /** The command must not turn another router kind into a plain switch. */
    @Test void routerCopyKeepsItsKindPortsAndChannels() {
        MatrixNode matrix = new MatrixNode("matrix-1", "Matrix", List.of("FL", "FR"),
                List.of("in-1", "in-2"), List.of("out-1"), List.of());

        RouterNode copy = matrix.withActiveLinks(List.of("in-1->out-1"));

        assertEquals("matrix", copy.type());
        assertEquals(List.of("in-1", "in-2"), copy.inputs().stream().map(AudioPort::id).toList());
        assertEquals(List.of("out-1"), copy.outputs().stream().map(AudioPort::id).toList());
        assertEquals(List.of("FL", "FR"), copy.inputs().getFirst().channels());
        assertEquals(List.of("in-1->out-1"), copy.activeLinks());
    }

    /** Adds a runtime-generated output port to the saved MultiSwitch without a connection edge. */
    @SuppressWarnings("unchecked")
    private static void addGeneratedOutputPort(Map<String, Object> config, String portId) {
        Map<String, Object> node = ((List<Map<String, Object>>) routeMap(config).get("nodes")).stream()
                .filter(item -> UnifiedGraphConfig.SWITCH_ID.equals(item.get("id"))).findFirst().orElseThrow();
        Map<String, Object> parameters = (Map<String, Object>) node.get("parameters");
        List<String> ports = new ArrayList<>();
        if (parameters.get("output_ports") instanceof List<?> saved)
            saved.forEach(port -> ports.add(String.valueOf(port)));
        ports.add(portId);
        parameters.put("output_ports", ports);
    }

    /** The toggle may change the active links of the MultiSwitch and nothing else. */
    private static void assertOnlyActiveLinksChanged(AudioGraph before, AudioGraph after) {
        assertEquals(before.id(), after.id());
        assertEquals(before.name(), after.name());
        assertEquals(before.nodes().size(), after.nodes().size());
        for (int index = 0; index < before.nodes().size(); index++) {
            GraphNode previous = before.nodes().get(index);
            GraphNode current = after.nodes().get(index);
            assertEquals(previous.id(), current.id());
            assertEquals(previous.type(), current.type());
            assertEquals(previous.name(), current.name());
            assertEquals(previous.ports(), current.ports());
            if (!previous.id().equals(UnifiedGraphConfig.SWITCH_ID))
                assertEquals(previous.parameters(), current.parameters());
        }
        assertEquals(before.connections(), after.connections());
    }

    private static RouterNode switchNode(Map<String, Object> config) {
        return (RouterNode) route(config).nodes().stream()
                .filter(node -> node.id().equals(UnifiedGraphConfig.SWITCH_ID)).findFirst().orElseThrow();
    }

    private static AudioGraph route(Map<String, Object> config) {
        return GraphCodec.fromMap(routeMap(config));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> routeMap(Map<String, Object> config) {
        return routes(config).stream().filter(item -> "main".equals(item.get("id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> routes(Map<String, Object> config) {
        return (List<Map<String, Object>>) config.get("route_library");
    }
}
