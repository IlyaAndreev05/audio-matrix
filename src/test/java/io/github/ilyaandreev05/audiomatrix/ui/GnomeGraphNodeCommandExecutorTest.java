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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GnomeGraphNodeCommandExecutorTest {
    private static final String ROUTE_ID = "main";
    private static final List<String> CHANNELS = List.of("FL", "FR");
    @TempDir Path temp;

    @Test void generatedApplicationSetterUpdatesOnlyTheExistingCanonicalRuleAndAwaitsRuntime() throws Exception {
        Map<String, Object> config = DefaultConfig.create();
        DynamicGeneratorNode generator = generator("applications", "default-application",
                "application:unassigned", Map.of());
        AudioGraph graph = replaceGenerator(config, generator);
        AudioNode stream = stream(17, "org.example.player", "Example player", "example-player", 0.7, false);
        DynamicInstanceRegistry.ReconcileResult registry = DynamicInstanceRegistry.reconcile(graph,
                Map.of(generator.id(), List.of(DynamicInstanceRegistry.application(stream))));
        String instanceKey = "app:org.example.player";
        config.put("applications", new ArrayList<>(List.of(
                DefaultConfig.map("id", "process-rule", "match", DefaultConfig.map("process", "example-player"),
                        "volume", 0.55, "mute", false, "bus", "main"),
                DefaultConfig.map("id", "app-rule", "match", DefaultConfig.map("app_id", "org.example.player"),
                        "volume", 0.7, "mute", true, "bus", "main"))));
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(), List.of(),
                List.of(stream), List.of(), Map.of(), -1, System.currentTimeMillis());

        try (ConfigStore store = new ConfigStore(temp.resolve("application.json"))) {
            store.save(config);
            AtomicReference<Map<String, Object>> appliedRule = new AtomicReference<>();
            CompletableFuture<Void> runtime = new CompletableFuture<>();
            AtomicBoolean saved = new AtomicBoolean();
            CompletableFuture<Void> command = GnomeGraphNodeCommandExecutor.execute(
                    new GnomeNodeCommandHandler.SetVolume(
                            new GnomeNodeCommandHandler.Target(ROUTE_ID, generator.id(), instanceKey), 0.35),
                    store, null, snapshot, List.of(), registry, false, null,
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Graph runtime must not be changed")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Graph rollback must not be changed")),
                    candidate -> {
                        appliedRule.set(((List<Map<String, Object>>) candidate.get("applications")).getFirst());
                        return runtime;
                    },
                    ignored -> CompletableFuture.completedFuture(null),
                    error -> fail(error), () -> saved.set(true));

            assertFalse(command.isDone());
            List<Map<String, Object>> beforeRuntime = (List<Map<String, Object>>) store.snapshot().get("applications");
            assertEquals(0.55, ((Number) beforeRuntime.getFirst().get("volume")).doubleValue(), 0.0001);
            assertFalse(saved.get());
            runtime.complete(null);
            command.join();

            List<Map<String, Object>> rules = (List<Map<String, Object>>) store.snapshot().get("applications");
            assertEquals(0.35, ((Number) rules.getFirst().get("volume")).doubleValue(), 0.0001);
            assertFalse((Boolean) rules.getFirst().get("mute"));
            assertEquals("process-rule", appliedRule.get().get("id"));
            assertEquals(0.7, ((Number) rules.get(1).get("volume")).doubleValue(), 0.0001);
            assertTrue((Boolean) rules.get(1).get("mute"));
            assertTrue(saved.get());
            assertTrue(DynamicInstanceRegistry.instanceOverride(store.snapshot(), ROUTE_ID,
                    generator.id(), instanceKey, "gain").isEmpty(),
                    "application level has a single source of truth in applications[]");
        }
    }

    @Test void firstGeneratedApplicationSetterCreatesItsCanonicalRuleWithoutAnInstanceOverride() throws Exception {
        Map<String, Object> config = DefaultConfig.create();
        DynamicGeneratorNode generator = generator("applications", "default-application",
                "application:unassigned", Map.of());
        AudioGraph graph = replaceGenerator(config, generator);
        AudioNode stream = stream(18, "org.example.new-player", "New player", "new-player", 0.8, false);
        DynamicInstanceRegistry.ReconcileResult registry = DynamicInstanceRegistry.reconcile(graph,
                Map.of(generator.id(), List.of(DynamicInstanceRegistry.application(stream))));
        config.put("applications", new ArrayList<>());
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(), List.of(),
                List.of(stream), List.of(), Map.of(), -1, System.currentTimeMillis());
        String instanceKey = "app:org.example.new-player";
        AtomicReference<Map<String, Object>> appliedRule = new AtomicReference<>();

        try (ConfigStore store = new ConfigStore(temp.resolve("new-application.json"))) {
            store.save(config);
            GnomeGraphNodeCommandExecutor.execute(
                    new GnomeNodeCommandHandler.SetVolume(
                            new GnomeNodeCommandHandler.Target(ROUTE_ID, generator.id(), instanceKey), 0.34),
                    store, null, snapshot, List.of(), registry, false, null,
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Application setting must not rebuild graph")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Application setting must not rebuild graph")),
                    candidate -> {
                        appliedRule.set(((List<Map<String, Object>>) candidate.get("applications")).getFirst());
                        return CompletableFuture.completedFuture(null);
                    },
                    ignored -> CompletableFuture.completedFuture(null),
                    error -> fail(error), () -> {}).join();

            Map<String, Object> rule = ((List<Map<String, Object>>) store.snapshot().get("applications")).getFirst();
            assertTrue(String.valueOf(rule.get("id")).startsWith("dynamic-app-"));
            assertEquals("org.example.new-player", ((Map<String, Object>) rule.get("match")).get("app_id"));
            assertEquals(0.34, ((Number) rule.get("volume")).doubleValue(), 0.0001);
            assertFalse((Boolean) rule.get("mute"));
            assertEquals(rule.get("id"), appliedRule.get().get("id"));
            assertTrue(DynamicInstanceRegistry.instanceOverride(store.snapshot(), ROUTE_ID,
                    generator.id(), instanceKey, "gain").isEmpty());
        }
    }

    @Test void generatedOutputTargetResolvesItsGainToTheExistingDeviceBinding() {
        Map<String, Object> config = DefaultConfig.create();
        DynamicGeneratorNode generator = generator(UnifiedGraphConfig.MONITOR_OUTPUT_ID, "outputs", "default-output",
                "device:*", Map.of("connect_to", Map.of("node_id", UnifiedGraphConfig.SWITCH_ID, "port_id", "in"),
                        "excluded_selectors", List.of("device:role:haptic_output")));
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
        String configId = bindingId.startsWith("device:") ? bindingId.substring("device:".length()) : bindingId;
        config.put("devices", new ArrayList<>(List.of(DefaultConfig.map("id", configId,
                "kind", "device_slot", "role", "output", "identity", identity.toMap(),
                "volume", 0.62, "mute", true))));
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(output), List.of(),
                List.of(), List.of(), Map.of(), output.id(), System.currentTimeMillis());

        GnomeGraphNodeCommandExecutor.PropertyValue property = (GnomeGraphNodeCommandExecutor.PropertyValue)
                GnomeGraphNodeCommandExecutor.propertyValue(config,
                        new GnomeNodeCommandHandler.PropertyKey(ROUTE_ID, generator.id(), instanceKey, "volume"),
                        registry, snapshot, List.of(output));
        GnomeGraphNodeCommandExecutor.PropertyValue muteProperty =
                (GnomeGraphNodeCommandExecutor.PropertyValue) GnomeGraphNodeCommandExecutor.propertyValue(config,
                        new GnomeNodeCommandHandler.PropertyKey(ROUTE_ID, generator.id(), instanceKey, "muted"),
                        registry, snapshot, List.of(output));

        assertEquals("device:" + configId, property.targetIdentity());
        assertEquals(0.62, ((Number) property.value()).doubleValue(), 0.0001);
        assertTrue((Boolean) muteProperty.value());
        assertTrue(instance.bindingAvailable());
        assertTrue(instance.gainControlAvailable());

    }



    private static DynamicGeneratorNode generator(String registry, String template, String selector,
            Map<String, Object> additional) {
        return generator(GraphCommonVibro.MAIN_SOURCE_ID, registry, template, selector, additional);
    }

    private static DynamicGeneratorNode generator(String id, String registry, String template, String selector,
            Map<String, Object> additional) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("registry", registry);
        parameters.put("template", template);
        parameters.put("selector", selector);
        parameters.put("instances", new LinkedHashMap<>());
        parameters.putAll(additional);
        return new DynamicGeneratorNode(id, "Generated source", selector, CHANNELS, parameters);
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

    private static AudioNode stream(int id, String appId, String name, String process,
            double volume, boolean muted) {
        return new AudioNode(id, "stream-" + id, name, "Stream/Output/Audio", "running",
                name, process, appId, 2, List.of(48000), volume, muted, null);
    }
}
