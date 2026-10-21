package io.github.ilyaandreev05.audiomatrix.graph;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphSubgraphParentingTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test void movingNestedGroupsPreservesMembershipAndRejectsCycles() {
        AudioGraph graph = graph();
        Map<String, Object> config = new LinkedHashMap<>();
        GraphSubgraph parent = GraphSubgraphLibrary.create(config, graph, "Parent", List.of("source", "gain-a"));
        GraphSubgraph child = GraphSubgraphLibrary.create(config, graph, "Child", List.of("gain-b", "output"));

        GraphSubgraphLibrary.move(config, graph.id(), child.id(), parent.id());
        assertEquals(parent.id(), group(config, graph.id(), child.id()).parentId());
        assertEquals(List.of("gain-b", "output"), group(config, graph.id(), child.id()).nodeIds());
        assertThrows(IllegalArgumentException.class,
                () -> GraphSubgraphLibrary.move(config, graph.id(), parent.id(), child.id()));
        GraphSubgraphLibrary.move(config, graph.id(), child.id(), null);
        assertNull(group(config, graph.id(), child.id()).parentId());
    }

    @Test void generatedCompositeGroupsCannotBeDeletedOrMoved() {
        AudioGraph graph = graph();
        String generatorId = "tabs";
        String instanceKey = "tab:chrome:instance:profile:session";
        GraphSubgraph generated = new GraphSubgraph(
                DynamicInstanceRegistry.stableCompositeId(generatorId, instanceKey), "Generated tab", List.of(), true,
                null, generatorId);
        Map<String, Object> byRoute = new LinkedHashMap<>();
        byRoute.put(graph.id(), new ArrayList<>(List.of(generated.toMap())));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("graph_ui_subgraphs", byRoute);

        assertThrows(IllegalArgumentException.class,
                () -> GraphSubgraphLibrary.delete(config, graph.id(), generated.id()));
        assertThrows(IllegalArgumentException.class,
                () -> GraphSubgraphLibrary.move(config, graph.id(), generated.id(), null));
        assertEquals(generated.id(), GraphSubgraphLibrary.forRoute(config, graph.id()).getFirst().id());
    }

    private static GraphSubgraph group(Map<String, Object> config, String routeId, String id) {
        return GraphSubgraphLibrary.forRoute(config, routeId).stream().filter(group -> id.equals(group.id()))
                .findFirst().orElseThrow();
    }

    private static AudioGraph graph() {
        return new AudioGraph("route-parenting", "Nested groups", List.of(
                new SourceNode("source", "Applications", "application:unassigned", STEREO),
                new GainNode("gain-a", "Input volume", STEREO, 1.0, false),
                new GainNode("gain-b", "Output volume", STEREO, 1.0, false),
                new OutputNode("output", "Monitor", "device:monitor", STEREO)), List.of(
                edge("source-gain", "source", "out", "gain-a", "in"),
                edge("gain-chain", "gain-a", "out", "gain-b", "in"),
                edge("gain-output", "gain-b", "out", "output", "in")));
    }

    private static GraphConnection edge(String id, String from, String fromPort, String to, String toPort) {
        return new GraphConnection(id, from, fromPort, to, toPort, true, 1.0);
    }
}
