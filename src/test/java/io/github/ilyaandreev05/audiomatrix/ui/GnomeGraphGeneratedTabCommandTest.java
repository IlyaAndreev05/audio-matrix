package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
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

import static org.junit.jupiter.api.Assertions.*;

class GnomeGraphGeneratedTabCommandTest {
    private static final String ROUTE_ID = "main";
    private static final List<String> CHANNELS = List.of("FL", "FR");
    @TempDir Path temp;

    @Test void tabGainAndMutePersistAsIndependentSparseOverridesAfterRuntimeConfirmation() throws Exception {
        Map<String, Object> config = DefaultConfig.create();
        DynamicGeneratorNode generator = generator("browser_tabs", "default-tab", "browser:*");
        AudioGraph graph = replaceGenerator(config, generator);
        String captureKey = "capture-1";
        AudioNode captured = new AudioNode(33, "audio-matrix-chrome-" + captureKey, "Page",
                "Stream/Output/Audio", "running", "Chrome", "chrome", "com.google.Chrome",
                2, List.of(48000), 0.8, false, null);
        DynamicInstanceRegistry.InstanceSnapshot tab = DynamicInstanceRegistry.browserTab("Chrome",
                "browser-1", "Default", "session-1", 7, "Page", true, false,
                "captured", captureKey, "chrome-rule", "com.google.Chrome", "chrome");
        DynamicInstanceRegistry.ReconcileResult registry = DynamicInstanceRegistry.reconcile(graph,
                Map.of(generator.id(), List.of(tab)));
        config.put("applications", new ArrayList<>(List.of(DefaultConfig.map("id", "chrome-rule",
                "match", DefaultConfig.map("app_id", "com.google.Chrome"),
                "volume", 0.8, "mute", false, "bus", "main"))));
        String groupId = groupId(config);
        GraphRouteLibrary.saveRoute(config, registry.configuredGraph(), groupId);
        String instanceKey = "tab:Chrome:browser-1:Default:session-1";
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(), List.of(),
                List.of(captured), List.of(), Map.of(), -1, System.currentTimeMillis());
        GnomeNodeCommandHandler.PropertyKey propertyKey =
                new GnomeNodeCommandHandler.PropertyKey(ROUTE_ID, generator.id(), instanceKey, "volume");
        GnomeGraphNodeCommandExecutor.PropertyValue baseline =
                (GnomeGraphNodeCommandExecutor.PropertyValue) GnomeGraphNodeCommandExecutor.propertyValue(
                        config, propertyKey, registry, snapshot, List.of());
        AtomicBoolean applied = new AtomicBoolean();

        try (ConfigStore store = new ConfigStore(temp.resolve("browser-tab.json"))) {
            store.save(config);
            GnomeGraphNodeCommandExecutor.execute(
                    new GnomeNodeCommandHandler.SetVolume(
                            new GnomeNodeCommandHandler.Target(ROUTE_ID, generator.id(), instanceKey), 0.42),
                    store, null, snapshot, List.of(), registry, true, null,
                    candidate -> {
                        applied.set(true);
                        return CompletableFuture.completedFuture(null);
                    },
                    ignored -> CompletableFuture.completedFuture(null),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Application rules are unrelated")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Application rollback is unrelated")),
                    error -> fail(error), () -> {}).join();
            assertEquals(0.42, ((Number) DynamicInstanceRegistry.instanceOverride(store.snapshot(), ROUTE_ID,
                    generator.id(), instanceKey, "gain").orElseThrow()).doubleValue(), 0.0001);
            assertTrue(DynamicInstanceRegistry.instanceOverride(store.snapshot(), ROUTE_ID,
                    generator.id(), instanceKey, "muted").isEmpty());

            GnomeGraphNodeCommandExecutor.execute(
                    new GnomeNodeCommandHandler.SetMuted(
                            new GnomeNodeCommandHandler.Target(ROUTE_ID, generator.id(), instanceKey), true),
                    store, null, snapshot, List.of(), registry, true, null,
                    candidate -> CompletableFuture.completedFuture(null),
                    ignored -> CompletableFuture.completedFuture(null),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Application rules are unrelated")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Application rollback is unrelated")),
                    error -> fail(error), () -> {}).join();

            Map<String, Object> saved = store.snapshot();
            assertEquals(1.0, ((Number) baseline.value()).doubleValue(), 0.0001);
            assertTrue(applied.get());
            assertEquals(0.42, ((Number) DynamicInstanceRegistry.instanceOverride(saved, ROUTE_ID,
                    generator.id(), instanceKey, "gain").orElseThrow()).doubleValue(), 0.0001);
            assertTrue((Boolean) DynamicInstanceRegistry.instanceOverride(saved, ROUTE_ID,
                    generator.id(), instanceKey, "muted").orElseThrow());
        }
    }

    private static DynamicGeneratorNode generator(String registry, String template, String selector) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("registry", registry);
        parameters.put("template", template);
        parameters.put("selector", selector);
        parameters.put("instances", new LinkedHashMap<>());
        return new DynamicGeneratorNode(GraphCommonVibro.MAIN_SOURCE_ID, "Generated source", selector,
                CHANNELS, parameters);
    }

    private static AudioGraph replaceGenerator(Map<String, Object> config, DynamicGeneratorNode generator) {
        Map<String, Object> savedRoute = routes(config).stream()
                .filter(route -> ROUTE_ID.equals(route.get("id"))).findFirst().orElseThrow();
        AudioGraph original = GraphCodec.fromMap(savedRoute);
        List<GraphNode> nodes = new ArrayList<>(original.nodes());
        int index = nodes.stream().map(GraphNode::id).toList().indexOf(generator.id());
        assertTrue(index >= 0, "default route must contain the dynamic generator");
        nodes.set(index, generator);
        AudioGraph changed = new AudioGraph(original.id(), original.name(), nodes, original.connections());
        GraphRouteLibrary.saveRoute(config, changed, String.valueOf(savedRoute.getOrDefault("group_id", "")));
        return GraphCodec.fromMap(routes(config).stream()
                .filter(route -> ROUTE_ID.equals(route.get("id"))).findFirst().orElseThrow());
    }

    private static String groupId(Map<String, Object> config) {
        return String.valueOf(routes(config).stream().filter(route -> ROUTE_ID.equals(route.get("id")))
                .findFirst().orElseThrow().getOrDefault("group_id", ""));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> routes(Map<String, Object> config) {
        return (List<Map<String, Object>>) config.get("route_library");
    }
}
