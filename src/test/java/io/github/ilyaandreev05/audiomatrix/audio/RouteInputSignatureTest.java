package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

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
    void stillChangesWhenAnExternalPlaybackStreamAppears() {
        Map<String, Object> config = Map.of("active_route", "desktop", "engine", Map.of("apply_on_start", true));
        List<AudioNode> sinks = List.of(sink(69, "alsa_output.hdmi-stereo"));
        String withoutApplication = signature(config, sinks, List.of());
        String withApplication = signature(config, sinks, List.of(stream(401, "Firefox", "org.mozilla.firefox")));

        assertNotEquals(withoutApplication, withApplication);
    }

    @Test
    void stillChangesWhenAPhysicalOutputChanges() {
        Map<String, Object> config = Map.of("active_route", "desktop", "engine", Map.of("apply_on_start", true));
        String hdmi = signature(config, List.of(sink(69, "alsa_output.hdmi-stereo")), List.of());
        String usb = signature(config, List.of(sink(70, "alsa_output.usb-stereo")), List.of());

        assertNotEquals(hdmi, usb);
    }

    private static String signature(Map<String, Object> config, List<AudioNode> sinks, List<AudioNode> streams) {
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", sinks, List.of(), streams,
                List.of(), Map.of(), 69, 0);
        return RouteInputSignature.create(config, snapshot, 1);
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
