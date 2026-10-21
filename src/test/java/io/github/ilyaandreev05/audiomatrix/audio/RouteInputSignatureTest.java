package io.github.ilyaandreev05.audiomatrix.audio;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RouteInputSignatureTest {
    @Test
    void ignoresAudioMatrixOwnedNodesThatAreRecreatedWhenTheGraphIsApplied() {
        Map<String, Object> config = Map.of("active_route", "desktop", "engine", Map.of("apply_on_start", true));
        AudioNode physicalSink = sink(69, "alsa_output.hdmi-stereo");
        AudioNode systemSink = sink(32, "audiomatrix_system");
        AudioNode routePlayback = stream(107, "audiomatrix_route_abc_playback", "");

        String first = signature(config, List.of(physicalSink, systemSink), List.of(routePlayback));
        String afterRebuild = signature(config, List.of(physicalSink, sink(202, "audiomatrix_system")),
                List.of(stream(318, "audiomatrix_route_abc_playback", "")));

        assertEquals(first, afterRebuild);
    }

    @Test
    void transientSinkIdChurnDoesNotForceAGraphRebuild() {
        Map<String, Object> config = Map.of("active_route", "desktop", "engine", Map.of("apply_on_start", true));
        String before = signature(config, List.of(sink(69, "alsa_output.hdmi-stereo")), List.of());
        String afterReconnect = signature(config, List.of(sink(777, "alsa_output.hdmi-stereo")), List.of());

        assertEquals(before, afterReconnect);
    }

    @Test
    void streamOnlyChangesKeepStaticTopologySignatureAndUseDynamicBindingKey() {
        Map<String, Object> config = Map.of("active_route", "desktop", "engine", Map.of("apply_on_start", true));
        List<AudioNode> sinks = List.of(sink(69, "alsa_output.hdmi-stereo"));
        AudioNode before = stream(401, "Firefox", "org.mozilla.firefox");
        AudioNode recreated = stream(402, "Firefox", "org.mozilla.firefox");
        PipeWireBackend.Snapshot first = snapshot(sinks, List.of(before));
        PipeWireBackend.Snapshot next = snapshot(sinks, List.of(recreated));

        assertEquals(RouteInputSignature.create(config, first, 1), RouteInputSignature.create(config, next, 1));
        assertNotEquals(RouteInputSignature.dynamicBindings(first), RouteInputSignature.dynamicBindings(next));

        AudioNode presentationChanged = new AudioNode(before.id(), before.name(), "A new tab title",
                before.mediaClass(), "suspended", "Renamed application", before.processName(), before.applicationId(),
                before.channels(), before.sampleRates(), 0.25, true, before.pulseId(), before.identity());
        assertEquals(RouteInputSignature.dynamicBindings(first),
                RouteInputSignature.dynamicBindings(snapshot(sinks, List.of(presentationChanged))));
    }

    @Test
    void changesWhenAnOutputPortIdentityChanges() {
        Map<String, Object> config = Map.of("active_route", "desktop", "engine", Map.of("apply_on_start", true));
        AudioNode first = sinkWithIdentity(69, "alsa_output.card");
        AudioNode changedPort = new AudioNode(70, first.name(), first.description(), first.mediaClass(), first.state(),
                first.applicationName(), first.processName(), first.applicationId(), first.channels(), first.sampleRates(),
                first.volume(), first.muted(), first.pulseId(), DeviceIdentity.from(Map.of(
                        "device.bus-path", "pci-0000:00:01.0", "device.profile.name", "hdmi-stereo",
                        "device.port.name", "hdmi-output-2", "audio.position", "[FL FR]"), 2));

        assertNotEquals(RouteInputSignature.create(config, snapshot(List.of(first), List.of()), 1),
                RouteInputSignature.create(config, snapshot(List.of(changedPort), List.of()), 1));
    }

    @Test
    void retriesWhenTheIsolatedEventSinkAppearsAfterTheRoute(){
        Map<String,Object> config=Map.of("active_route","desktop","engine",Map.of("apply_on_start",true));
        List<AudioNode> sinks=List.of(sink(69,"alsa_output.hdmi-stereo"));
        PipeWireBackend.Snapshot withoutEventInput=new PipeWireBackend.Snapshot(true,"",sinks,List.of(),List.of(),
                List.of(),Map.of(),69,0);
        AudioNode eventInput=EventInputMonitor.sourceView(sink(203,EventInputMonitor.NODE_NAME));
        PipeWireBackend.Snapshot withEventInput=new PipeWireBackend.Snapshot(true,"",sinks,List.of(eventInput),List.of(),
                List.of(),Map.of(),69,1);

        assertNotEquals(RouteInputSignature.create(config,withoutEventInput,1),
                RouteInputSignature.create(config,withEventInput,1));
    }


    @Test
    void keepsStaticTopologyWhenSharedGainLevelChanges() {
        Map<String, Object> config = DefaultConfig.create();
        List<AudioNode> sinks = List.of(sink(69, "alsa_output.front"));
        String before = signature(config, sinks, List.of());
        String levelsBefore = RouteInputSignature.dynamicLevels(config);
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes =
                (List<Map<String, Object>>) config.get("route_library");
        @SuppressWarnings("unchecked") List<Map<String, Object>> nodes =
                (List<Map<String, Object>>) routes.getFirst().get("nodes");
        Map<String, Object> gain = nodes.stream()
                .filter(node -> "octine-vibro-gain".equals(node.get("id"))).findFirst().orElseThrow();
        Map<String, Object> parameters = new LinkedHashMap<>((Map<String, Object>) gain.get("parameters"));
        parameters.put("gain", 0.25);
        gain.put("parameters", parameters);

        assertEquals(before, signature(config, sinks, List.of()));
        assertNotEquals(levelsBefore, RouteInputSignature.dynamicLevels(config));
    }

    private static String signature(Map<String, Object> config, List<AudioNode> sinks, List<AudioNode> streams) {
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", sinks, List.of(), streams,
                List.of(), Map.of(), sinks.isEmpty() ? -1 : sinks.getFirst().id(), 0);
        return RouteInputSignature.create(config, snapshot, 1);
    }
    private static PipeWireBackend.Snapshot snapshot(List<AudioNode> sinks, List<AudioNode> streams) {
        return new PipeWireBackend.Snapshot(true, "", sinks, List.of(), streams,
                List.of(), Map.of(), sinks.isEmpty() ? -1 : sinks.getFirst().id(), 0);
    }

    private static AudioNode sinkWithIdentity(int id, String name) {
        DeviceIdentity identity = DeviceIdentity.from(Map.of(
                "device.bus-path", "pci-0000:00:01.0", "device.profile.name", "hdmi-stereo",
                "device.port.name", "hdmi-output-1", "audio.position", "[FL FR]"), 2);
        return new AudioNode(id, name, name, "Audio/Sink", "running", "", "", "", 2,
                List.of(48000), 1.0, false, null, identity);
    }

    private static AudioNode sink(int id, String name) {
        return new AudioNode(id, name, name, "Audio/Sink", "running", "", "", "", 2,
                List.of(48000), 1.0, false, null);
    }

    private static AudioNode stream(int id, String name, String applicationId) {
        return new AudioNode(id, name, name, "Stream/Output/Audio", "running", name, "pw-loopback",
                applicationId, 2, List.of(48000), 1.0, false, null);
    }
}
