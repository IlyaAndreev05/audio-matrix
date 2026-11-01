package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PipeWirePortAvailabilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void activeRouteAvailabilityIsNormalizedAndExcludedFromHardwareIdentity() throws Exception {
        JsonNode availableRoot = root("""
                [{"device":0,"name":"analog-output-headphones","available":"yes"}]
                """, "[]");
        JsonNode unavailableRoot = root("""
                [{"device":0,"name":"analog-output-headphones","available":"no"}]
                """, "[{\"device\":0,\"name\":\"analog-output-headphones\",\"available\":\"yes\"}]");

        Map<String, String> properties = DeviceProperties.read(availableRoot,
                availableRoot.path(1).path("info").path("props"));
        AudioNode available = snapshotSink(availableRoot);
        AudioNode unavailable = snapshotSink(unavailableRoot);

        assertEquals("yes", properties.get(DeviceProperties.PORT_AVAILABILITY),
                "the normalized route value must override a raw node property");
        assertEquals(PortAvailability.AVAILABLE, available.portAvailability());
        assertTrue(available.outputAvailable());
        assertEquals(PortAvailability.UNAVAILABLE, unavailable.portAvailability(),
                "the active Route takes precedence over EnumRoute");
        assertFalse(unavailable.outputAvailable());
        assertEquals(available.identity().fingerprint(), unavailable.identity().fingerprint(),
                "availability must not perturb the persistent hardware identity");
    }

    @Test
    void missingOrUnrecognizedAvailabilityFallsBackOnlyToOneCompatibleEnumRoute() throws Exception {
        JsonNode fallback = root("""
                [{"device":0,"name":"analog-output-headphones"}]
                """, """
                [{"device":0,"name":"analog-output-headphones","available":"yes"}]
                """);
        JsonNode noFallback = root("""
                [{"device":0,"name":"analog-output-headphones"}]
                """, "[]");
        JsonNode ambiguousFallback = root("""
                [{"device":0,"name":"analog-output-headphones"}]
                """, """
                [{"device":0,"name":"analog-output-headphones","available":"yes"},
                 {"device":0,"name":"analog-output-headphones","available":"no"}]
                """);
        JsonNode incompatibleFallback = root("""
                [{"device":0,"name":"analog-output-headphones"}]
                """, """
                [{"device":1,"name":"analog-output-headphones","available":"yes"}]
                """);
        JsonNode unrecognized = root("""
                [{"device":0,"name":"analog-output-headphones","available":"plugged"}]
                """, """
                [{"device":0,"name":"analog-output-headphones","available":"yes"}]
                """);
        JsonNode missingRoute = root("[]", """
                [{"device":0,"name":"analog-output-headphones","available":"yes"}]
                """);

        assertEquals("yes", DeviceProperties.read(fallback, fallback.path(1).path("info").path("props"))
                .get(DeviceProperties.PORT_AVAILABILITY));
        assertEquals("unknown", DeviceProperties.read(noFallback, noFallback.path(1).path("info").path("props"))
                .get(DeviceProperties.PORT_AVAILABILITY));
        assertEquals(PortAvailability.AVAILABLE, snapshotSink(fallback).portAvailability());
        assertEquals(PortAvailability.UNKNOWN, snapshotSink(noFallback).portAvailability());
        assertEquals(PortAvailability.UNKNOWN, snapshotSink(ambiguousFallback).portAvailability());
        assertEquals(PortAvailability.UNKNOWN, snapshotSink(incompatibleFallback).portAvailability());
        assertEquals(PortAvailability.UNKNOWN, snapshotSink(unrecognized).portAvailability());
        assertEquals(PortAvailability.UNKNOWN, snapshotSink(missingRoute).portAvailability());
    }

    @Test
    void oldConvenienceConstructorsDefaultUnknownAndSuspendedDoesNotMeanUnavailable() {
        AudioNode unknown = new AudioNode(1, "alsa_output.unknown", "Unknown", "Audio/Sink", "running",
                "", "", "", 2, List.of(48000), 1.0, false, null);
        AudioNode suspended = new AudioNode(2, "alsa_output.suspended", "Suspended", "Audio/Sink", "suspended",
                "", "", "", 2, List.of(48000), 1.0, false, null);

        assertEquals(PortAvailability.UNKNOWN, unknown.portAvailability());
        assertTrue(unknown.outputAvailable());
        assertFalse(suspended.active());
        assertTrue(suspended.outputAvailable());
        AudioNode input = new AudioNode(3, "alsa_input.test", "Test input", "Audio/Source", "running",
                "", "", "", 2, List.of(48000), 1.0, false, null, identity("input"), PortAvailability.UNAVAILABLE);
        assertTrue(DeviceSelectorMatcher.matches(input, Map.of("kind", "name_equals", "value", input.name())));
        assertSame(input, DeviceSelectorMatcher.resolve(List.of(input), input.name(), List.of()));
    }
    @Test
    void exactRawNameResolvesAvailableOutputWithoutBypassingAvailabilityOrAmbiguity() {
        AudioNode legacy = sink(20, "alsa_output.legacy", "running", PortAvailability.UNKNOWN, identity("legacy"));
        AudioNode duplicate = sink(21, legacy.name(), "running", PortAvailability.AVAILABLE, identity("duplicate"));
        AudioNode unavailable = sink(22, "alsa_output.hidden", "running", PortAvailability.UNAVAILABLE, identity("hidden"));

        assertSame(legacy, DeviceSelectorMatcher.resolve(List.of(legacy), legacy.name(), List.of()));
        assertNull(DeviceSelectorMatcher.resolve(List.of(legacy, duplicate), legacy.name(), List.of()));
        assertNull(DeviceSelectorMatcher.resolve(List.of(unavailable), unavailable.name(), List.of()));
    }

    @Test
    void identityCollisionRemainsAmbiguousWhenOnlyOneDuplicateIdentityIsAvailable() {
        DeviceIdentity duplicatedIdentity = identity("duplicate");
        AudioNode unavailable = sink(30, "alsa_output.duplicate-a", "running",
                PortAvailability.UNAVAILABLE, duplicatedIdentity);
        AudioNode available = sink(31, "alsa_output.duplicate-b", "running",
                PortAvailability.AVAILABLE, duplicatedIdentity);

        DeviceDiscovery.Resolution resolution = DeviceDiscovery.resolve(
                List.of(unavailable, available), binding("duplicated-slot", duplicatedIdentity));

        assertEquals(DeviceDiscovery.State.AMBIGUOUS, resolution.state());
        assertNull(resolution.node());
    }

    @Test
    void unavailableSavedBindingStaysOfflineCannotBeReboundAndReturnsWithStableIdentity() throws Exception {
        AudioNode unavailable = snapshotSink(root("""
                [{"device":0,"name":"analog-output-headphones","available":"no"}]
                """, "[]"));
        Map<String, Object> saved = binding("dac-slot", unavailable.identity());
        DeviceDiscovery.Resolution offline = DeviceDiscovery.resolve(List.of(unavailable), saved);
        List<DeviceDiscovery.Entry> catalog = DeviceDiscovery.catalog(List.of(unavailable), List.of(saved));

        assertEquals(DeviceDiscovery.State.OFFLINE, offline.state());
        assertNull(offline.node());
        assertEquals("Output unavailable", offline.message());
        assertEquals(1, catalog.size(), "the saved offline slot remains represented without a duplicate NEW endpoint");
        assertEquals("dac-slot", catalog.getFirst().slotId());
        assertEquals(DeviceDiscovery.State.OFFLINE, catalog.getFirst().state());
        assertTrue(DeviceDiscovery.catalog(List.of(unavailable), List.of()).isEmpty(),
                "an explicitly unavailable unbound endpoint is not offered as NEW");
        assertNull(DeviceSelectorMatcher.resolve(List.of(unavailable), unavailable.name(), List.of()));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("devices", new ArrayList<>());
        assertThrows(IllegalArgumentException.class, () -> DeviceDiscovery.bind(config, "new-slot", unavailable));
        assertEquals(List.of(), config.get("devices"), "rejected binding must not mutate saved configuration");

        AudioNode reconnected = new AudioNode(999, unavailable.name(), unavailable.description(), unavailable.mediaClass(),
                "running", "", "", "", unavailable.channels(), unavailable.sampleRates(), unavailable.volume(),
                unavailable.muted(), unavailable.pulseId(), unavailable.identity(), PortAvailability.AVAILABLE);
        DeviceDiscovery.Resolution online = DeviceDiscovery.resolve(List.of(reconnected), saved);
        assertEquals(DeviceDiscovery.State.ONLINE, online.state());
        assertSame(reconnected, online.node());
        assertEquals(unavailable.identity().fingerprint(), reconnected.identity().fingerprint());
        assertEquals(PortAvailability.UNAVAILABLE, unavailable.portAvailability());
    }

    @Test
    void routePlanRejectsUnavailableTargetUsesExplicitFallbackAndTracksTopologyAvailability() throws Exception {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "effects-haptic", "from", "music", "to", "haptic-device",
                "fallback", "safe-usb", "enabled", true));
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", "haptic-device", "selector", DefaultConfig.map("kind", "name_equals", "value", "family-haptic"),
                "role", "haptic_output", "volume", 1.0, "mute", false));
        devices.add(DefaultConfig.map("id", "safe-usb", "selector", DefaultConfig.map("kind", "name_equals", "value", "usb-speakers"),
                "role", "speaker", "volume", 1.0, "mute", false));
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("active_buses", List.of("music"));
        profiles.getFirst().put("active_routes", List.of("effects-haptic"));

        DeviceIdentity familyIdentity = identity("family-card");
        AudioNode availableFamily = sink(50, "family-haptic", "running", PortAvailability.AVAILABLE, familyIdentity);
        AudioNode unknownFamily = sink(50, "family-haptic", "running", PortAvailability.UNKNOWN, familyIdentity);
        AudioNode unavailableFamily = sink(50, "family-haptic", "running", PortAvailability.UNAVAILABLE, familyIdentity);
        AudioNode usbSpeakers = sink(51, "usb-speakers", "running", PortAvailability.UNKNOWN, identity("usb-card"));
        PipeWireRouteManager manager = new PipeWireRouteManager();

        PipeWireRouteManager.Plan availablePlan = manager.plan(config, snapshot(List.of(availableFamily), 50));
        PipeWireRouteManager.Plan unknownPlan = manager.plan(config, snapshot(List.of(unknownFamily), 50));
        PipeWireRouteManager.Plan fallbackPlan = manager.plan(config, snapshot(List.of(unavailableFamily, usbSpeakers), 50));

        assertTrue(availablePlan.routes().getFirst().sink().outputAvailable());
        assertTrue(unknownPlan.routes().getFirst().sink().outputAvailable());
        assertFalse(PipeWireRouteStaticSupport03.sameRoutingTopology(availablePlan, unknownPlan),
                "topology comparison must notice a port-availability transition for the same sink identity");
        assertEquals("usb-speakers", fallbackPlan.routes().getFirst().sink().name());
        assertTrue(fallbackPlan.warnings().stream().anyMatch(warning -> warning.contains("using fallback output safe-usb")));

        String before = RouteInputSignature.create(config, snapshot(List.of(availableFamily), 50), 1);
        String after = RouteInputSignature.create(config, snapshot(List.of(unavailableFamily), 50), 1);
        assertNotEquals(before, after, "the route input signature must change when a retained sink ID becomes unavailable");
    }

    private static JsonNode root(String routes, String enumRoutes) throws Exception {
        return JSON.readTree("""
                [
                  {"id":20,"type":"PipeWire:Interface:Device","info":{"props":{"device.bus-path":"pci-usb-1","device.serial":"DAC-42"},
                    "params":{"Profile":[{"name":"analog-stereo","active":true}],"Route":%s,"EnumRoute":%s}}},
                  {"id":21,"type":"PipeWire:Interface:Node","info":{"state":"running","props":{
                    "media.class":"Audio/Sink","node.name":"alsa_output.DAC","node.description":"DAC","audio.channels":2,
                    "audio.position":["FL","FR"],"device.id":20,"card.profile.device":0,"device.port.availability":"no"},
                    "params":{"EnumFormat":[]}}}
                ]
                """.formatted(routes, enumRoutes));
    }

    private static AudioNode snapshotSink(JsonNode root) {
        List<AudioNode> sinks = new ArrayList<>(), sources = new ArrayList<>(), streams = new ArrayList<>();
        PipeWireSnapshotReader.readNodes(root, Map.of(), Map.of(), sinks, sources, streams);
        return sinks.getFirst();
    }

    private static AudioNode sink(int id, String name, String state, PortAvailability availability) {
        return sink(id, name, state, availability, identity(name));
    }

    private static AudioNode sink(int id, String name, String state, PortAvailability availability, DeviceIdentity identity) {
        return new AudioNode(id, name, name, "Audio/Sink", state, "", "", "", 2, List.of(48000),
                1.0, false, null, identity, availability);
    }

    private static DeviceIdentity identity(String serial) {
        return DeviceIdentity.from(Map.of("device.bus-path", "pci-test", "device.serial", serial,
                "device.profile.name", "analog-stereo", "device.port.name", "analog-output", "audio.position", "FL,FR"), 2);
    }

    private static Map<String, Object> binding(String id, DeviceIdentity identity) {
        return Map.of("id", id, "identity", identity.toMap());
    }

    private static PipeWireBackend.Snapshot snapshot(List<AudioNode> sinks, int defaultSinkId) {
        return new PipeWireBackend.Snapshot(true, "", sinks, List.of(), List.of(), List.of(), Map.of(),
                defaultSinkId, 0);
    }
}
