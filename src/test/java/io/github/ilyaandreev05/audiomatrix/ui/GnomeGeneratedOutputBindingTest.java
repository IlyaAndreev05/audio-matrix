package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.audio.PortAvailability;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GnomeGeneratedOutputBindingTest {
    @Test void rejectsMissingIgnoredConflictingAndOfflineBindingsWithoutMutation() {
        Map<String, Object> config = DefaultConfig.create();
        var generator = GeneratedOutputTestSupport.generator();
        var graph = GeneratedOutputTestSupport.replaceGenerator(config, generator);
        DeviceIdentity identity = DeviceIdentity.from(Map.of("device.serial", "safe-output",
                "device.profile.name", "analog-stereo", "device.port.name", "front-output",
                "audio.position", "FL,FR"), 2);
        AudioNode output = new AudioNode(41, "alsa_output.live", "Live output", "Audio/Sink",
                "running", "", "", "", 2, List.of(48000), 0.6, false, null, identity);
        var registry = DynamicInstanceRegistry.reconcile(graph,
                Map.of(generator.id(), List.of(DynamicInstanceRegistry.output(output))));
        var target = new GnomeNodeCommandHandler.Target("main", generator.id(), "device:" + identity.fingerprint());
        var snapshot = new PipeWireBackend.Snapshot(true, "", List.of(output), List.of(),
                List.of(), List.of(), Map.of(), output.id(), System.currentTimeMillis());
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices =
                (List<Map<String, Object>>) config.get("devices");
        Map<String, Object> saved = DefaultConfig.map("id", "saved-id", "identity", identity.toMap(),
                "volume", 0.62, "mute", true);
        assertThrows(IllegalArgumentException.class, () -> GnomeGraphNodeTargetResolver.resolve(
                config, target, "volume", registry, snapshot, List.of(output)));
        saved.put("ignored", true);
        devices.add(saved);
        assertThrows(IllegalArgumentException.class, () -> GnomeGraphNodeTargetResolver.resolve(
                config, target, "volume", registry, snapshot, List.of(output)));
        saved.put("ignored", false);
        devices.add(DefaultConfig.map("id", "conflict", "identity", identity.toMap(), "volume", 0.9));
        assertThrows(IllegalArgumentException.class, () -> GnomeGraphNodeTargetResolver.resolve(
                config, target, "volume", registry, snapshot, List.of(output)));
        devices.removeLast();
        assertThrows(IllegalArgumentException.class, () -> GnomeGraphNodeTargetResolver.resolve(
                config, target, "volume", registry, PipeWireBackend.Snapshot.offline("offline"), List.of()));
        AudioNode unavailable = new AudioNode(41, output.name(), output.description(), "Audio/Sink",
                "running", "", "", "", 2, List.of(48000), 0.6, false, null, identity, PortAvailability.UNAVAILABLE);
        assertThrows(IllegalArgumentException.class, () -> GnomeGraphNodeTargetResolver.resolve(
                config, target, "volume", registry, snapshot, List.of(unavailable)));
        assertEquals(0.62, ((Number) saved.get("volume")).doubleValue(), 0.0001);
        assertEquals(true, saved.get("mute"));
        assertEquals("saved-id", GnomeGraphNodeTargetResolver.resolve(
                config, target, "volume", registry, snapshot, List.of(output)).bindingId());
    }
}
