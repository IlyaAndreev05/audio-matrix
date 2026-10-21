package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GnomeGraphSavedDeviceCommandTest {
    @TempDir Path temp;

    @Test void savedOutputSetterUpdatesTheEndpointStoreAndLiveSinkWithoutChangingTheGraphSlot() throws Exception {
        Map<String, Object> config = DefaultConfig.create();
        String endpointId = "saved-output-device";
        DeviceIdentity identity = DeviceIdentity.from(Map.of("device.serial", "saved-output-device",
                "device.profile.name", "analog-stereo", "device.port.name", "front-output",
                "audio.position", "FL,FR"), 2);
        AudioNode output = new AudioNode(52, "alsa_output.saved-command", "Saved output", "Audio/Sink",
                "running", "", "", "", 2, List.of(48000), 0.7, false, null, identity);
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices =
                (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", endpointId, "kind", "device_slot", "role", "output",
                "identity", identity.toMap(), "volume", 0.62, "mute", true));
        Map<String, Object> route = routes(config).stream().filter(item -> "main".equals(item.get("id")) )
                .findFirst().orElseThrow();
        Map<String, Object> slot = maps(route.get("nodes" )).stream()
                .filter(item -> GraphCommonVibro.VIBRO_OUTPUT_ID.equals(item.get("id"))).findFirst().orElseThrow();
        Map<String, Object> slotParameters = object(slot.get("parameters"));
        slotParameters.put("device_id", "device:" + endpointId);
        double savedSlotVolume = ((Number) slotParameters.get("volume")).doubleValue();
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(output), List.of(),
                List.of(), List.of(), Map.of(), output.id(), System.currentTimeMillis());
        AtomicReference<Integer> runtimeNode = new AtomicReference<>();
        AtomicReference<Double> runtimeVolume = new AtomicReference<>();

        try (ConfigStore store = new ConfigStore(temp.resolve("saved-output.json"))) {
            store.save(config);
            GnomeGraphNodeCommandExecutor.execute(
                    new GnomeNodeCommandHandler.SetVolume(
                            new GnomeNodeCommandHandler.Target("main", GraphCommonVibro.VIBRO_OUTPUT_ID, ""), 0.38),
                    store, snapshot, List.of(output), null, false, null,
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Saved endpoint must not rebuild graph")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Saved endpoint must not rebuild graph")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                    (nodeId, candidate, bindingId, property) -> {
                        runtimeNode.set(nodeId);
                        assertEquals("volume", property);
                        runtimeVolume.set(((Number) GraphNodeCommand.deviceBinding(candidate, bindingId)
                                .get("volume")).doubleValue());
                        return CompletableFuture.completedFuture(null);
                    },
                    error -> fail(error), () -> {}).join();

            Map<String, Object> saved = store.snapshot();
            Map<String, Object> endpoint = GraphNodeCommand.deviceBinding(saved, endpointId);
            assertEquals(52, runtimeNode.get());
            assertEquals(0.38, runtimeVolume.get(), 0.0001);
            assertEquals(0.38, ((Number) endpoint.get("volume")).doubleValue(), 0.0001);
            assertTrue((Boolean) endpoint.get("mute"));
            Map<String, Object> savedRoute = routes(saved).stream().filter(item -> "main".equals(item.get("id")))
                    .findFirst().orElseThrow();
            Map<String, Object> savedSlot = maps(savedRoute.get("nodes")).stream()
                    .filter(item -> GraphCommonVibro.VIBRO_OUTPUT_ID.equals(item.get("id"))).findFirst().orElseThrow();
            Map<String, Object> savedParameters = object(savedSlot.get("parameters"));
            assertEquals(savedSlotVolume, ((Number) savedParameters.get("volume")).doubleValue(), 0.0001);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> routes(Map<String, Object> config) {
        return (List<Map<String, Object>>) config.get("route_library");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }
}
