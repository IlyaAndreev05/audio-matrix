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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GnomeGraphSavedDeviceCommandTest {
    @TempDir Path temp;

    @Test void savedOutputSetterUpdatesTheEndpointStoreAndLiveSinkWithoutChangingTheGraphSlot() throws Exception {
        Map<String, Object> config = DefaultConfig.create();
        String endpointId = "saved-output-device";
        AudioNode output = savedOutput(config, endpointId, true);
        double savedSlotVolume = ((Number) slotParameters(config).get("volume")).doubleValue();
        PipeWireBackend.Snapshot snapshot = snapshot(output);
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
                        assertFalse((Boolean) GraphNodeCommand.deviceBinding(candidate, bindingId).get("mute"));
                        return CompletableFuture.completedFuture(null);
                    },
                    error -> fail(error), () -> {}).join();

            Map<String, Object> saved = store.snapshot();
            Map<String, Object> endpoint = GraphNodeCommand.deviceBinding(saved, endpointId);
            assertEquals(52, runtimeNode.get());
            assertEquals(0.38, runtimeVolume.get(), 0.0001);
            assertEquals(0.38, ((Number) endpoint.get("volume")).doubleValue(), 0.0001);
            assertFalse((Boolean) endpoint.get("mute"));
            assertEquals(savedSlotVolume,
                    ((Number) slotParameters(saved).get("volume")).doubleValue(), 0.0001);
        }
    }

    @Test void savedOutputMuteAndUnmuteRetainTheCanonicalVolumeWithoutChangingTheGraphSlot() throws Exception {
        Map<String, Object> config = DefaultConfig.create();
        String endpointId = "saved-output-device";
        AudioNode output = savedOutput(config, endpointId, false);
        double savedSlotVolume = ((Number) slotParameters(config).get("volume")).doubleValue();
        List<Boolean> runtimeMutes = new ArrayList<>();

        try (ConfigStore store = new ConfigStore(temp.resolve("saved-output-mute.json"))) {
            store.save(config);
            for (boolean muted : List.of(true, false)) {
                GnomeGraphNodeCommandExecutor.execute(
                        new GnomeNodeCommandHandler.SetMuted(
                                new GnomeNodeCommandHandler.Target("main", GraphCommonVibro.VIBRO_OUTPUT_ID, ""), muted),
                        store, snapshot(output), List.of(output), null, false, null,
                        ignored -> CompletableFuture.failedFuture(new AssertionError("Saved endpoint must not rebuild graph")),
                        ignored -> CompletableFuture.failedFuture(new AssertionError("Saved endpoint must not rebuild graph")),
                        ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                        ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                        (nodeId, candidate, bindingId, property) -> {
                            assertEquals(output.id(), nodeId);
                            Map<String, Object> endpoint = GraphNodeCommand.deviceBinding(candidate, bindingId);
                            assertEquals(0.62, ((Number) endpoint.get("volume")).doubleValue(), 0.0001);
                            assertEquals(muted, endpoint.get("mute"));
                            runtimeMutes.add((Boolean) endpoint.get("mute"));
                            return CompletableFuture.completedFuture(null);
                        },
                        error -> fail(error), () -> {}).join();

                Map<String, Object> saved = store.snapshot();
                Map<String, Object> endpoint = GraphNodeCommand.deviceBinding(saved, endpointId);
                assertEquals(0.62, ((Number) endpoint.get("volume")).doubleValue(), 0.0001);
                assertEquals(muted, endpoint.get("mute"));
                assertEquals(savedSlotVolume,
                        ((Number) slotParameters(saved).get("volume")).doubleValue(), 0.0001);
            }
            assertEquals(List.of(true, false), runtimeMutes);
        }
    }

    @Test void failedSavedOutputVolumeChangeRollsBackTheOriginalMutedCanonicalLevel() throws Exception {
        Map<String, Object> config = DefaultConfig.create();
        String endpointId = "saved-output-device";
        AudioNode output = savedOutput(config, endpointId, true);
        List<Map<String, Object>> runtimeEndpoints = new ArrayList<>();

        try (ConfigStore store = new ConfigStore(temp.resolve("saved-output-rollback.json"))) {
            store.save(config);
            Map<String, Object> original = store.snapshot();
            CompletableFuture<Void> command = GnomeGraphNodeCommandExecutor.execute(
                    new GnomeNodeCommandHandler.SetVolume(
                            new GnomeNodeCommandHandler.Target("main", GraphCommonVibro.VIBRO_OUTPUT_ID, ""), 0.4),
                    store, snapshot(output), List.of(output), null, false, null,
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Saved endpoint must not rebuild graph")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Saved endpoint must not rebuild graph")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                    (nodeId, candidate, bindingId, property) -> {
                        assertEquals(output.id(), nodeId);
                        assertEquals("volume", property);
                        assertEquals(original, store.snapshot(), "Runtime and rollback must precede persistence");
                        runtimeEndpoints.add(Map.copyOf(GraphNodeCommand.deviceBinding(candidate, bindingId)));
                        if (runtimeEndpoints.size() == 1)
                            return CompletableFuture.failedFuture(new IllegalStateException("Backend rejected volume"));
                        return CompletableFuture.completedFuture(null);
                    },
                    error -> fail(error), () -> fail("Failed runtime must not be reported as saved"));

            assertThrows(CompletionException.class, command::join);
            assertEquals(2, runtimeEndpoints.size());
            assertEquals(0.4, ((Number) runtimeEndpoints.get(0).get("volume")).doubleValue(), 0.0001);
            assertFalse((Boolean) runtimeEndpoints.get(0).get("mute"));
            assertEquals(0.62, ((Number) runtimeEndpoints.get(1).get("volume")).doubleValue(), 0.0001);
            assertTrue((Boolean) runtimeEndpoints.get(1).get("mute"));
            assertEquals(GraphNodeCommand.deviceBinding(original, endpointId), runtimeEndpoints.get(1));
            assertEquals(original, store.snapshot());
        }
    }

    private static AudioNode savedOutput(Map<String, Object> config, String endpointId, boolean muted) {
        DeviceIdentity identity = DeviceIdentity.from(Map.of("device.serial", "saved-output-device",
                "device.profile.name", "analog-stereo", "device.port.name", "front-output",
                "audio.position", "FL,FR"), 2);
        AudioNode output = new AudioNode(52, "alsa_output.saved-command", "Saved output", "Audio/Sink",
                "running", "", "", "", 2, List.of(48000), 0.7, false, null, identity);
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices =
                (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", endpointId, "kind", "device_slot", "role", "output",
                "identity", identity.toMap(), "volume", 0.62, "mute", muted));
        slotParameters(config).put("device_id", "device:" + endpointId);
        return output;
    }

    private static PipeWireBackend.Snapshot snapshot(AudioNode output) {
        return new PipeWireBackend.Snapshot(true, "", List.of(output), List.of(),
                List.of(), List.of(), Map.of(), output.id(), System.currentTimeMillis());
    }

    private static Map<String, Object> slotParameters(Map<String, Object> config) {
        Map<String, Object> route = routes(config).stream().filter(item -> "main".equals(item.get("id")))
                .findFirst().orElseThrow();
        Map<String, Object> slot = maps(route.get("nodes")).stream()
                .filter(item -> GraphCommonVibro.VIBRO_OUTPUT_ID.equals(item.get("id"))).findFirst().orElseThrow();
        return object(slot.get("parameters"));
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
