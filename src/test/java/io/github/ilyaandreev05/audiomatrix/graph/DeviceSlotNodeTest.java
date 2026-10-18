package io.github.ilyaandreev05.audiomatrix.graph;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DeviceSlotNodeTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test void unassignedSlotPersistsOneInputAndOneOutputWithoutFallback() {
        DeviceSlotNode slot = new DeviceSlotNode("slot", "New Device", null, STEREO);
        AudioGraph graph = new AudioGraph("route", "Route", List.of(slot), List.of());
        DeviceSlotNode decoded = assertInstanceOf(DeviceSlotNode.class,
                GraphCodec.fromMap(GraphCodec.toMap(graph)).nodes().getFirst());
        assertEquals("", decoded.deviceId());
        assertEquals(List.of("in"), decoded.inputs().stream().map(AudioPort::id).toList());
        assertEquals(List.of("out"), decoded.outputs().stream().map(AudioPort::id).toList());
    }

    @Test void rejectsSlotFanoutRatherThanRoutingAnotherPhysicalSink() {
        DeviceSlotNode slot = new DeviceSlotNode("slot", "USB DAC", "usb-slot", STEREO);
        OutputNode first = new OutputNode("a", "A", "usb-slot", STEREO);
        OutputNode second = new OutputNode("b", "B", "other-slot", STEREO);
        AudioGraph graph = new AudioGraph("route", "Route", List.of(slot, first, second), List.of(
                new GraphConnection("a", "slot", "out", "a", "in", true, 1),
                new GraphConnection("b", "slot", "out", "b", "in", true, 1)));
        assertTrue(GraphValidator.validate(graph).stream().anyMatch(error -> error.contains("one logical output")));
    }
}
