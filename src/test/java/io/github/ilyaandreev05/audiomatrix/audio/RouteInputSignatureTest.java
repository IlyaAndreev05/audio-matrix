package io.github.ilyaandreev05.audiomatrix.audio;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
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
    void sinkDeviceIdentityAndChannelMapChangesAreDynamicInputs() {
        Map<String, Object> config = Map.of("active_route", "desktop", "engine", Map.of("apply_on_start", true));
        DeviceIdentity stereo = DeviceIdentity.from(Map.of(
                "device.bus-path", "pci-0000:00:01.0", "device.profile.name", "analog-stereo",
                "device.port.name", "analog-output-lineout", "audio.position", "FL FR"), 2);
        DeviceIdentity otherDevice = DeviceIdentity.from(Map.of(
                "device.bus-path", "pci-0000:00:02.0", "device.profile.name", "analog-stereo",
                "device.port.name", "analog-output-lineout", "audio.position", "FL FR"), 2);
        DeviceIdentity otherChannelMap = DeviceIdentity.from(Map.of(
                "device.bus-path", "pci-0000:00:01.0", "device.profile.name", "analog-stereo",
                "device.port.name", "analog-output-lineout", "audio.position", "FR FL"), 2);

        String original = signature(config, List.of(sink(69, "alsa_output.analog-stereo", stereo)), List.of());

        assertNotEquals(original, signature(config,
                List.of(sink(69, "alsa_output.analog-stereo", otherDevice)), List.of()));
        assertNotEquals(original, signature(config,
                List.of(sink(69, "alsa_output.analog-stereo", otherChannelMap)), List.of()));
    }


    @Test
    void stillChangesWhenAnExternalPlaybackStreamAppears() {
        Map<String, Object> config = Map.of("active_route", "desktop", "engine", Map.of("apply_on_start", true));
        List<AudioNode> sinks = List.of(sink(69, "alsa_output.hdmi-stereo"));
        String withoutApplication = signature(config, sinks, List.of());
        String withApplication = signature(config, sinks, List.of(stream(401, "Firefox", "org.mozilla.firefox")));

        assertNotEquals(withoutApplication, withApplication);
    }
    @Test
    void streamInstanceChurnChangesTheDispatchFingerprint() {
        Map<String, Object> config = Map.of("active_route", "desktop", "engine", Map.of("apply_on_start", true));
        List<AudioNode> sinks = List.of(sink(69, "alsa_output.hdmi-stereo"));
        String beforeRestart = signature(config, sinks,
                List.of(stream(401, "Firefox", "org.mozilla.firefox")));
        String afterRestart = signature(config, sinks,
                List.of(stream(902, "Firefox", "org.mozilla.firefox")));

        assertNotEquals(beforeRestart, afterRestart);
    }

    @Test
    void streamAvailabilityChangesTheDispatchFingerprint() {
        Map<String, Object> config = Map.of("active_route", "desktop", "engine", Map.of("apply_on_start", true));
        List<AudioNode> sinks = List.of(sink(69, "alsa_output.hdmi-stereo"));

        assertNotEquals(signature(config, sinks,
                        List.of(stream(401, "Firefox", "org.mozilla.firefox", "running")),
                        sinks.getFirst().id()),
                signature(config, sinks,
                        List.of(stream(401, "Firefox", "org.mozilla.firefox", "suspended")),
                        sinks.getFirst().id()));
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
    void stillChangesWhenAPhysicalOutputChanges() {
        Map<String, Object> config = Map.of("active_route", "desktop", "engine", Map.of("apply_on_start", true));
        String hdmi = signature(config, List.of(sink(69, "alsa_output.hdmi-stereo")), List.of());
        String usb = signature(config, List.of(sink(70, "alsa_output.usb-stereo")), List.of());

        assertNotEquals(hdmi, usb);
    }
    @Test
    void defaultOutputBindingChangesTheDispatchFingerprint() {
        Map<String, Object> config = Map.of("active_route", "desktop", "engine", Map.of("apply_on_start", true));
        List<AudioNode> sinks = List.of(
                sink(69, "alsa_output.hdmi-stereo"), sink(70, "alsa_output.usb-stereo"));

        assertNotEquals(signature(config, sinks, List.of(), 69),
                signature(config, sinks, List.of(), 70));
    }

    @Test
    void outputAvailabilityChangesTheDispatchFingerprint() {
        Map<String, Object> config = Map.of("active_route", "desktop", "engine", Map.of("apply_on_start", true));

        assertNotEquals(signature(config, List.of(sink(69, "alsa_output.hdmi-stereo", "running")), List.of()),
                signature(config, List.of(sink(69, "alsa_output.hdmi-stereo", "suspended")), List.of()));
    }


    @Test
    void changesWhenTheSharedVibroGraphChanges() {
        Map<String, Object> config = DefaultConfig.create();
        List<AudioNode> sinks = List.of(sink(69, "alsa_output.front"));
        String before = signature(config, sinks, List.of());
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes =
                (List<Map<String, Object>>) config.get("route_library");
        @SuppressWarnings("unchecked") List<Map<String, Object>> nodes =
                (List<Map<String, Object>>) routes.getFirst().get("nodes");
        Map<String, Object> gain = nodes.stream()
                .filter(node -> GraphCommonVibro.VIBRO_GAIN_ID.equals(node.get("id"))).findFirst().orElseThrow();
        Map<String, Object> parameters = new LinkedHashMap<>((Map<String, Object>) gain.get("parameters"));
        parameters.put("gain", 0.25);
        gain.put("parameters", parameters);

        assertNotEquals(before, signature(config, sinks, List.of()));
    }

    private static String signature(Map<String, Object> config, List<AudioNode> sinks, List<AudioNode> streams) {
        return signature(config, sinks, streams, sinks.isEmpty() ? -1 : sinks.getFirst().id());
    }

    private static String signature(Map<String, Object> config, List<AudioNode> sinks, List<AudioNode> streams,
                                    int defaultSinkId) {
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", sinks, List.of(), streams,
                List.of(), Map.of(), defaultSinkId, 0);
        return RouteInputSignature.create(config, snapshot, 1);
    }

    private static AudioNode sink(int id, String name) {
        return sink(id, name, "running", DeviceIdentity.from(Map.of(), 2));
    }

    private static AudioNode sink(int id, String name, String state) {
        return sink(id, name, state, DeviceIdentity.from(Map.of(), 2));
    }

    private static AudioNode sink(int id, String name, DeviceIdentity identity) {
        return sink(id, name, "running", identity);
    }

    private static AudioNode sink(int id, String name, String state, DeviceIdentity identity) {
        return new AudioNode(id, name, name, "Audio/Sink", state, "", "", "", 2,
                List.of(48000), 1.0, false, null, identity);
    }

    private static AudioNode stream(int id, String name, String applicationId) {
        return stream(id, name, applicationId, "running");
    }

    private static AudioNode stream(int id, String name, String applicationId, String state) {
        return new AudioNode(id, name, name, "Stream/Output/Audio", state, name, "pw-loopback",
                applicationId, 2, List.of(48000), 1.0, false, null);
    }
}
