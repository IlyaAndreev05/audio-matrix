package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DeviceDiscoveryTest {
    private final DeviceIdentity identity = DeviceIdentity.from(Map.of("device.bus-path", "pci-usb-1", "device.serial", "DAC-42",
            "device.profile.name", "analog-stereo", "device.port.name", "headphones", "audio.position", "FL,FR"), 2);
    private AudioNode node(int id, DeviceIdentity hardware) {
        return new AudioNode(id, "alsa_output.DAC", "DAC", "Audio/Sink", "running", "", "", "", 2,
                List.of(48000), 1, false, null, hardware);
    }
    private Map<String, Object> binding(String id) { return Map.of("id", id, "identity", identity.toMap()); }

    @Test void discoveryIsNotBindingAndReconnectIgnoresTransientNodeId() {
        AudioNode first = node(10, identity), reconnect = node(999, identity);
        assertEquals(DeviceDiscovery.State.NEW, DeviceDiscovery.catalog(List.of(first), List.of()).getFirst().state());
        Map<String, Object> config = new LinkedHashMap<>();
        DeviceDiscovery.bind(config, "usb-slot", first);
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) config.get("devices");
        assertEquals(false, devices.getFirst().get("selected"));
        assertEquals(DeviceDiscovery.State.OFFLINE, DeviceDiscovery.resolve(List.of(), devices.getFirst()).state());
        assertSame(reconnect, DeviceSelectorMatcher.resolve(List.of(reconnect), "usb-slot", devices));
        assertEquals(identity, DeviceIdentity.fromMap(devices.getFirst().get("identity") instanceof Map<?, ?> map
                ? map.entrySet().stream().collect(java.util.stream.Collectors.toMap(e -> e.getKey().toString(), Map.Entry::getValue)) : Map.of()));
    }

    @Test void identityCollisionsAndLowConfidenceNeverSelectAnOutput() {
        assertEquals(DeviceDiscovery.State.AMBIGUOUS, DeviceDiscovery.resolve(List.of(node(1, identity), node(2, identity)), binding("slot")).state());
        DeviceIdentity weak = DeviceIdentity.from(Map.of("object.id", "7", "object.path", "alsa:pcm:0"), 2);
        assertFalse(weak.reliable());
        assertThrows(IllegalArgumentException.class, () -> DeviceDiscovery.bind(new LinkedHashMap<>(), "slot", node(1, weak)));
        assertNull(DeviceSelectorMatcher.resolve(List.of(node(1, identity), node(2, identity)), "slot", List.of(binding("slot"))));
    }

    @Test void changedProfilePortOrChannelsRequiresExplicitRebinding() {
        for (DeviceIdentity changed : List.of(
                DeviceIdentity.from(Map.of("device.bus-path", "pci-usb-1", "device.serial", "DAC-42", "device.profile.name", "surround", "device.port.name", "headphones", "audio.position", "FL,FR"), 2),
                DeviceIdentity.from(Map.of("device.bus-path", "pci-usb-1", "device.serial", "DAC-42", "device.profile.name", "analog-stereo", "device.port.name", "lineout", "audio.position", "FL,FR"), 2),
                DeviceIdentity.from(Map.of("device.bus-path", "pci-usb-1", "device.serial", "DAC-42", "device.profile.name", "analog-stereo", "device.port.name", "headphones", "audio.position", "RL,RR"), 2),
                DeviceIdentity.from(Map.of("device.bus-path", "pci-usb-1", "device.serial", "DAC-42", "device.profile.name", "analog-stereo", "device.port.name", "headphones", "audio.position", "FL,FR,FC,LFE,RL,RR"), 6))) {
            assertEquals(DeviceDiscovery.State.OFFLINE, DeviceDiscovery.resolve(List.of(node(1, changed)), binding("slot")).state());
        }
    }

    @Test void vibroAndSecondIndependentSlotCannotSharePhysicalOutput() {
        Map<String, Object> vibro = new LinkedHashMap<>(binding("vibro")); vibro.put("role", "haptic_output");
        Map<String, Object> config = new LinkedHashMap<>(); config.put("devices", List.of(vibro));
        assertThrows(IllegalArgumentException.class, () -> DeviceDiscovery.bind(config, "music", node(1, identity)));
        List<Map<String, Object>> invalid = List.of(vibro, binding("music"));
        assertNull(DeviceSelectorMatcher.resolve(List.of(node(1, identity)), "music", invalid));
        assertTrue(DeviceDiscovery.catalog(List.of(node(1, identity)), invalid).stream().allMatch(entry -> entry.state() == DeviceDiscovery.State.CONFLICT));
    }

    @Test void explicitIdentityDoesNotFallBackToStaleSelectorOrDefault() {
        Map<String, Object> device = new LinkedHashMap<>(binding("slot"));
        device.put("selector", Map.of("kind", "name_equals", "value", "alsa_output.DAC"));
        AudioNode unrelated = node(1, DeviceIdentity.from(Map.of("device.serial", "other"), 2));
        assertNull(DeviceSelectorMatcher.resolve(List.of(unrelated), "slot", List.of(device)));
        assertNull(DeviceSelectorMatcher.resolve(List.of(unrelated), "system_default", List.of(device)));
    }

    @Test void activeCardProfileAndPortAreReadFromPhysicalDevice() throws Exception {
        var json = new ObjectMapper();
        var root = json.readTree("""
                [{"id":20,"info":{"props":{"device.bus-path":"pci-42","device.serial":"S42"},
                "params":{"Profile":[{"name":"analog-stereo"}],"Route":[{"device":0,"name":"analog-output-headphones"}]}}}]
                """);
        var props = json.readTree("{\"device.id\":20,\"card.profile.device\":0,\"audio.position\":[\"FL\",\"FR\"]}");
        var found = DeviceIdentity.from(DeviceProperties.read(root, props), 2);
        assertEquals("analog-stereo", found.cardProfile()); assertEquals("analog-output-headphones", found.port());
        assertEquals("pci-42", found.physicalPath()); assertTrue(found.reliable());
    }
}
