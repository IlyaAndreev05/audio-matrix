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

import static org.junit.jupiter.api.Assertions.*;

class GnomeGraphGeneratedTabValidationTest {
    private static final String ROUTE_ID = "main";
    private static final List<String> CHANNELS = List.of("FL", "FR");
    @TempDir Path temp;

    @Test void generatedTabTargetsRequireTheCurrentGeneratorAndExactLiveCapture() throws Exception {
        Map<String, Object> config = DefaultConfig.create();
        DynamicGeneratorNode generator = generator("browser_tabs", "default-tab", "browser:*");
        AudioGraph graph = replaceGenerator(config, generator);
        String captureKey = "capture-live";
        AudioNode captured = new AudioNode(34, "audio-matrix-chrome-" + captureKey, "Page",
                "Stream/Output/Audio", "running", "Chrome", "chrome", "com.google.Chrome",
                2, List.of(48000), 0.8, false, null);
        DynamicInstanceRegistry.InstanceSnapshot tab = DynamicInstanceRegistry.browserTab("Chrome",
                "browser-1", "Default", "session-2", 8, "Page", true, false,
                "captured", captureKey, "chrome-rule", "com.google.Chrome", "chrome");
        DynamicInstanceRegistry.ReconcileResult registry = DynamicInstanceRegistry.reconcile(graph,
                Map.of(generator.id(), List.of(tab)));
        String instanceKey = "tab:Chrome:browser-1:Default:session-2";
        GnomeNodeCommandHandler.Target target =
                new GnomeNodeCommandHandler.Target(ROUTE_ID, generator.id(), instanceKey);
        PipeWireBackend.Snapshot live = new PipeWireBackend.Snapshot(true, "", List.of(), List.of(),
                List.of(captured), List.of(), Map.of(), -1, System.currentTimeMillis());

        try (ConfigStore store = new ConfigStore(temp.resolve("offline-tab.json"))) {
            store.save(config);
            DynamicInstanceRegistry.InstanceSnapshot tabWithoutParentRule =
                    DynamicInstanceRegistry.browserTab("Chrome", "browser-1", "Default", "session-2", 8,
                            "Page", true, false, "captured", captureKey, "", "com.google.Chrome", "chrome");
            DynamicInstanceRegistry.ReconcileResult noParentRuleRegistry = DynamicInstanceRegistry.reconcile(graph,
                    Map.of(generator.id(), List.of(tabWithoutParentRule)));
            CompletableFuture<Void> missingParentRule = GnomeGraphNodeCommandExecutor.execute(
                    new GnomeNodeCommandHandler.SetMuted(target, true), store, null, live, List.of(),
                    noParentRuleRegistry, true, null,
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Unbound parent rule must not apply")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Unbound parent rule must not rollback")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                    error -> fail(error), () -> {});
            java.util.concurrent.CompletionException parentFailure =
                    assertThrows(java.util.concurrent.CompletionException.class, missingParentRule::join);
            assertTrue(parentFailure.getCause().getMessage().contains("parent application rule is unavailable"));
            assertTrue(DynamicInstanceRegistry.instanceOverride(store.snapshot(), ROUTE_ID,
                    generator.id(), instanceKey, "muted").isEmpty());
            CompletableFuture<Void> offline = GnomeGraphNodeCommandExecutor.execute(
                    new GnomeNodeCommandHandler.SetVolume(target, 0.3), store, null,
                    PipeWireBackend.Snapshot.offline("PipeWire disconnected"), List.of(), registry, true, null,
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Offline target must not apply")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Offline target must not rollback")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                    error -> fail(error), () -> {});
            assertThrows(java.util.concurrent.CompletionException.class, offline::join);
            assertTrue(DynamicInstanceRegistry.instanceOverride(store.snapshot(), ROUTE_ID,
                    generator.id(), instanceKey, "gain").isEmpty());
            AudioNode wrongCaptured = new AudioNode(35, "audio-matrix-chrome-other", "Other page",
                    "Stream/Output/Audio", "running", "Chrome", "chrome", "com.google.Chrome",
                    2, List.of(48000), 0.8, false, null);
            PipeWireBackend.Snapshot wrongCaptureSnapshot = new PipeWireBackend.Snapshot(true, "", List.of(),
                    List.of(), List.of(wrongCaptured), List.of(), Map.of(), -1, System.currentTimeMillis());
            CompletableFuture<Void> wrongCapture = GnomeGraphNodeCommandExecutor.execute(
                    new GnomeNodeCommandHandler.SetVolume(target, 0.3), store, null, wrongCaptureSnapshot,
                    List.of(), registry, true, null,
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Wrong capture must not apply")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Wrong capture must not rollback")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                    error -> fail(error), () -> {});
            java.util.concurrent.CompletionException captureFailure =
                    assertThrows(java.util.concurrent.CompletionException.class, wrongCapture::join);
            assertTrue(captureFailure.getCause().getMessage().contains("capture stream is unavailable"));

            GnomeNodeCommandHandler.Target unknown = new GnomeNodeCommandHandler.Target(
                    ROUTE_ID, generator.id(), "tab:Chrome:browser-1:Default:missing");
            CompletableFuture<Void> missing = GnomeGraphNodeCommandExecutor.execute(
                    new GnomeNodeCommandHandler.SetVolume(unknown, 0.3), store, null, live, List.of(),
                    registry, true, null,
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Unknown target must not apply")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Unknown target must not rollback")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                    ignored -> CompletableFuture.failedFuture(new AssertionError("Not an application target")),
                    error -> fail(error), () -> {});
            assertThrows(java.util.concurrent.CompletionException.class, missing::join);

            Map<String, Object> changedConfig = store.snapshot();
            DynamicGeneratorNode changedGenerator = generator("browser_tabs", "default-tab", "browser:other");
            replaceGenerator(changedConfig, changedGenerator);
            assertThrows(IllegalArgumentException.class, () -> GnomeGraphNodeCommandExecutor.propertyValue(
                    changedConfig, new GnomeNodeCommandHandler.PropertyKey(ROUTE_ID, generator.id(),
                            instanceKey, "volume"), registry, live, List.of()));
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> routes(Map<String, Object> config) {
        return (List<Map<String, Object>>) config.get("route_library");
    }
}
