package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.VersionedConfig;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the prepared-graph cache of {@link DynamicRouteRuntime}.
 *
 * <p>The cache may skip decode/reconcile/compile only while the configuration is genuinely
 * unchanged: a new revision, an in-place mutation of a {@link VersionedConfig} at the same
 * revision, a different connection generation, another route, or a route/discovery change must
 * all produce visible PCM configuration instead of a stale one.</p>
 */
class DynamicRouteRuntimePreparedCacheTest {
    private static final List<String> STEREO = List.of("FL", "FR");
    private static final String GENERATOR = "apps";
    private static final String MUSIC_KEY = "app:org.example.Music";

    @TempDir Path temp;

    @Test void versionedConfigReusesOnePreparedGraph() throws Exception {
        try (ConfigStore store = configuredStore()) {
            DynamicRouteRuntime runtime = new DynamicRouteRuntime(store);
            try {
                VersionedConfig config = store.snapshot();
                DynamicRouteRuntime.Prepared first = runtime.prepareNow(config, live(singleStream()), List.of(), 1L);
                DynamicRouteRuntime.Prepared reused = runtime.prepareNow(config, live(singleStream()), List.of(), 1L);
                assertSame(first, reused, "an unchanged versioned configuration reuses the prepared graph");
                assertEquals("main", reused.routeId());
                assertNotNull(reused.reconcile());
            } finally { runtime.close(); }
        }
    }

    @Test void mutatingAVersionedConfigAtTheSameRevisionInvalidatesBothCaches() throws Exception {
        try (ConfigStore store = configuredStore()) {
            DynamicRouteRuntime runtime = new DynamicRouteRuntime(store);
            try {
                VersionedConfig config = store.snapshot();
                String gainId = DynamicInstanceRegistry.stableNodeId(GENERATOR, MUSIC_KEY, "gain");
                DynamicRouteRuntime.Prepared first = runtime.prepareNow(config, live(singleStream()), List.of(), 1L);
                String originalName = gainName(first, gainId);
                assertFalse(originalName.contains("Новое имя"));

                // Same revision, same stable signature input: only the retained instance record changed.
                putInstanceOverride(config, MUSIC_KEY, "Новое имя");
                DynamicRouteRuntime.Prepared mutated = runtime.prepareNow(config, live(singleStream()), List.of(), 1L);
                assertNotSame(first, mutated, "an in-place mutation of the same revision must not reuse the graph");
                assertTrue(gainName(mutated, gainId).contains("Новое имя"),
                        "the mutation reaches the compiled graph immediately");
            } finally { runtime.close(); }
        }
    }

    @Test void unversionedConfigBypassesBothCaches() throws Exception {
        try (ConfigStore store = configuredStore()) {
            DynamicRouteRuntime runtime = new DynamicRouteRuntime(store);
            try {
                Map<String, Object> plain = new LinkedHashMap<>(store.snapshot());
                DynamicRouteRuntime.Prepared first = runtime.prepareNow(plain, live(singleStream()), List.of(), 1L);
                DynamicRouteRuntime.Prepared second = runtime.prepareNow(plain, live(singleStream()), List.of(), 1L);
                assertNotSame(first, second, "a plain config map never reads or writes the caches");
                putInstanceOverride(plain, MUSIC_KEY, "Плоская копия");
                String gainId = DynamicInstanceRegistry.stableNodeId(GENERATOR, MUSIC_KEY, "gain");
                DynamicRouteRuntime.Prepared mutated = runtime.prepareNow(plain, live(singleStream()), List.of(), 1L);
                assertTrue(gainName(mutated, gainId).contains("Плоская копия"),
                        "an unversioned map is always recomputed from its current contents");
            } finally { runtime.close(); }
        }
    }

    @Test void anotherConnectionGenerationOrRouteMissesThePreparedCache() throws Exception {
        try (ConfigStore store = configuredStore()) {
            DynamicRouteRuntime runtime = new DynamicRouteRuntime(store);
            try {
                VersionedConfig config = store.snapshot();
                DynamicRouteRuntime.Prepared first = runtime.prepareNow(config, live(singleStream()), List.of(), 1L);
                assertNotSame(first, runtime.prepareNow(config, live(singleStream()), List.of(), 2L),
                        "a new PipeWire connection generation invalidates the prepared graph");
                config.put("active_route", "other");
                DynamicRouteRuntime.Prepared other = runtime.prepareNow(config, live(singleStream()), List.of(), 1L);
                assertEquals("other", other.routeId(), "a different active route is prepared on its own");
            } finally { runtime.close(); }
        }
    }

