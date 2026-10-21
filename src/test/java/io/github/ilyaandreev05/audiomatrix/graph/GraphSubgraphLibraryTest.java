package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.ConfigValidator;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphSubgraphLibraryTest {

    @Test void collapsibleSubgraphKeepsTopologyAndBoundaryPorts() {
        Map<String,Object> config = DefaultConfig.create();
        AudioGraph graph = GraphSubgraphLibraryFixtures.graph();
        GraphRouteLibrary.saveRoute(config, graph, "");

        GraphSubgraph subgraph = GraphSubgraphLibrary.create(config, graph, "Browser processing", List.of("gain-a", "gain-b"));
        List<GraphSubgraph.BoundaryPort> ports = GraphSubgraph.boundaryPorts(graph, subgraph.nodeIds());

        assertTrue(subgraph.collapsed());
        assertEquals(2, ports.size());
        assertTrue(ports.stream().anyMatch(port -> port.direction() == GraphSubgraph.Direction.INPUT && port.nodeId().equals("gain-a")));
        assertTrue(ports.stream().anyMatch(port -> port.direction() == GraphSubgraph.Direction.OUTPUT && port.nodeId().equals("gain-b")));
        assertEquals(6, GraphCodec.fromMap(GraphSubgraphLibraryFixtures.route(config)).nodes().size());
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

    @Test void subgraphsAreCopiedWithNewNodeIdsAndSavedCopiesStayInactive() {
        Map<String,Object> config = DefaultConfig.create();
        AudioGraph graph = GraphSubgraphLibraryFixtures.graph();
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
        AudioGraph graph = GraphSubgraphLibraryFixtures.graph();
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

    @Test void nestedCollapseStateDoesNotChangeAudioTopologyOrCompiledRoutes() {
        Map<String,Object> config = DefaultConfig.create();
        AudioGraph graph = GraphSubgraphLibraryFixtures.graph();
        AudioGraph normalized = GraphEndpointGainFactory.ensure(graph);
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphSubgraph root = GraphSubgraphLibrary.create(config, graph, "Root", List.of("source", "gain-a"));
        GraphSubgraph child = GraphSubgraphLibrary.create(config, graph, "Child", List.of("gain-b", "output"), root.id());
        Map<String,Object> compiled = GraphPipeWireCompiler.compile(normalized, config);

        GraphSubgraphLibrary.setCollapsed(config, graph.id(), root.id(), false);
        GraphSubgraphLibrary.setCollapsed(config, graph.id(), child.id(), false);

        assertFalse(GraphSubgraphLibrary.forRoute(config, graph.id()).stream().filter(group -> group.id().equals(root.id())).findFirst().orElseThrow().collapsed());
        assertFalse(GraphSubgraphLibrary.forRoute(config, graph.id()).stream().filter(group -> group.id().equals(child.id())).findFirst().orElseThrow().collapsed());
        assertEquals(GraphCodec.toMap(normalized), GraphCodec.toMap(GraphCodec.fromMap(GraphSubgraphLibraryFixtures.route(config))));
        Map<String,Object> compiledAfter = GraphPipeWireCompiler.compile(normalized, config);
        assertEquals(compiled.get("buses"), compiledAfter.get("buses"));
        assertEquals(compiled.get("routes"), compiledAfter.get("routes"));
        assertEquals(compiled.get("graph_default_bus"), compiledAfter.get("graph_default_bus"));
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

    @Test void legacyEncodingAndGeneratorContainersRoundTrip() {
        Map<String,Object> legacyMap = Map.of("id", "legacy", "name", "Legacy", "node_ids", List.of("source", "gain-a"), "collapsed", true);
        GraphSubgraph legacy = GraphSubgraph.fromMap(legacyMap);
        assertNull(legacy.parentId());
        assertNull(legacy.generatorId());
        assertEquals(legacyMap, legacy.toMap());
        assertThrows(IllegalArgumentException.class, () -> new GraphSubgraph("empty", "Empty", List.of(), true));

        Map<String,Object> config = DefaultConfig.create();
        AudioGraph graph = GraphSubgraphLibraryFixtures.graphWithGenerator(Map.of());
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphSubgraph parent = GraphSubgraphLibrary.create(config, graph, "Parent", List.of("generator", "gain-a"));
        String emptyKey = "app:empty-instance";
        String singleKey = "app:single-instance";
        GraphSubgraph container = new GraphSubgraph(DynamicInstanceRegistry.stableCompositeId("generator", emptyKey),
                "Automatic", List.of(), true, parent.id(), "generator");
        GraphSubgraph generatedSingleton = new GraphSubgraph(DynamicInstanceRegistry.stableCompositeId("generator", singleKey),
                "Single-node automatic", List.of("output"), true, parent.id(), "generator");
        GraphSubgraphLibraryFixtures.storeSubgraphs(config, graph.id(), List.of(container, generatedSingleton));
        GraphRouteLibrary.saveRoute(config, GraphSubgraphLibraryFixtures.withGeneratorInstances(graph, Map.of(
                emptyKey, container.id(), singleKey, generatedSingleton.id())), "");
        assertThrows(IllegalArgumentException.class, () -> GraphSubgraphLibrary.delete(config, graph.id(), container.id()));
        assertThrows(IllegalArgumentException.class, () -> GraphSubgraphLibrary.removeNode(config, graph.id(), "output"));

        assertEquals(parent.id(), container.parentId());
        assertEquals("generator", container.generatorId());
        assertTrue(container.nodeIds().isEmpty());
        assertEquals(container, GraphSubgraph.fromMap(container.toMap()));
        assertEquals(List.of("output"), generatedSingleton.nodeIds());
        assertEquals("generator", generatedSingleton.generatorId());
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }
}
