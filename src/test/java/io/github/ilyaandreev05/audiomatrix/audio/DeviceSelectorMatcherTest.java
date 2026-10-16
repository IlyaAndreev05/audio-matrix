package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeviceSelectorMatcherTest {
    private final AudioNode usb = new AudioNode(47, "alsa_output.usb-DAC", "USB DAC Headphones", "Audio/Sink", "running", "", "", "", 2, List.of(48000), .5, false, null);

    @Test void namesAndDescriptionsCanBeMatchedWithoutTransientNodeIds() {
        assertTrue(DeviceSelectorMatcher.matches(usb, Map.of("kind", "name_equals", "value", "alsa_output.usb-DAC")));
        assertTrue(DeviceSelectorMatcher.matches(usb, Map.of("kind", "name_contains", "value", "USB-dac")));
        assertTrue(DeviceSelectorMatcher.matches(usb, Map.of("kind", "description_contains", "value", "headphones")));
        AudioNode afterReconnect = new AudioNode(99, usb.name(), usb.description(), usb.mediaClass(), usb.state(), "", "", "", 2, List.of(48000), .5, false, null);
        assertTrue(DeviceSelectorMatcher.matches(afterReconnect, Map.of("kind", "name_equals", "value", "alsa_output.usb-DAC")));
    }

    @Test void stableDeviceIdResolvesThroughItsSelectorAndOfflineDeviceStaysUnresolved() {
        Map<String, Object> device = Map.of("id", "dac-main", "selector", Map.of("kind", "description_contains", "value", "DAC Headphones"));
        assertSame(usb, DeviceSelectorMatcher.resolve(List.of(usb), "dac-main", List.of(device)));
        assertNull(DeviceSelectorMatcher.resolve(List.of(), "dac-main", List.of(device)));
        assertNull(DeviceSelectorMatcher.resolve(List.of(usb), "system_default", List.of(device)));
    }

    @Test void ambiguousSelectorsDoNotSilentlyChooseTheFirstNode() {
        AudioNode second = new AudioNode(48, "alsa_output.second", "USB DAC Speakers", "Audio/Sink", "running", "", "", "", 2, List.of(48000), .5, false, null);
        Map<String, Object> selector = Map.of("kind", "description_contains", "value", "DAC");
        assertEquals(List.of(usb, second), DeviceSelectorMatcher.findAll(List.of(usb, second), selector));
        assertNull(DeviceSelectorMatcher.find(List.of(usb, second), selector));
        assertNull(DeviceSelectorMatcher.resolve(List.of(usb, second), "dac", List.of()));
    }
}
