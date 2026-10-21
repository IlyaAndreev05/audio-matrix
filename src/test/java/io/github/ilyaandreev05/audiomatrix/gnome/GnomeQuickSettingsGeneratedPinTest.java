package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GnomeQuickSettingsGeneratedPinTest {
    @Test void generatedPinsUseGeneratorAndStableInstanceKeysAndRespectSparseUnpin() {
        Map<String, Object> config = DefaultConfig.create();
        List<String> channels = List.of("FL", "FR");
        String instanceKey = "app:org.example.Player";
        DynamicGeneratorNode generator = new DynamicGeneratorNode("apps", "Applications", "application:*", channels,
                DefaultConfig.map("registry", "applications", "auto_pin", true));
        SourceNode source = new SourceNode("source-child", "Player", "application:org.example.Player", channels);
        GainNode gain = new GainNode("gain-child", "Player Volume", channels, .63, false, "");
        AudioGraph effective = new AudioGraph("main", "Main", List.of(generator, source, gain), List.of());
        DynamicInstanceRegistry.RuntimeInstance instance = new DynamicInstanceRegistry.RuntimeInstance(
                generator.id(), instanceKey, Map.of("source", source.id(), "gain", gain.id()), "composite-player",
                true, true, true, DynamicInstanceRegistry.Confidence.HIGH,
                Map.of("applicationId", "org.example.Player", "processName", "player", "applicationName", "Player"),
                Map.of());
        Map<DynamicInstanceRegistry.InstanceAddress, DynamicInstanceRegistry.RuntimeInstance> instances = Map.of(
                new DynamicInstanceRegistry.InstanceAddress(generator.id(), instanceKey), instance);
        List<Map<String, Object>> apps = List.of(DefaultConfig.map("id", "app:org.example.Player",
                "application_id", "org.example.Player", "process", "player", "name", "Player",
                "available", true, "volumeAvailable", true, "volume", .63, "muted", false, "streams", List.of()));

        List<Map<String, Object>> pins = GnomeQuickSettingsPins.build(config,
                new PipeWireBackend.Snapshot(true, "", List.of(), List.of(), List.of(), List.of(), Map.of(), -1, 0),
                List.of(), apps, List.of(), effective, instances);
        Map<String, Object> generated = pins.stream().filter(pin -> generator.id().equals(pin.get("nodeId")))
                .findFirst().orElseThrow();

        assertEquals(generator.id(), generated.get("nodeId"));
        assertEquals(instanceKey, generated.get("instanceKey"));
        assertEquals("applications", generated.get("groupId"));
        assertEquals(gain.id(), ((Map<?, ?>) generated.get("gain")).get("nodeId"));
        assertEquals(List.of(), generated.get("applications"),
                "The generated graph Gain is the one volume control, not a duplicate parent app slider");

        DynamicInstanceRegistry.RuntimeInstance unpinned = new DynamicInstanceRegistry.RuntimeInstance(
                generator.id(), instanceKey, instance.nodeIds(), instance.compositeId(), true, true, true,
                DynamicInstanceRegistry.Confidence.HIGH, instance.runtimeBinding(), Map.of("pinned", false));
        List<Map<String, Object>> withoutPin = GnomeQuickSettingsPins.build(config,
                new PipeWireBackend.Snapshot(true, "", List.of(), List.of(), List.of(), List.of(), Map.of(), -1, 0),
                List.of(), apps, List.of(), effective,
                Map.of(new DynamicInstanceRegistry.InstanceAddress(generator.id(), instanceKey), unpinned));
        assertFalse(withoutPin.stream().anyMatch(pin -> generator.id().equals(pin.get("nodeId"))
                && instanceKey.equals(pin.get("instanceKey"))));
    }
}
