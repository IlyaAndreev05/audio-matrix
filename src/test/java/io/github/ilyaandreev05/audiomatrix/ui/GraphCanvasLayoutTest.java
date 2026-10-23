package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.EventSoundStudioSource;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import io.github.ilyaandreev05.audiomatrix.graph.SwitchNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphCanvasLayoutTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test void arrangesSourceVolumeMergeMultiSwitchOutputVolumeAndOutputInColumns() {
        SourceNode source = new SourceNode("source", "Application", "application:app", STEREO);
        GainNode sourceVolume = new GainNode("source-volume", "Application volume", STEREO, 1, false, source.id());
        MixerNode merge = new MixerNode("merge", "Merge", STEREO, Map.of("app", 1.0, "ess", 1.0));
        SwitchNode multiSwitch = new SwitchNode("multi-switch", "MultiSwitch", STEREO, List.of("monitor"), List.of());
        OutputNode output = new OutputNode("output", "Monitor", "monitor-device", STEREO, null);
        GainNode outputVolume = new GainNode("output-volume", "Output volume", STEREO, 1, false, output.id());
        AudioGraph graph = new AudioGraph("route", "Route",
                List.of(source, sourceVolume, merge, multiSwitch, outputVolume, output), List.of(
                edge("source-volume-link", source.id(), "out", sourceVolume.id(), "in"),
                edge("volume-merge-link", sourceVolume.id(), "out", merge.id(), "app"),
                edge("merge-switch-link", merge.id(), "out", multiSwitch.id(), "in"),
                edge("switch-volume-link", multiSwitch.id(), "out", outputVolume.id(), "in"),
                edge("volume-output-link", outputVolume.id(), "out", output.id(), "in")));

        GraphCanvasLayout.Layout layout = GraphCanvasLayout.arrange(graph, ignored -> 80);

        assertEquals(24, layout.positions().get("source").x());
        assertEquals(324, layout.positions().get("source-volume").x());
        assertEquals(624, layout.positions().get("merge").x());
        assertEquals(924, layout.positions().get("multi-switch").x());
        assertEquals(1224, layout.positions().get("output-volume").x());
        assertEquals(1524, layout.positions().get("output").x());
        assertTrue(layout.width() > layout.positions().get("output").x() + GraphCanvasLayout.NODE_WIDTH);
    }

    @Test void putsEventSoundStudioOnItsOwnLowerLaneAndTreatsDeviceSlotAsOutput() {
        SourceNode app = new SourceNode("app", "Application", "application:app", STEREO);
        SourceNode ess = new SourceNode("ess", "Event Sound Studio", EventSoundStudioSource.SELECTOR, STEREO);
        MixerNode merge = new MixerNode("merge", "Haptic merge", STEREO, Map.of("app", 1.0, "ess", 1.0));
        DeviceSlotNode slot = new DeviceSlotNode("slot", "Output slot", "", STEREO);
        AudioGraph graph = new AudioGraph("route", "Route", List.of(app, ess, merge, slot), List.of(
                edge("app-merge", app.id(), "out", merge.id(), "app"),
                edge("ess-merge", ess.id(), "out", merge.id(), "ess"),
                edge("merge-slot", merge.id(), "out", slot.id(), "in")));

        GraphCanvasLayout.Layout layout = GraphCanvasLayout.arrange(graph, ignored -> 80);

        assertTrue(layout.positions().get("ess").y() > layout.positions().get("app").y());
        assertTrue(layout.positions().get("merge").y() > layout.positions().get("app").y());
        assertEquals(1524, layout.positions().get("slot").x());
        assertEquals("Выход", GraphNodeLabels.typeName(slot, (russian, english) -> russian));
    }

    @Test void usesRussianFriendlyLabelsForInternalRoutingNodes() {
        MixerNode merge = new MixerNode("merge", "Merge", STEREO, Map.of("one", 1.0, "two", 1.0));
        SwitchNode multiSwitch = new SwitchNode("switch", "MultiSwitch", STEREO, List.of("out"), List.of());

        assertEquals("Сумматор", GraphNodeLabels.typeName(merge, (russian, english) -> russian));
        assertEquals("Распределитель звука", GraphNodeLabels.typeName(multiSwitch, (russian, english) -> russian));
    }
    private static GraphConnection edge(String id, String from, String fromPort, String to, String toPort) {
        return new GraphConnection(id, from, fromPort, to, toPort, true, 1.0);
    }
}