    @Test void newRevisionLevelChangeAndDiscoveryChangeReachTheCompiledGain() throws Exception {
        try (ConfigStore store = configuredStore()) {
            DynamicRouteRuntime runtime = new DynamicRouteRuntime(store);
            try {
                String gainId = DynamicInstanceRegistry.stableNodeId(GENERATOR, MUSIC_KEY, "gain");
                VersionedConfig first = store.snapshot();
                DynamicRouteRuntime.Prepared prepared = runtime.prepareNow(first, live(singleStream()), List.of(), 1L);
                assertEquals(0.5, compiledGain(prepared, gainId), 1e-9);

                store.updateAsync(config -> applicationRule(config, MUSIC_KEY).put("volume", 0.25)).join();
                VersionedConfig updated = store.snapshot();
                DynamicRouteRuntime.Prepared afterSave = runtime.prepareNow(updated, live(singleStream()), List.of(), 1L);
                assertEquals(0.25, compiledGain(afterSave, gainId), 1e-9,
                        "a saved rule change on a new revision reaches the compiled gain");

                DynamicRouteRuntime.Prepared afterDiscovery = runtime.prepareNow(updated,
                        live(singleStream(), secondStream()), List.of(), 1L);
                assertEquals(2, afterDiscovery.reconcile().instances().size(),
                        "a newly discovered stream invalidates the cached projection");
            } finally { runtime.close(); }
        }
    }

    @Test void preparedCacheExpiresAfterTheFallbackWindowWithoutExtendingOnHits() throws Exception {
        try (ConfigStore store = configuredStore()) {
            DynamicRouteRuntime runtime = new DynamicRouteRuntime(store);
            try {
                AtomicLong now = new AtomicLong(1_000);
                runtime.clock(now::get);
                VersionedConfig config = store.snapshot();
                DynamicRouteRuntime.Prepared first = runtime.prepareNow(config, live(singleStream()), List.of(), 7L);
                now.addAndGet(4_999);
                assertSame(first, runtime.prepareNow(config, live(singleStream()), List.of(), 7L),
                        "a hit inside the fallback window reuses the prepared graph");
                now.addAndGet(2);
                assertNotSame(first, runtime.prepareNow(config, live(singleStream()), List.of(), 7L),
                        "hits never extend the five-second fallback window");
            } finally { runtime.close(); }
        }
    }

    @Test void closingTheRuntimeDropsCachedGraphs() throws Exception {
        try (ConfigStore store = configuredStore()) {
            DynamicRouteRuntime runtime = new DynamicRouteRuntime(store);
            VersionedConfig config = store.snapshot();
            runtime.prepareNow(config, live(singleStream()), List.of(), 1L);
            runtime.close();
            DynamicRouteRuntime.Prepared fresh = runtime.prepareNow(config, live(singleStream()), List.of(), 1L);
            assertNotNull(fresh.reconcile(), "a closed runtime keeps no cached projection");
        }
    }

    @SuppressWarnings("unchecked")
    private ConfigStore configuredStore() throws Exception {
        Path file = temp.resolve("config.json");
        ConfigStore store = new ConfigStore(file);
        Map<String, Object> seed = store.snapshot();
        AudioGraph graph = graph("main", "Main");
        GraphRouteLibrary.saveRoute(seed, graph, "");
        GraphRouteLibrary.saveRoute(seed, graph("other", "Other"), "");
        GraphRouteLibrary.activate(seed, "main");
        // The fixture replaces the installed default route, so its presentation pins no longer resolve.
        ((Map<String, Object>) seed.get("quick_settings")).put("pins", new ArrayList<>());
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(DefaultConfig.map("id", "rule-music", "match", DefaultConfig.map("app_id", "org.example.Music"),
                "volume", 0.5, "mute", false));
        seed.put("applications", rules);
        store.saveAsync(seed).join();
        return store;
    }

