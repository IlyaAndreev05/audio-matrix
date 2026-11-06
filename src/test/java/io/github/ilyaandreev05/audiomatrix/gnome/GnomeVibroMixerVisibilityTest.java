package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GnomeVibroMixerVisibilityTest {
    private static final List<String> STEREO = List.of("FL", "FR");
    @Test void sameIdInCustomRouteRemainsVisibleInSavedAndCompositePins() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph graph = new AudioGraph("test-vibro", "Test", List.of(
                new MixerNode(GraphCommonVibro.VIBRO_MIXER_ID, "Vibro mixer", STEREO, Map.of("in", 1.0)),
                new MixerNode("other-mixer", "Other mixer", STEREO, Map.of("in", 1.0)),
                new GainNode("test-gain", "Gain", STEREO, 1, false)), List.of(
                edge("other-mixer", "test-gain", "in")));
        GraphRouteLibrary.saveRoute(config, graph, "");
        config.put("active_route", graph.id());
        QuickSettingsConfig.pinNode(config, graph.id(), GraphCommonVibro.VIBRO_MIXER_ID, "routing", 0, "DEFAULT");
        QuickSettingsConfig.pinNode(config, graph.id(), "other-mixer", "routing", 1, "DEFAULT");
        var composite = io.github.ilyaandreev05.audiomatrix.graph.GraphSubgraphLibrary.create(
                config, graph, "Group", List.of(GraphCommonVibro.VIBRO_MIXER_ID, "test-gain"));
        QuickSettingsConfig.pinNode(config, graph.id(), composite.id(), "routing", 2, "DEFAULT");
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(), List.of(),
                List.of(), List.of(), Map.of(), 0, 0);
        List<Map<String, Object>> pins = GnomeQuickSettingsPins.build(config, snapshot, List.of(), List.of(),
                List.of(), graph, Map.of());
        assertEquals(GraphCommonVibro.VIBRO_MIXER_ID, pin(pins, GraphCommonVibro.VIBRO_MIXER_ID).get("nodeId"));
        assertEquals("other-mixer", pin(pins, "other-mixer").get("nodeId"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> children =
                (List<Map<String, Object>>) pin(pins, composite.id()).get("children");
        assertEquals(GraphCommonVibro.VIBRO_MIXER_ID, pin(children, GraphCommonVibro.VIBRO_MIXER_ID).get("nodeId"));
        assertEquals("test-gain", pin(children, "test-gain").get("nodeId"));
        assertEquals(3, graph.nodes().size());
        assertEquals(1, graph.connections().size());
    }
    @Test void commonMixerIsHiddenInSavedAndCompositePins() {
        Map<String, Object> config = DefaultConfig.create();
        AudioGraph graph = GraphCommonVibro.defaults();
        GraphCommonVibro.save(config, graph);
        QuickSettingsConfig.pinNode(config, graph.id(), GraphCommonVibro.VIBRO_MIXER_ID, "routing", 0, "DEFAULT");
        var composite = GraphSubgraphLibrary.create(config, graph, "Group",
                List.of(GraphCommonVibro.VIBRO_MIXER_ID, GraphCommonVibro.VIBRO_GAIN_ID));
        QuickSettingsConfig.pinNode(config, graph.id(), composite.id(), "routing", 1, "DEFAULT");
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(), List.of(),
                List.of(), List.of(), Map.of(), 0, 0);
        List<Map<String, Object>> pins = GnomeQuickSettingsPins.build(config, snapshot, List.of(), List.of(),
                List.of(), null, Map.of());
        assertTrue(pins.stream().noneMatch(p -> GraphCommonVibro.VIBRO_MIXER_ID.equals(p.get("nodeId"))));
        @SuppressWarnings("unchecked") List<Map<String, Object>> children =
                (List<Map<String, Object>>) pin(pins, composite.id()).get("children");
        assertTrue(children.stream().noneMatch(p -> GraphCommonVibro.VIBRO_MIXER_ID.equals(p.get("nodeId"))));
    }
    private static GraphConnection edge(String from, String to, String port) {
        return new GraphConnection("edge", from, "out", to, port, true, 1);
    }
    private static Map<String, Object> pin(List<Map<String, Object>> pins, String nodeId) {
        return pins.stream().filter(item -> nodeId.equals(item.get("nodeId"))).findFirst().orElseThrow();
    }
}
