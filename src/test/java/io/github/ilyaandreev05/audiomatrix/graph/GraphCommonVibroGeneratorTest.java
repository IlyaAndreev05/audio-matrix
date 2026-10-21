package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphCommonVibroGeneratorTest {
    @Test void sharedMainOverlayPreservesGeneratorAndReconciledChildrenWithoutCatchAllSource() {
        AudioGraph defaults = GraphCommonVibro.defaults();
        List<GraphNode> configuredNodes = new ArrayList<>(defaults.nodes());
        DynamicGeneratorNode generator = new DynamicGeneratorNode(GraphCommonVibro.MAIN_SOURCE_ID,
                "Applications", "application:unassigned", GraphCommonVibro.CHANNELS);
        int sourceIndex = indexOf(configuredNodes, GraphCommonVibro.MAIN_SOURCE_ID);
        configuredNodes.set(sourceIndex, generator);
        AudioGraph selected = new AudioGraph("octine-monitor", "Monitor", configuredNodes, defaults.connections());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("common_vibro_graph", GraphCodec.toMap(defaults));

        AudioGraph overlaid = GraphCommonVibro.effectiveGraph(selected, config);
        DynamicGeneratorNode preserved = overlaid.nodes().stream()
                .filter(node -> GraphCommonVibro.MAIN_SOURCE_ID.equals(node.id()))
                .map(DynamicGeneratorNode.class::cast).findFirst().orElseThrow();
        assertEquals(generator.parameters(), preserved.parameters());
        assertFalse(overlaid.nodes().stream().anyMatch(node -> GraphCommonVibro.MAIN_SOURCE_ID.equals(node.id())
                && node instanceof SourceNode));

        DynamicInstanceRegistry.InstanceSnapshot app = DynamicInstanceRegistry.application(new AudioNode(51,
                "music-stream", "Music player", "Stream/Output/Audio", "running", "Music", "player", "org.example.music",
                2, List.of(48_000), 1.0, false, null));
        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(overlaid,
                Map.of(GraphCommonVibro.MAIN_SOURCE_ID, List.of(app)));
        DynamicInstanceRegistry.RuntimeInstance runtime = reconciled.instances().get(
                new DynamicInstanceRegistry.InstanceAddress(GraphCommonVibro.MAIN_SOURCE_ID, "app:org.example.music"));
        assertNotNull(runtime);
        assertNotEquals(runtime.nodeIds().get("source"), runtime.nodeIds().get("gain"));

        AudioGraph effective = GraphCommonVibro.effectiveGraph(reconciled.effectiveGraph(), config);
        DynamicGeneratorNode afterOverlay = effective.nodes().stream()
                .filter(node -> GraphCommonVibro.MAIN_SOURCE_ID.equals(node.id()))
                .map(DynamicGeneratorNode.class::cast).findFirst().orElseThrow();
        assertEquals(preserved.parameters(), afterOverlay.parameters());
        assertTrue(effective.nodes().stream().anyMatch(node -> node.id().equals(runtime.nodeIds().get("source"))
                && node instanceof SourceNode));
        assertTrue(effective.nodes().stream().anyMatch(node -> node.id().equals(runtime.nodeIds().get("gain"))
                && node instanceof GainNode));
        assertTrue(effective.connections().stream().anyMatch(edge -> edge.fromNode().equals(runtime.nodeIds().get("gain"))
                && edge.toNode().equals(GraphCommonVibro.MAIN_MIXER_ID)));
        assertFalse(effective.connections().stream().anyMatch(edge -> edge.fromNode().equals(GraphCommonVibro.MAIN_SOURCE_ID)));

        Map<String, Object> compiled = GraphPipeWireCompiler.compile(effective, config);
        @SuppressWarnings("unchecked") List<Map<String, Object>> buses = (List<Map<String, Object>>) compiled.get("buses");
        assertTrue(buses.stream().anyMatch(bus -> runtime.nodeIds().get("source").equals(bus.get("id"))));
        assertFalse(buses.stream().anyMatch(bus -> GraphCommonVibro.MAIN_SOURCE_ID.equals(bus.get("id"))));
    }

    private static int indexOf(List<GraphNode> nodes, String id) {
        for (int i = 0; i < nodes.size(); i++) if (id.equals(nodes.get(i).id())) return i;
        throw new AssertionError("Missing graph node " + id);
    }
}
