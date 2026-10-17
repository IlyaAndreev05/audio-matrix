package io.github.ilyaandreev05.audiomatrix.audio;
import org.junit.jupiter.api.Test;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphPipeWireCompiler;
import io.github.ilyaandreev05.audiomatrix.graph.SwitchNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class PipeWireRouteManagerTestSupport {
    protected static Map<String, Object> chainedBusConfig(boolean masterMuted) {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> buses = (List<Map<String, Object>>) config.get("buses");
        buses.stream().filter(bus -> "main".equals(bus.get("id"))).findFirst().orElseThrow().put("volume", 0.8);
        buses.stream().filter(bus -> "effects".equals(bus.get("id"))).findFirst().orElseThrow().put("volume", 0.6);
        @SuppressWarnings("unchecked") Map<String, Object> mixer = (Map<String, Object>) config.get("mixer");
        mixer.put("global_volume", 0.5);
        mixer.put("global_mute", masterMuted);
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", "speaker", "selector", DefaultConfig.map("kind", "name_equals", "value", "test-output"),
                "role", "output", "volume", 1.0, "mute", false));
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "main-to-effects", "from", "main", "to", "effects", "enabled", true));
        routes.add(DefaultConfig.map("id", "effects-to-output", "from", "effects", "to", "speaker", "enabled", true));
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("active_buses", List.of("main", "effects"));
        profiles.getFirst().put("active_routes", List.of("main-to-effects", "effects-to-output"));
        return config;
    }

    protected static AudioNode sink(int id, String name, String description) {
        return new AudioNode(id, name, description, "Audio/Sink", "running", "", "", "", 2,
                List.of(48000), 1.0, false, null);
    }

    protected static PipeWireBackend.Snapshot snapshot(List<AudioNode> sinks, int defaultSinkId) {
        return new PipeWireBackend.Snapshot(true, "", sinks, List.of(), List.of(), List.of(), Map.of(), defaultSinkId,
                System.currentTimeMillis());
    }

    protected static PipeWireBackend.Snapshot snapshotWithStreams(List<AudioNode> sinks, List<AudioNode> streams, int defaultSinkId) {
        return new PipeWireBackend.Snapshot(true, "", sinks, List.of(), streams, List.of(), Map.of(), defaultSinkId,
                System.currentTimeMillis());
    }
}
