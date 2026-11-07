package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.List;
import java.util.Map;

/** Snapshots, nodes and config sections shared by the route input signature tests. */
abstract class RouteInputSignatureFixture {
    static Map<String, Object> section(Map<String, Object> config, String key) {
        @SuppressWarnings("unchecked") Map<String, Object> section = (Map<String, Object>) config.get(key);
        return section;
    }

    static String signature(Map<String, Object> config, List<AudioNode> sinks, List<AudioNode> streams) {
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", sinks, List.of(), streams,
                List.of(), Map.of(), sinks.isEmpty() ? -1 : sinks.getFirst().id(), 0);
        return RouteInputSignature.create(config, snapshot, 1);
    }

    static PipeWireBackend.Snapshot snapshot(List<AudioNode> sinks, List<AudioNode> streams) {
        return snapshot(sinks, List.of(), streams);
    }

    static PipeWireBackend.Snapshot snapshot(List<AudioNode> sinks, List<AudioNode> sources,
                                             List<AudioNode> streams) {
        return new PipeWireBackend.Snapshot(true, "", sinks, sources, streams,
                List.of(), Map.of(), sinks.isEmpty() ? -1 : sinks.getFirst().id(), 0);
    }

    static AudioNode sinkWithIdentity(int id, String name) {
        DeviceIdentity identity = DeviceIdentity.from(Map.of(
                "device.bus-path", "pci-0000:00:01.0", "device.profile.name", "hdmi-stereo",
                "device.port.name", "hdmi-output-1", "audio.position", "[FL FR]"), 2);
        return new AudioNode(id, name, name, "Audio/Sink", "running", "", "", "", 2,
                List.of(48000), 1.0, false, null, identity);
    }

    static AudioNode sink(int id, String name) {
        return new AudioNode(id, name, name, "Audio/Sink", "running", "", "", "", 2,
                List.of(48000), 1.0, false, null);
    }

    static AudioNode eventInput(int id) {
        return EventInputMonitor.sourceView(sink(id, EventInputMonitor.NODE_NAME));
    }

    static AudioNode stream(int id, String name, String applicationId) {
        return stream(id, name, applicationId, "pw-loopback");
    }

    static AudioNode stream(int id, String name, String applicationId, String processName) {
        return new AudioNode(id, name, name, "Stream/Output/Audio", "running", name, processName,
                applicationId, 2, List.of(48000), 1.0, false, null);
    }
}
