package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.ConfigValidator;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphSubgraphLibraryTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test void collapsibleSubgraphKeepsTopologyAndBoundaryPorts() {
        Map<String,Object> config = DefaultConfig.create();
        AudioGraph graph = graph();
        GraphRouteLibrary.saveRoute(config, graph, "");

        GraphSubgraph subgraph = GraphSubgraphLibrary.create(config, graph, "Browser processing", List.of("gain-a", "gain-b"));
        List<GraphSubgraph.BoundaryPort> ports = GraphSubgraph.boundaryPorts(graph, subgraph.nodeIds());

        assertTrue(subgraph.collapsed());
        assertEquals(2, ports.size());
        assertTrue(ports.stream().anyMatch(port -> port.direction() == GraphSubgraph.Direction.INPUT && port.nodeId().equals("gain-a")));
        assertTrue(ports.stream().anyMatch(port -> port.direction() == GraphSubgraph.Direction.OUTPUT && port.nodeId().equals("gain-b")));
        assertEquals(6, GraphCodec.fromMap(route(config)).nodes().size());
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

    @Test void subgraphsAreCopiedWithNewNodeIdsAndSavedCopiesStayInactive() {
        Map<String,Object> config = DefaultConfig.create();
        AudioGraph graph = graph();
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphSubgraphLibrary.create(config, graph, "Processing", List.of("gain-a", "gain-b"));

        AudioGraph duplicate = GraphRouteLibrary.duplicateRoute(config, graph.id(), "Copy");
        GraphSubgraph duplicateGroup = GraphSubgraphLibrary.forRoute(config, duplicate.id()).getFirst();
        assertNotEquals("gain-a", duplicateGroup.nodeIds().getFirst());
        assertTrue(duplicate.nodes().stream().map(GraphNode::id).toList().containsAll(duplicateGroup.nodeIds()));

        String copyId = GraphRouteLibrary.saveInactiveCopy(config, graph.id(), "Snapshot");
        GraphRouteLibrary.deleteRoute(config, graph.id(), duplicate.id());
        AudioGraph restored = GraphRouteLibrary.restoreSavedCopy(config, copyId, "Restored");
        GraphSubgraph restoredGroup = GraphSubgraphLibrary.forRoute(config, restored.id()).getFirst();
        assertTrue(restored.nodes().stream().map(GraphNode::id).toList().containsAll(restoredGroup.nodeIds()));
        assertFalse(Boolean.TRUE.equals(((Map<?,?>) ((List<?>) config.get("saved_copies")).getFirst()).get("active")));
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

    @Test void metadataRejectsOverlappingGroupsAndNodeDeletionCleansMembership() {
        Map<String,Object> config = DefaultConfig.create();
        AudioGraph graph = graph();
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphSubgraphLibrary.create(config, graph, "First", List.of("gain-a", "gain-b"));
        GraphSubgraph second = new GraphSubgraph("second", "Second", List.of("source", "output"), true);
        GraphSubgraphLibrary.mutableByRoute(config).put(graph.id(), new ArrayList<>(GraphSubgraphLibrary.encoded(
                List.of(GraphSubgraphLibrary.forRoute(config, graph.id()).getFirst(), second))));
        @SuppressWarnings("unchecked") List<Map<String,Object>> groups = (List<Map<String,Object>>) GraphSubgraphLibrary.mutableByRoute(config).get(graph.id());
        groups.get(1).put("node_ids", List.of("gain-b", "output"));
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(problem -> problem.contains("more than one subgraph")));

        GraphSubgraphLibrary.removeNode(config, graph.id(), "gain-a");
        assertTrue(GraphSubgraphLibrary.forRoute(config, graph.id()).stream().noneMatch(group -> group.nodeIds().contains("gain-a")));
    }

    @Test void expansionStateCanChangeWithoutChangingAudioGraph() {
        Map<String,Object> config = DefaultConfig.create();
        AudioGraph graph = graph();
        AudioGraph normalized = GraphEndpointGainFactory.ensure(graph);
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphSubgraph group = GraphSubgraphLibrary.create(config, graph, "Processing", List.of("gain-a", "gain-b"));
        GraphSubgraphLibrary.setCollapsed(config, graph.id(), group.id(), false);
        assertFalse(GraphSubgraphLibrary.forRoute(config, graph.id()).getFirst().collapsed());
        assertEquals(GraphCodec.toMap(normalized), GraphCodec.toMap(GraphCodec.fromMap(route(config))));
    }

    private static AudioGraph graph() {
        return new AudioGraph("route-subgraph", "Subgraph test", List.of(
                new SourceNode("source", "Browser", "application:chrome", STEREO),
                new GainNode("gain-a", "Input gain", STEREO, .8, false),
                new GainNode("gain-b", "Output gain", STEREO, .6, false),
                new OutputNode("output", "Monitor", "device:monitor", STEREO)), List.of(
                edge("one", "source", "gain-a"), edge("two", "gain-a", "gain-b"), edge("three", "gain-b", "output")));
    }
    private static GraphConnection edge(String id, String from, String to) {
        return new GraphConnection(id, from, "out", to, "in", true, 1);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> route(Map<String,Object> config) {
        return ((List<Map<String,Object>>) config.get("route_library")).stream()
                .filter(item -> "route-subgraph".equals(item.get("id"))).findFirst().orElseThrow();
    }
}
