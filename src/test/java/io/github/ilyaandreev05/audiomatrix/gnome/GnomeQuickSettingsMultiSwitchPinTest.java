package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import io.github.ilyaandreev05.audiomatrix.graph.SwitchNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GnomeQuickSettingsMultiSwitchPinTest {
    @Test void pinnedMultiSwitchPublishesExactlyItsActiveLinkIds() {
        Map<String, Object> config = DefaultConfig.create();
        List<String> channels = List.of("FL", "FR");
        SourceNode source = new SourceNode("music", "Music", "application:music", channels);
        SwitchNode multi = new SwitchNode("multi", "MultiSwitch", channels, List.of("monitor"), List.of("in->monitor"));
        OutputNode output = new OutputNode("monitor", "Monitor", "device:monitor", channels);
        AudioGraph graph = new AudioGraph("route-multi", "Music routing", List.of(source, multi, output), List.of(
                new GraphConnection("to-switch", source.id(), "out", multi.id(), "in", true, 1),
                new GraphConnection("from-switch", multi.id(), "monitor", output.id(), "in", true, 1)));
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphRouteLibrary.activate(config, graph.id());
        QuickSettingsConfig.pinNode(config, graph.id(), multi.id(), "routing", 0, "EXPANDED");

        List<Map<String, Object>> pins = GnomeQuickSettingsPins.build(config,
                PipeWireBackend.Snapshot.offline("disconnected"), List.of(), List.of(), List.of(), null, Map.of());
        Map<?, ?> routeControl = (Map<?, ?>) pin(pins, graph.id(), multi.id()).get("routeControl");
        List<?> options = (List<?>) routeControl.get("options");
        List<String> optionIds = options.stream().map(option -> String.valueOf(((Map<?, ?>) option).get("id"))).toList();

        assertEquals(multi.activeLinks(), routeControl.get("value"));
        assertEquals(multi.activeLinks(), optionIds);
        assertEquals("switch", routeControl.get("nodeType"));
    }

    private static Map<String, Object> pin(List<Map<String, Object>> pins, String routeId, String nodeId) {
        return pins.stream().filter(item -> routeId.equals(item.get("routeId"))
                && nodeId.equals(item.get("nodeId"))).findFirst().orElseThrow();
    }
}
