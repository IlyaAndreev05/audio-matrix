package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.UnifiedGraphConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GnomeGraphGeneratedOutputCommandTest {
    private static final String ROUTE_ID = "main";
    private static final List<String> CHANNELS = List.of("FL", "FR");
    @TempDir Path temp;

    @Test void generatedOutputSetterPersistsOnlyTheCanonicalEndpointAndConfirmsItsRuntimeTarget() throws Exception {
        Map<String, Object> config = DefaultConfig.create();
        DynamicGeneratorNode generator = generator();
        AudioGraph graph = replaceGenerator(config, generator);
        DeviceIdentity identity = DeviceIdentity.from(Map.of("device.serial", "command-output",
                "device.profile.name", "analog-stereo", "device.port.name", "front-output",
                "audio.position", "FL,FR"), 2);
        AudioNode output = new AudioNode(41, "alsa_output.command", "Command output", "Audio/Sink",
                "running", "", "", "", 2, List.of(48000), 0.6, false, null, identity);
        DynamicInstanceRegistry.ReconcileResult registry = DynamicInstanceRegistry.reconcile(graph,
                Map.of(generator.id(), List.of(DynamicInstanceRegistry.output(output))));
        String instanceKey = "device:" + identity.fingerprint();
        DynamicInstanceRegistry.RuntimeInstance instance = DynamicInstanceRegistry.requireInstance(
                registry, generator.id(), instanceKey, true);
        String bindingId = String.valueOf(instance.runtimeBinding().get("deviceId"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices =
                (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", bindingId, "kind", "device_slot", "role", "output",
                "identity", identity.toMap(), "volume", 0.62, "mute", true));
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(output), List.of(),
                List.of(), List.of(), Map.of(), output.id(), System.currentTimeMillis());
        AtomicReference<Integer> runtimeNode = new AtomicReference<>();
        AtomicReference<Double> runtimeVolume = new AtomicReference<>();

        try (ConfigStore store = new ConfigStore(temp.resolve("generated-output.json"))) {
            store.save(config);
            CompletableFuture<Void> offline = GnomeGraphNodeCommandExecutor.execute(
                    new GnomeNodeCommandHandler.SetVolume(
                            new GnomeNodeCommandHandler.Target(ROUTE_ID, generator.id(), instanceKey), 0.3),
                    store, PipeWireBackend.Snapshot.offline("PipeWire disconnected"), List.of(), registry,
                    false, null,
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Offline output must not apply")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Offline output must not rollback")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                    (nodeId, candidate, endpointId, property) ->
                            CompletableFuture.failedFuture(new AssertionError("Offline output must not change")),
                    error -> fail(error), () -> {});
            assertThrows(java.util.concurrent.CompletionException.class, offline::join);

            GnomeNodeCommandHandler.Target unknown =
                    new GnomeNodeCommandHandler.Target(ROUTE_ID, generator.id(), "device:unknown");
            CompletableFuture<Void> missing = GnomeGraphNodeCommandExecutor.execute(
                    new GnomeNodeCommandHandler.SetVolume(unknown, 0.3), store, snapshot, List.of(output),
                    registry, false, null,
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Unknown output must not apply")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Unknown output must not rollback")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                    (nodeId, candidate, endpointId, property) ->
                            CompletableFuture.failedFuture(new AssertionError("Unknown output must not change")),
                    error -> fail(error), () -> {});
            assertThrows(java.util.concurrent.CompletionException.class, missing::join);
            assertEquals(0.62, ((Number) GraphNodeCommand.deviceBinding(store.snapshot(), bindingId)
                    .get("volume")).doubleValue(), 0.0001);
            GnomeGraphNodeCommandExecutor.execute(
                    new GnomeNodeCommandHandler.SetVolume(
                            new GnomeNodeCommandHandler.Target(ROUTE_ID, generator.id(), instanceKey), 0.4),
                    store, snapshot, List.of(output), registry, false, null,
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Endpoint volume must not rebuild graph")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Endpoint volume must not rebuild graph")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                    (nodeId, candidate, endpointId, changedProperty) -> {
                        runtimeNode.set(nodeId);
                        assertEquals("volume", changedProperty);
                        runtimeVolume.set(((Number) GraphNodeCommand.deviceBinding(candidate, endpointId)
                                .get("volume")).doubleValue());
                        assertFalse((Boolean) GraphNodeCommand.deviceBinding(candidate, endpointId).get("mute"));
                        return CompletableFuture.completedFuture(null);
                    },
                    error -> fail(error), () -> {}).join();

            Map<String, Object> savedEndpoint = GraphNodeCommand.deviceBinding(store.snapshot(), bindingId);
            assertEquals(41, runtimeNode.get());
            assertEquals(0.4, runtimeVolume.get(), 0.0001);
            assertEquals(0.4, ((Number) savedEndpoint.get("volume")).doubleValue(), 0.0001);
            assertFalse((Boolean) savedEndpoint.get("mute"));
            assertTrue(DynamicInstanceRegistry.instanceOverride(store.snapshot(), ROUTE_ID,
                    generator.id(), instanceKey, "gain").isEmpty());
            assertTrue(DynamicInstanceRegistry.instanceOverride(store.snapshot(), ROUTE_ID,
                    generator.id(), instanceKey, "mute").isEmpty());
        }
    }

    @Test void generatedOutputMuteAndUnmuteRetainTheCanonicalVolumeWithoutInstanceOverrides() throws Exception {
        Map<String, Object> config = DefaultConfig.create();
        DynamicGeneratorNode generator = generator();
        AudioGraph graph = replaceGenerator(config, generator);
        DeviceIdentity identity = DeviceIdentity.from(Map.of("device.serial", "command-output",
                "device.profile.name", "analog-stereo", "device.port.name", "front-output",
                "audio.position", "FL,FR"), 2);
        AudioNode output = new AudioNode(41, "alsa_output.command", "Command output", "Audio/Sink",
                "running", "", "", "", 2, List.of(48000), 0.6, false, null, identity);
        DynamicInstanceRegistry.ReconcileResult registry = DynamicInstanceRegistry.reconcile(graph,
                Map.of(generator.id(), List.of(DynamicInstanceRegistry.output(output))));
        String instanceKey = "device:" + identity.fingerprint();
        DynamicInstanceRegistry.RuntimeInstance instance = DynamicInstanceRegistry.requireInstance(
                registry, generator.id(), instanceKey, true);
        String bindingId = String.valueOf(instance.runtimeBinding().get("deviceId"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices =
                (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", bindingId, "kind", "device_slot", "role", "output",
                "identity", identity.toMap(), "volume", 0.62, "mute", false));
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(output), List.of(),
                List.of(), List.of(), Map.of(), output.id(), System.currentTimeMillis());
        List<Boolean> runtimeMutes = new ArrayList<>();

        try (ConfigStore store = new ConfigStore(temp.resolve("generated-output-mute.json"))) {
            store.save(config);
            Object savedRoutes = store.snapshot().get("route_library");
            for (boolean muted : List.of(true, false)) {
                GnomeGraphNodeCommandExecutor.execute(
                        new GnomeNodeCommandHandler.SetMuted(
                                new GnomeNodeCommandHandler.Target(ROUTE_ID, generator.id(), instanceKey), muted),
                        store, snapshot, List.of(output), registry, false, null,
                        ignored -> CompletableFuture.failedFuture(new AssertionError("Endpoint mute must not rebuild graph")),
                        ignored -> CompletableFuture.failedFuture(new AssertionError("Endpoint mute must not rebuild graph")),
                        ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                        ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                        (nodeId, candidate, endpointId, property) -> {
                            assertEquals(output.id(), nodeId);
                            Map<String, Object> endpoint = GraphNodeCommand.deviceBinding(candidate, endpointId);
                            assertEquals(0.62, ((Number) endpoint.get("volume")).doubleValue(), 0.0001);
                            assertEquals(muted, endpoint.get("mute"));
                            runtimeMutes.add((Boolean) endpoint.get("mute"));
                            return CompletableFuture.completedFuture(null);
                        },
                        error -> fail(error), () -> {}).join();

                Map<String, Object> saved = store.snapshot();
                Map<String, Object> endpoint = GraphNodeCommand.deviceBinding(saved, bindingId);
                assertEquals(0.62, ((Number) endpoint.get("volume")).doubleValue(), 0.0001);
                assertEquals(muted, endpoint.get("mute"));
                assertEquals(savedRoutes, saved.get("route_library"));
                assertTrue(DynamicInstanceRegistry.instanceOverride(saved, ROUTE_ID,
                        generator.id(), instanceKey, "gain").isEmpty());
                assertTrue(DynamicInstanceRegistry.instanceOverride(saved, ROUTE_ID,
                        generator.id(), instanceKey, "mute").isEmpty());
            }
            assertEquals(List.of(true, false), runtimeMutes);
        }
    }

    private static DynamicGeneratorNode generator() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("registry", "outputs");
        parameters.put("template", "default-output");
        parameters.put("selector", "device:*");
        parameters.put("instances", new LinkedHashMap<>());
        parameters.put("connect_to", Map.of("node_id", UnifiedGraphConfig.SWITCH_ID, "port_id", "in"));
        parameters.put("excluded_selectors", List.of("device:role:haptic_output"));
        return new DynamicGeneratorNode(UnifiedGraphConfig.MONITOR_OUTPUT_ID, "Generated output", "device:*",
                CHANNELS, parameters);
    }

    private static AudioGraph replaceGenerator(Map<String, Object> config, DynamicGeneratorNode generator) {
        Map<String, Object> savedRoute = routes(config).stream()
                .filter(route -> ROUTE_ID.equals(route.get("id"))).findFirst().orElseThrow();
        AudioGraph original = GraphCodec.fromMap(savedRoute);
        List<GraphNode> nodes = new ArrayList<>(original.nodes());
        int index = nodes.stream().map(GraphNode::id).toList().indexOf(generator.id());
        if (index >= 0) nodes.set(index, generator);
        else nodes.add(generator);
        AudioGraph changed = new AudioGraph(original.id(), original.name(), nodes, original.connections());
        GraphRouteLibrary.saveRoute(config, changed, String.valueOf(savedRoute.getOrDefault("group_id", "")));
        return GraphCodec.fromMap(routes(config).stream()
                .filter(route -> ROUTE_ID.equals(route.get("id"))).findFirst().orElseThrow());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> routes(Map<String, Object> config) {
        return (List<Map<String, Object>>) config.get("route_library");
    }
}
