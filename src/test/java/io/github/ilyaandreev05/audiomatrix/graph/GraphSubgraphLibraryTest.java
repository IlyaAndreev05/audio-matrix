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

    @Test void nestedCollapseStateDoesNotChangeAudioTopologyOrCompiledRoutes() {
        Map<String,Object> config = DefaultConfig.create();
        AudioGraph graph = graph();
        AudioGraph normalized = GraphEndpointGainFactory.ensure(graph);
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphSubgraph root = GraphSubgraphLibrary.create(config, graph, "Root", List.of("source", "gain-a"));
        GraphSubgraph child = GraphSubgraphLibrary.create(config, graph, "Child", List.of("gain-b", "output"), root.id());
        Map<String,Object> compiled = GraphPipeWireCompiler.compile(normalized, config);

        GraphSubgraphLibrary.setCollapsed(config, graph.id(), root.id(), false);
        GraphSubgraphLibrary.setCollapsed(config, graph.id(), child.id(), false);

        assertFalse(GraphSubgraphLibrary.forRoute(config, graph.id()).stream().filter(group -> group.id().equals(root.id())).findFirst().orElseThrow().collapsed());
        assertFalse(GraphSubgraphLibrary.forRoute(config, graph.id()).stream().filter(group -> group.id().equals(child.id())).findFirst().orElseThrow().collapsed());
        assertEquals(GraphCodec.toMap(normalized), GraphCodec.toMap(GraphCodec.fromMap(route(config))));
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
        AudioGraph graph = graphWithGenerator(Map.of());
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphSubgraph parent = GraphSubgraphLibrary.create(config, graph, "Parent", List.of("generator", "gain-a"));
        String emptyKey = "app:empty-instance";
        String singleKey = "app:single-instance";
        GraphSubgraph container = new GraphSubgraph(DynamicInstanceRegistry.stableCompositeId("generator", emptyKey),
                "Automatic", List.of(), true, parent.id(), "generator");
        GraphSubgraph generatedSingleton = new GraphSubgraph(DynamicInstanceRegistry.stableCompositeId("generator", singleKey),
                "Single-node automatic", List.of("output"), true, parent.id(), "generator");
        storeSubgraphs(config, graph.id(), List.of(container, generatedSingleton));
        GraphRouteLibrary.saveRoute(config, withGeneratorInstances(graph, Map.of(
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

    @Test void routeDuplicationRemapsGroupParentsAndDeletionPromotesChildren() {
        Map<String,Object> config = DefaultConfig.create();
        AudioGraph graph = graphWithGenerator(Map.of());
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphSubgraph root = GraphSubgraphLibrary.create(config, graph, "Root", List.of("generator", "gain-a"));
        String stableKey = "app:stable-child";
        GraphSubgraph container = new GraphSubgraph(DynamicInstanceRegistry.stableCompositeId("generator", stableKey),
                "Automatic", List.of(), true, root.id(), "generator");
        storeSubgraphs(config, graph.id(), List.of(container));
        GraphRouteLibrary.saveRoute(config, withGeneratorInstances(graph, Map.of(stableKey, container.id())), "");

        AudioGraph duplicate = GraphRouteLibrary.duplicateRoute(config, graph.id(), "Copy");
        List<GraphSubgraph> copied = GraphSubgraphLibrary.forRoute(config, duplicate.id());
        GraphSubgraph copiedRoot = copied.stream().filter(group -> group.name().equals("Root")).findFirst().orElseThrow();
        GraphSubgraph copiedContainer = copied.stream().filter(group -> group.name().equals("Automatic")).findFirst().orElseThrow();
        DynamicGeneratorNode copiedGenerator = duplicate.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).findFirst().orElseThrow();
        assertNotEquals(root.id(), copiedRoot.id());
        assertEquals(copiedRoot.id(), copiedContainer.parentId());
        assertEquals(copiedGenerator.id(), copiedContainer.generatorId());
        assertEquals(DynamicInstanceRegistry.stableCompositeId(copiedGenerator.id(), stableKey), copiedContainer.id());

        GraphSubgraphLibrary.delete(config, duplicate.id(), copiedRoot.id());
        GraphSubgraph promotedContainer = GraphSubgraphLibrary.forRoute(config, duplicate.id()).stream()
                .filter(group -> group.name().equals("Automatic")).findFirst().orElseThrow();
        assertNull(promotedContainer.parentId());
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

    @Test void validationChecksHierarchyAndDirectMembership() {
        String autoKey = "app:auto";
        String autoId = DynamicInstanceRegistry.stableCompositeId("generator", autoKey);
        AudioGraph graph = graphWithGenerator(Map.of(autoKey, autoId));
        GraphSubgraph root = new GraphSubgraph("root", "Root", List.of("generator", "gain-a"), true);
        GraphSubgraph child = new GraphSubgraph("child", "Child", List.of("gain-b", "output"), true, root.id(), null);
        GraphSubgraph container = new GraphSubgraph(autoId, "Automatic", List.of(), true, child.id(), "generator");
        Map<String,Object> config = configWithGroups(graph, GraphSubgraphLibrary.encoded(List.of(root, child, container)));
        assertTrue(ConfigValidator.validate(config).isEmpty());

        GraphSubgraph orphan = new GraphSubgraph("orphan", "Orphan", List.of("generator", "gain-a"), true, "missing", null);
        assertTrue(ConfigValidator.validate(configWithGroups(graphWithGenerator(Map.of()), GraphSubgraphLibrary.encoded(List.of(orphan))))
                .stream().anyMatch(problem -> problem.contains("missing parent")));

        GraphSubgraph cycleA = new GraphSubgraph("cycle-a", "Cycle A", List.of("generator", "gain-a"), true, "cycle-b", null);
        GraphSubgraph cycleB = new GraphSubgraph("cycle-b", "Cycle B", List.of("gain-b", "output"), true, "cycle-a", null);
        assertTrue(ConfigValidator.validate(configWithGroups(graphWithGenerator(Map.of()), GraphSubgraphLibrary.encoded(List.of(cycleA, cycleB))))
                .stream().anyMatch(problem -> problem.contains("parent cycle")));

        GraphSubgraph duplicateId = new GraphSubgraph("root", "Duplicate", List.of("gain-b", "output"), true);
        assertTrue(ConfigValidator.validate(configWithGroups(graphWithGenerator(Map.of()), GraphSubgraphLibrary.encoded(List.of(root, duplicateId))))
                .stream().anyMatch(problem -> problem.contains("duplicate id")));

        GraphSubgraph overlapping = new GraphSubgraph("overlap", "Overlap", List.of("generator", "gain-b"), true);
        assertTrue(ConfigValidator.validate(configWithGroups(graphWithGenerator(Map.of()), GraphSubgraphLibrary.encoded(List.of(root, overlapping))))
                .stream().anyMatch(problem -> problem.contains("more than one subgraph")));

        GraphSubgraph missingNode = new GraphSubgraph("missing-node", "Missing node", List.of("unknown", "gain-a"), true);
        assertTrue(ConfigValidator.validate(configWithGroups(graphWithGenerator(Map.of()), GraphSubgraphLibrary.encoded(List.of(missingNode))))
                .stream().anyMatch(problem -> problem.contains("references a missing node")));

        String wrongGeneratorId = DynamicInstanceRegistry.stableCompositeId("missing-generator", "app:wrong");
        GraphSubgraph wrongGenerator = new GraphSubgraph(wrongGeneratorId, "Wrong generator", List.of(), true, null, "missing-generator");
        assertTrue(ConfigValidator.validate(configWithGroups(graphWithGenerator(Map.of()), GraphSubgraphLibrary.encoded(List.of(wrongGenerator))))
                .stream().anyMatch(problem -> problem.contains("generator")));

        String unassociatedId = DynamicInstanceRegistry.stableCompositeId("generator", "app:unassociated");
        GraphSubgraph unassociated = new GraphSubgraph(unassociatedId, "Unassociated", List.of(), true, null, "generator");
        assertTrue(ConfigValidator.validate(configWithGroups(graphWithGenerator(Map.of()), GraphSubgraphLibrary.encoded(List.of(unassociated))))
                .stream().anyMatch(problem -> problem.contains("exactly one")));

        String duplicateCompositeId = DynamicInstanceRegistry.stableCompositeId("generator", "app:duplicate");
        GraphSubgraph duplicateAssociation = new GraphSubgraph(duplicateCompositeId, "Duplicate association", List.of(), true, null, "generator");
        AudioGraph duplicateInstances = graphWithGenerator(Map.of("app:first", duplicateCompositeId, "app:second", duplicateCompositeId));
        assertTrue(ConfigValidator.validate(configWithGroups(duplicateInstances, GraphSubgraphLibrary.encoded(List.of(duplicateAssociation))))
                .stream().anyMatch(problem -> problem.contains("exactly one")));
        String missingGroupId = DynamicInstanceRegistry.stableCompositeId("generator", "app:missing-group");
        Map<String,Object> missingGroupConfig = DefaultConfig.create();
        GraphRouteLibrary.saveRoute(missingGroupConfig,
                graphWithGenerator(Map.of("app:missing-group", missingGroupId)), "");
        assertTrue(ConfigValidator.validate(missingGroupConfig).stream()
                .anyMatch(problem -> problem.contains("references a missing subgraph")));

        Map<String,Object> unassociatedEmpty = Map.of("id", "empty", "name", "Empty", "node_ids", List.of(), "collapsed", true);
        assertTrue(ConfigValidator.validate(configWithGroups(graphWithGenerator(Map.of()), List.of(unassociatedEmpty)))
                .stream().anyMatch(problem -> problem.contains("generator")));
    }

    @Test void removingNodeDissolvesInvalidGroupsAndKeepsGeneratorOwnedComposites() {
        Map<String,Object> config = DefaultConfig.create();
        AudioGraph graph = graphWithGenerator(Map.of());
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphSubgraph root = GraphSubgraphLibrary.create(config, graph, "Root", List.of("generator", "gain-a"));
        GraphSubgraph child = GraphSubgraphLibrary.create(config, graph, "Child", List.of("gain-b", "output"), root.id());
        String stableKey = "app:stable-child";
        GraphSubgraph container = new GraphSubgraph(DynamicInstanceRegistry.stableCompositeId("generator", stableKey),
                "Automatic", List.of(), true, child.id(), "generator");
        storeSubgraphs(config, graph.id(), List.of(container));
        GraphRouteLibrary.saveRoute(config, withGeneratorInstances(graph, Map.of(stableKey, container.id())), "");

        GraphSubgraphLibrary.removeNode(config, graph.id(), "gain-b");

        List<GraphSubgraph> afterRemove = GraphSubgraphLibrary.forRoute(config, graph.id());
        assertTrue(afterRemove.stream().noneMatch(group -> group.id().equals(child.id())));
        assertEquals(root.id(), afterRemove.stream().filter(group -> group.id().equals(container.id())).findFirst().orElseThrow().parentId());
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

    private static Map<String,Object> configWithGroups(AudioGraph graph, List<Map<String,Object>> groups) {
        Map<String,Object> config = DefaultConfig.create();
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphSubgraphLibrary.mutableByRoute(config).put(graph.id(), new ArrayList<>(groups));
        return config;
    }
    private static void storeSubgraphs(Map<String,Object> config, String routeId, List<GraphSubgraph> additions) {
        List<GraphSubgraph> groups = new ArrayList<>(GraphSubgraphLibrary.forRoute(config, routeId));
        groups.addAll(additions);
        GraphSubgraphLibrary.mutableByRoute(config).put(routeId, new ArrayList<>(GraphSubgraphLibrary.encoded(groups)));
    }


    private static AudioGraph graphWithGenerator(Map<String,String> composites) {
        AudioGraph graph = new AudioGraph("route-subgraph", "Subgraph test", List.of(
                new DynamicGeneratorNode("generator", "Browser generator", "application:unassigned", STEREO),
                new GainNode("gain-a", "Input gain", STEREO, .8, false),
                new GainNode("gain-b", "Output gain", STEREO, .6, false),
                new OutputNode("output", "Monitor", "device:monitor", STEREO)), List.of(
                edge("one", "generator", "gain-a"), edge("two", "gain-a", "gain-b"), edge("three", "gain-b", "output")));
        return withGeneratorInstances(graph, composites);
    }

    private static AudioGraph withGeneratorInstances(AudioGraph graph, Map<String,String> composites) {
        List<GraphNode> nodes = new ArrayList<>(graph.nodes());
        DynamicGeneratorNode generator = nodes.stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).findFirst().orElseThrow();
        Map<String,Object> instances = new LinkedHashMap<>(generator.instances());
        for (Map.Entry<String,String> entry : composites.entrySet()) {
            String sourceId = "generated-" + entry.getKey() + "-source";
            String gainId = "generated-" + entry.getKey() + "-gain";
            nodes.add(new SourceNode(sourceId, "Generated source", "application:" + entry.getKey(), STEREO));
            nodes.add(new GainNode(gainId, "Generated gain", STEREO, 1, false));
            instances.put(entry.getKey(), Map.of(
                    "node_ids", Map.of("source", sourceId, "gain", gainId),
                    "composite_id", entry.getValue(), "overrides", Map.of()));
        }
        nodes.replaceAll(node -> node.id().equals(generator.id()) ? generator.withInstances(instances) : node);
        return new AudioGraph(graph.id(), graph.name(), nodes, graph.connections());
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
