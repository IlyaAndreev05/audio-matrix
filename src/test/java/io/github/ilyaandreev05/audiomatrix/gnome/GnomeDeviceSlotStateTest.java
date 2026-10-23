package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GnomeDeviceSlotStateTest {
    private static final List<String> STEREO = List.of("FL", "FR");
    private final DeviceIdentity identity = DeviceIdentity.from(Map.of(
            "device.serial", "front-aux", "device.profile.name", "analog-stereo",
            "device.port.name", "front-output", "audio.position", "FL,FR"), 2);
    private final AudioNode sink = new AudioNode(41, "alsa_output.front", "Front AUX", "Audio/Sink",
            "running", "", "", "", 2, List.of(48000), 1.0, false, null, identity);

    @Test void unboundSlotIsSilentAndReportsNoDevice() {
        Map<String, Object> state = GnomeDeviceSlotState.describe(
                new DeviceSlotNode("slot", "New device", "", STEREO),
                PipeWireBackend.Snapshot.offline("disconnected"), DefaultConfig.create());

        assertEquals("UNBOUND", state.get("state"));
        assertEquals(false, state.get("available"));
    }

    @Test void resolvedSlotBecomesOnlineWhenItsStableIdentityAppears() {
        Map<String, Object> config = DefaultConfig.create();
        config.put("devices", List.of(binding("slot")));

        Map<String, Object> state = GnomeDeviceSlotState.describe(
                new DeviceSlotNode("slot", "USB DAC", "slot", STEREO), snapshot(), config);

        assertEquals("ONLINE", state.get("state"));
        assertEquals(true, state.get("available"));
    }

    @Test void secondAssignmentOfTheSameSinkReportsConflictInsteadOfRouting() {
        Map<String, Object> config = DefaultConfig.create();
        config.put("devices", List.of(binding("vibro"), binding("music")));

        Map<String, Object> state = GnomeDeviceSlotState.describe(
                new DeviceSlotNode("slot", "Vibro", "vibro", STEREO), snapshot(), config);

        assertEquals("CONFLICT", state.get("state"));
        assertEquals(false, state.get("available"));
    }

    private Map<String, Object> binding(String id) {
        return DefaultConfig.map("id", id, "kind", "device_slot", "identity", identity.toMap(),
                "volume", 1.0, "mute", false, "selected", false);
    }

    private PipeWireBackend.Snapshot snapshot() {
        return new PipeWireBackend.Snapshot(true, "", List.of(sink), List.of(), List.of(), List.of(),
                Map.of(), sink.id(), System.currentTimeMillis());
    }
}
