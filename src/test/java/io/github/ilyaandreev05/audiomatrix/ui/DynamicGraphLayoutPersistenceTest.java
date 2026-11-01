package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.config.ConfigValidator;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicGraphLayoutPersistenceTest {
    @Test void savedGeneratedPositionsAreValidAndDisappearingInstancesOnlyLoseTheirOwnPositions() {
        Map<String,Object> config = DefaultConfig.create();
        DynamicGeneratorNode generator = new DynamicGeneratorNode("generator", "Generator", "application:unassigned", List.of("FL", "FR"));
        Map<String,Object> parameters = new LinkedHashMap<>(generator.parameters());
        parameters.put("retain_on_disappear", false);
        generator = new DynamicGeneratorNode(generator.id(), generator.name(), generator.selector(), generator.channels(), parameters);
        Map<String,Object> records = new LinkedHashMap<>();
        records.put("gone", DynamicInstanceRegistry.seedInstanceRecord("generator", "gone", List.of("source", "gain")));
        records.put("keep", DynamicInstanceRegistry.seedInstanceRecord("generator", "keep", List.of("source", "gain")));
        generator = generator.withInstances(records);
        AudioGraph graph = new AudioGraph("layout-test", "Layout", List.of(generator, new GainNode("user-gain", "User", List.of("FL", "FR"), 1, false)), List.of());
        GraphRouteLibrary.saveRoute(config, graph, "");
        String gone = DynamicInstanceRegistry.stableNodeId("generator", "gone", "source");
        String keep = DynamicInstanceRegistry.stableNodeId("generator", "keep", "source");
        Map<String,Object> positions = new LinkedHashMap<>();
        positions.put(gone, Map.of("x", 100, "y", 200));
        positions.put(keep, Map.of("x", 300, "y", 400));
        positions.put("user-gain", Map.of("x", 500, "y", 600));
        config.put("graph_ui_layout", new LinkedHashMap<>(Map.of(graph.id(), positions)));
        String goneComposite = DynamicInstanceRegistry.stableCompositeId("generator", "gone");
        String keepComposite = DynamicInstanceRegistry.stableCompositeId("generator", "keep");
        GraphSubgraph root = new GraphSubgraph("user-group", "User group", List.of("generator", "user-gain"), false);
        GraphSubgraph vanished = new GraphSubgraph(goneComposite, "Gone", List.of(), true, root.id(), "generator");
        GraphSubgraph retained = new GraphSubgraph(keepComposite, "Keep", List.of(), true, goneComposite, "generator");
        GraphSubgraphLibrary.mutableByRoute(config).put(graph.id(), new ArrayList<>(GraphSubgraphLibrary.encoded(List.of(root, vanished, retained))));
        assertEquals(List.of(), ConfigValidator.validate(config));
        AudioGraph before = GraphCodec.fromMap(route(config, graph.id()));
        DynamicGeneratorNode saved = (DynamicGeneratorNode) before.nodes().stream().filter(n -> n.id().equals("generator")).findFirst().orElseThrow();
        Map<String,Object> remaining = new LinkedHashMap<>(saved.instances());
        remaining.remove("gone");
        List<GraphNode> nextNodes = new ArrayList<>(before.nodes());
        nextNodes.replaceAll(n -> n.id().equals("generator") ? saved.withInstances(remaining) : n);
        AudioGraph after = new AudioGraph(before.id(), before.name(), nextNodes, before.connections());
        DynamicInstanceRegistry.ReconcileResult result = new DynamicInstanceRegistry.ReconcileResult(after, after, Map.of(), List.of(), true);
        DynamicRegistryStore.mergeRegistryChanges(config, graph.id(), before, result);
        Map<?,?> layout = (Map<?,?>) ((Map<?,?>) config.get("graph_ui_layout")).get(graph.id());
        assertFalse(layout.containsKey(gone));
        assertEquals(Map.of("x", 300, "y", 400), layout.get(keep));
        assertEquals(Map.of("x", 500, "y", 600), layout.get("user-gain"));
        assertFalse(GraphLayoutNodes.ids(config, graph.id()).contains(gone));
        assertEquals(List.of(), ConfigValidator.validate(config));
        List<GraphSubgraph> groups = GraphSubgraphLibrary.forRoute(config, graph.id());
        assertEquals(2, groups.size());
        assertTrue(groups.stream().anyMatch(group -> group.id().equals(root.id())));
        assertEquals(root.id(), groups.stream().filter(group -> group.id().equals(keepComposite)).findFirst().orElseThrow().parentId());
        GraphInitialLayout.apply(config, graph.id(), Map.of(
                gone, new GraphCanvasLayout.Position(10, 20),
                keep, new GraphCanvasLayout.Position(30, 40),
                "user-gain", new GraphCanvasLayout.Position(50, 60)));
        layout = (Map<?,?>) ((Map<?,?>) config.get("graph_ui_layout")).get(graph.id());
        assertFalse(layout.containsKey(gone));
        assertEquals(Map.of("x", 300.0, "y", 400.0), layout.get(keep));
        assertEquals(Map.of("x", 500.0, "y", 600.0), layout.get("user-gain"));
        positions = new LinkedHashMap<>((Map<String,Object>) layout);
        positions.put(gone, Map.of("x", 1, "y", 2));
        config.put("graph_ui_layout", Map.of(graph.id(), positions));
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(e -> e.contains("references a missing node") && e.contains(gone)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> route(Map<String,Object> config, String id) {
        return ((List<Map<String,Object>>) config.get("route_library")).stream().filter(r -> id.equals(r.get("id"))).findFirst().orElseThrow();
    }
}
