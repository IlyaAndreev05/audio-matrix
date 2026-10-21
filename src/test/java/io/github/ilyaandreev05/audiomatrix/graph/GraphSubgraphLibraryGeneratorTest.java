package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.ConfigValidator;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphSubgraphLibraryGeneratorTest {

    @Test void routeDuplicationRemapsGroupParentsAndDeletionPromotesChildren() {
        Map<String,Object> config = DefaultConfig.create();
        AudioGraph graph = GraphSubgraphLibraryFixtures.graphWithGenerator(Map.of());
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphSubgraph root = GraphSubgraphLibrary.create(config, graph, "Root", List.of("generator", "gain-a"));
        String stableKey = "app:stable-child";
        GraphSubgraph container = new GraphSubgraph(DynamicInstanceRegistry.stableCompositeId("generator", stableKey),
                "Automatic", List.of(), true, root.id(), "generator");
        GraphSubgraphLibraryFixtures.storeSubgraphs(config, graph.id(), List.of(container));
        GraphRouteLibrary.saveRoute(config, GraphSubgraphLibraryFixtures.withGeneratorInstances(graph, Map.of(stableKey, container.id())), "");

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

    @Test void removingNodeDissolvesInvalidGroupsAndKeepsGeneratorOwnedComposites() {
        Map<String,Object> config = DefaultConfig.create();
        AudioGraph graph = GraphSubgraphLibraryFixtures.graphWithGenerator(Map.of());
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphSubgraph root = GraphSubgraphLibrary.create(config, graph, "Root", List.of("generator", "gain-a"));
        GraphSubgraph child = GraphSubgraphLibrary.create(config, graph, "Child", List.of("gain-b", "output"), root.id());
        String stableKey = "app:stable-child";
        GraphSubgraph container = new GraphSubgraph(DynamicInstanceRegistry.stableCompositeId("generator", stableKey),
                "Automatic", List.of(), true, child.id(), "generator");
        GraphSubgraphLibraryFixtures.storeSubgraphs(config, graph.id(), List.of(container));
        GraphRouteLibrary.saveRoute(config, GraphSubgraphLibraryFixtures.withGeneratorInstances(graph, Map.of(stableKey, container.id())), "");

        GraphSubgraphLibrary.removeNode(config, graph.id(), "gain-b");

        List<GraphSubgraph> afterRemove = GraphSubgraphLibrary.forRoute(config, graph.id());
        assertTrue(afterRemove.stream().noneMatch(group -> group.id().equals(child.id())));
        assertEquals(root.id(), afterRemove.stream().filter(group -> group.id().equals(container.id())).findFirst().orElseThrow().parentId());
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }
}
