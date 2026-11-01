package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.audio.PortAvailability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GnomeOutputStateAvailabilityTest {
    @Test
    void hiddenPhysicalOutputCannotReturnThroughRawNameWhileUnknownAndSuspendedRemainVisible() {
        AudioNode unavailable = sink(10, "alsa_output.unavailable", "running", PortAvailability.UNAVAILABLE);
        AudioNode unknown = sink(11, "alsa_output.unknown", "running", PortAvailability.UNKNOWN);
        AudioNode suspended = sink(12, "alsa_output.suspended", "suspended", PortAvailability.UNKNOWN);
        AudioNode systemMaster = sink(13, PipeWireBackend.SYSTEM_MIXER_SINK_NAME,
                "running", PortAvailability.UNKNOWN);
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "",
                List.of(unavailable, unknown, suspended, systemMaster), List.of(), List.of(), List.of(), Map.of(),
                systemMaster.id(), 0);

        List<Map<String, Object>> outputs = GnomeOutputState.devices(snapshot, List.of());
        Set<String> nodeNames = outputs.stream().map(output -> String.valueOf(output.get("nodeName")))
                .collect(Collectors.toSet());

        assertEquals(Set.of(unknown.name(), suspended.name()), nodeNames);
        assertNull(GnomeOutputState.resolve(List.of(unavailable), "name:" + unavailable.name(), List.of()));
        assertNull(GnomeOutputState.resolve(List.of(unavailable), unavailable.name(), List.of()));
        assertSame(unknown, GnomeOutputState.resolve(List.of(unknown), "name:" + unknown.name(), List.of()));
        assertTrue(outputs.stream().allMatch(output -> Boolean.TRUE.equals(output.get("available"))));
    }

    private static AudioNode sink(int id, String name, String state, PortAvailability availability) {
        return new AudioNode(id, name, name, "Audio/Sink", state, "", "", "", 2, List.of(48000),
                1.0, false, null, DeviceIdentity.from(Map.of(), 2), availability);
    }
}