    private static AudioGraph graph(String id, String name) {
        DynamicGeneratorNode generator = new DynamicGeneratorNode(GENERATOR, "Applications",
                "application:unassigned", STEREO, Map.of("registry", "applications",
                "connect_to", Map.of("node_id", "mixer", "port_id", "applications")));
        MixerNode mixer = new MixerNode("mixer", "Application Merge", STEREO,
                Map.of("applications", 1.0, "additional", 1.0));
        // One static source keeps the route activatable; generated instances provide the PCM sources.
        SourceNode events = new SourceNode("events", "Events", "application:events", STEREO);
        OutputNode output = new OutputNode("out", "Monitor", "monitor", STEREO);
        return new AudioGraph(id, name, List.of(generator, mixer, events, output), List.of(
                new GraphConnection("events-mixer", "events", "out", "mixer", "additional", true, 1.0),
                new GraphConnection("mixer-out", "mixer", "out", "out", "in", true, 1.0)));
    }

    private static PipeWireBackend.Snapshot live(AudioNode... streams) {
        return new PipeWireBackend.Snapshot(true, "", List.of(), List.of(), List.of(streams), List.of(),
                Map.of(), -1, 1L);
    }

    private static AudioNode singleStream() {
        return stream(90, "Music", "org.example.Music", "pw-play");
    }

    private static AudioNode secondStream() {
        return stream(91, "Podcast", "org.example.Podcast", "pw-play");
    }

    private static AudioNode stream(int id, String applicationName, String applicationId, String process) {
        return new AudioNode(id, "stream-" + id, applicationName, "Stream/Output/Audio", "running",
                applicationName, process, applicationId, 2, List.of(48_000), 0.8, false, null);
    }

    private static String gainName(DynamicRouteRuntime.Prepared prepared, String gainId) {
        return prepared.reconcile().effectiveGraph().nodes().stream()
                .filter(node -> gainId.equals(node.id())).findFirst().orElseThrow().name();
    }

    private static double compiledGain(DynamicRouteRuntime.Prepared prepared, String gainId) {
        return prepared.reconcile().effectiveGraph().nodes().stream()
                .filter(GainNode.class::isInstance).map(GainNode.class::cast)
                .filter(node -> gainId.equals(node.id())).findFirst().orElseThrow().gain();
    }

    @SuppressWarnings("unchecked")
    private static void putInstanceOverride(Map<String, Object> config, String instanceKey, String displayName) {
        Map<String, Object> route = ((List<Map<String, Object>>) config.get("route_library")).stream()
                .filter(item -> "main".equals(item.get("id"))).findFirst().orElseThrow();
        Map<String, Object> generator = ((List<Map<String, Object>>) route.get("nodes")).stream()
                .filter(node -> GENERATOR.equals(node.get("id"))).findFirst().orElseThrow();
        Map<String, Object> parameters = (Map<String, Object>) generator.computeIfAbsent("parameters",
                ignored -> new LinkedHashMap<String, Object>());
        Map<String, Object> instances = (Map<String, Object>) parameters.computeIfAbsent("instances",
                ignored -> new LinkedHashMap<String, Object>());
        Map<String, Object> record = (Map<String, Object>) instances.computeIfAbsent(instanceKey,
                ignored -> new LinkedHashMap<String, Object>());
        record.put("node_ids", new LinkedHashMap<>(Map.of(
                "source", DynamicInstanceRegistry.stableNodeId(GENERATOR, instanceKey, "source"),
                "gain", DynamicInstanceRegistry.stableNodeId(GENERATOR, instanceKey, "gain"))));
        record.put("composite_id", DynamicInstanceRegistry.stableCompositeId(GENERATOR, instanceKey));
        record.put("overrides", new LinkedHashMap<>(Map.of("display_name", displayName)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> applicationRule(Map<String, Object> config, String applicationId) {
        List<Map<String, Object>> rules = (List<Map<String, Object>>) config.computeIfAbsent("applications",
                ignored -> new ArrayList<Map<String, Object>>());
        return rules.stream().filter(rule -> "rule-music".equals(rule.get("id"))).findFirst().orElseThrow();
    }
}
