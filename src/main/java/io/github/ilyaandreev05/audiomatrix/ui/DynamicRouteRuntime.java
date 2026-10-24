package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.*;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.config.VersionedConfig;
import io.github.ilyaandreev05.audiomatrix.gnome.BrowserAdapter;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import static io.github.ilyaandreev05.audiomatrix.ui.DynamicRouteSupport.*;

/** Reconciles live bindings into one effective graph and derives the runtime capture bindings. */
final class DynamicRouteRuntime implements AutoCloseable {
    private static final long FALLBACK_MILLIS = 5_000;
    record Prepared(String routeId, DynamicInstanceRegistry.ReconcileResult reconcile, Map<String,Object> runtimeConfig, Map<String,CapturedStreamBinding> bindings, String signature, AudioGraph baseGraph, List<Map<String,Object>> newRules) {}
    /** Revision- and mutation-aware projection cache; only used for versioned configuration. */
    private record Cached(String input, long configRevision, String sourceConfigFingerprint, long at,
                          DynamicInstanceRegistry.ReconcileResult result) {}
    /** One-entry prepared-graph cache; a map per route would only retain stale runtime graphs. */
    private record PreparedCache(String input, String signature, long configRevision,
                                 String sourceConfigFingerprint, long generation, String routeId, long at,
                                 Prepared prepared) {}
    /** Everything the signature and the reconciler need, computed once before any graph decode. */
    private record DraftInput(String routeId, String input, String signature, Map<String,Object> ruleConfig,
                              RuleDiscovery discovery) {}
    private record SnapshotSet(Map<String,List<DynamicInstanceRegistry.InstanceSnapshot>> byGenerator, Map<String,String> parents) {}
    private record Draft(String routeId, AudioGraph base, DynamicInstanceRegistry.ReconcileResult result, String signature, String input, Map<String,Object> compileBase, List<Map<String,Object>> newRules) {}
    private record RuleDiscovery(List<Map<String,Object>> newRules, Map<String,String> ruleIds) {}
    private final DynamicRegistryStore registryStore;
    private final ConcurrentMap<String,Cached> cache = new ConcurrentHashMap<>();
    private volatile PreparedCache preparedCache;
    private volatile LongSupplier clock = System::currentTimeMillis;
    private volatile List<BrowserAdapter> adapters = List.of();
    private volatile Runnable browserChange = () -> {};
    private volatile String diagnostic = "";
    private final Consumer<BrowserAdapter.BrowserEvent> listener = event -> browserChange.run();
    private boolean listening;
    private volatile boolean closed;

    DynamicRouteRuntime(ConfigStore store) { registryStore = new DynamicRegistryStore(store); }
    String diagnostic() { return diagnostic; }
    synchronized void watch(List<BrowserAdapter> shared, Runnable changed) { browserChange = changed == null ? () -> {} : changed; if (!listening) { adapters = shared == null ? List.of() : List.copyOf(shared); adapters.forEach(a -> a.addListener(listener)); listening = true; } }
    @Override public synchronized void close() {
        if (listening) adapters.forEach(a -> a.removeListener(listener));
        closed = true;
        listening = false; browserChange = () -> {}; preparedCache = null; cache.clear();
    }
    /** Test seam for the five-second fallback boundary; production always uses wall time. */
    void clock(LongSupplier source) { clock = source == null ? System::currentTimeMillis : source; }

    Prepared prepareNow(Map<String,Object> config, PipeWireBackend.Snapshot snapshot, List<BrowserAdapter> shared, long generation) {
        DraftInput input = draftInput(config, snapshot, shared, generation);
        if (!(config instanceof VersionedConfig)) return prepare(input, snapshot, shared, generation, null);
        CacheKey key = cacheKey(config);
        PreparedCache cached = preparedCache;
        long now = clock.getAsLong();
        if (cached != null && cached.input().equals(input.input()) && cached.signature().equals(input.signature())
                && cached.configRevision() == key.revision() && cached.sourceConfigFingerprint().equals(key.fingerprint())
                && cached.generation() == generation && cached.routeId().equals(input.routeId())
                && now - cached.at() < FALLBACK_MILLIS) return cached.prepared();
        Prepared prepared = prepare(input, snapshot, shared, generation, key);
        // A miss must not extend the fallback window of the previous entry; it is simply replaced.
        preparedCache = new PreparedCache(input.input(), input.signature(), key.revision(), key.fingerprint(),
                generation, input.routeId(), now, prepared);
        return prepared;
    }

    /** Current discovery projection without persisting generated children or compiling runtime links. */
    DynamicInstanceRegistry.ReconcileResult reconcileNow(Map<String,Object> config,
            PipeWireBackend.Snapshot snapshot, List<BrowserAdapter> shared, long generation) {
        return draft(config, snapshot, shared, generation).result;
    }
    String signature(Map<String,Object> config, PipeWireBackend.Snapshot snapshot, List<BrowserAdapter> shared, long generation) { return draftInput(config, snapshot, shared, generation).signature(); }
    CompletableFuture<DynamicInstanceRegistry.ReconcileResult> reconcileOnly(Map<String,Object> config, PipeWireBackend.Snapshot snapshot, List<BrowserAdapter> shared, long generation) {
        // The captured snapshot supplies scope and revision; a newer store revision is never substituted.
        return CompletableFuture.supplyAsync(() -> draft(config, snapshot, shared, generation)).thenCompose(d -> persist(config, d, true).thenApply(ignored -> d.result));
    }
    CompletableFuture<Void> persist(Map<String,Object> config, Prepared p, boolean enabled) {
        return persist(config, new Draft(p.routeId, p.baseGraph, p.reconcile, p.signature, "", Map.of(), p.newRules), enabled);
    }
    private CompletableFuture<Void> persist(Map<String,Object> config, Draft d, boolean enabled) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Audio Matrix runtime is closed"));
        return registryStore.persist(config, d.routeId, d.base, d.result, d.newRules, enabled);
    }

    private Prepared prepare(DraftInput input, PipeWireBackend.Snapshot snapshot, List<BrowserAdapter> shared,
                             long generation, CacheKey key) {
        Draft d = draft(input, snapshot, shared, generation, key);
        Map<String,Object> runtime = d.result == null ? d.compileBase : GraphPipeWireCompiler.compile(d.result, d.compileBase);
        return new Prepared(d.routeId, d.result, runtime, captureBindings(runtime), d.signature, d.base, d.newRules);
    }

    private Draft draft(Map<String,Object> config, PipeWireBackend.Snapshot snapshot, List<BrowserAdapter> shared, long generation) {
        return draft(draftInput(config, snapshot, shared, generation), snapshot, shared, generation,
                config instanceof VersionedConfig ? cacheKey(config) : null);
    }

    private Draft draft(DraftInput in, PipeWireBackend.Snapshot snapshot, List<BrowserAdapter> shared,
                        long generation, CacheKey key) {
        List<BrowserAdapter> browsers = shared == null ? List.of() : shared;
        if (in.routeId().isBlank()) return new Draft("", null, null, in.signature(), in.input(), in.ruleConfig(), in.discovery().newRules());
        Map<String,Object> route = maps(in.ruleConfig().get("route_library")).stream().filter(item -> in.routeId().equals(item.get("id"))).findFirst().orElseThrow(() -> new IllegalArgumentException("Active graph route is missing: " + in.routeId()));
        AudioGraph graph = GraphCodec.fromMap(route);
        long now = clock.getAsLong();
        DynamicInstanceRegistry.ReconcileResult result = null;
        if (key != null) {
            Cached old = cache.get(in.routeId());
            if (old != null && old.input().equals(in.input()) && old.configRevision() == key.revision()
                    && old.sourceConfigFingerprint().equals(key.fingerprint()) && now - old.at() < FALLBACK_MILLIS)
                result = old.result();
        }
        if (result == null) {
            SnapshotSet observed = observations(graph, in.ruleConfig(), snapshot, browsers, in.discovery().ruleIds());
            result = DynamicInstanceRegistry.reconcile(graph, observed.byGenerator, observed.parents,
                    (selector, instance) -> applicationGain(in.ruleConfig(), instance),
                    (endpoint, observation) -> endpointGain(in.ruleConfig(), endpoint));
            if (key != null)
                cache.put(in.routeId(), new Cached(in.input(), key.revision(), key.fingerprint(), now, result));
        }
        Map<String,Object> base = configWithGraph(in.ruleConfig(), in.routeId(), result.configuredGraph());
        return new Draft(in.routeId(), graph, result, in.signature(), in.input(), base, in.discovery().newRules());
    }

    private DraftInput draftInput(Map<String,Object> config, PipeWireBackend.Snapshot snapshot,
                                  List<BrowserAdapter> shared, long generation) {
        List<BrowserAdapter> browsers = shared == null ? List.of() : shared;
        Map<String,Object> ruleConfig = new LinkedHashMap<>(config);
        ruleConfig.put("applications", mutableMaps(config.get("applications")));
        // Discovery must run before the signatures are taken: its inserted rules are part of them.
        RuleDiscovery discovery = discoverRules(ruleConfig, snapshot);
        String input = stateSignature(config, snapshot, browsers, generation);
        String signature = stateSignature(ruleConfig, snapshot, browsers, generation);
        return new DraftInput(selectedRoute(config), input, signature, ruleConfig, discovery);
    }

    private record CacheKey(long revision, String fingerprint) {}

    /** Version plus a content fingerprint of the original config, which still carries instances. */
    private static CacheKey cacheKey(Map<String,Object> config) {
        long revision = ((VersionedConfig) config).revision();
        String fingerprint = RouteInputSignature.create(config, null, 0) + ":"
                + RouteInputSignature.dynamicLevels(config);
        return new CacheKey(revision, fingerprint);
    }

    private static RuleDiscovery discoverRules(Map<String,Object> config, PipeWireBackend.Snapshot snapshot) {
        List<Map<String,Object>> added = new ArrayList<>(); Map<String,String> ids = new LinkedHashMap<>();
        for (AudioNode stream : snapshot.streams()) if (!excluded(stream)) {
            DynamicInstanceRegistry.InstanceSnapshot app = DynamicInstanceRegistry.application(stream);
            if (app.confidence() != DynamicInstanceRegistry.Confidence.HIGH) continue;
            Optional<DynamicInstanceRegistry.ApplicationRuleResolution> resolution = DynamicInstanceRegistry.ensureApplicationRule(config, app);
            if (resolution.isEmpty()) continue;
            DynamicInstanceRegistry.ApplicationRuleResolution rule = resolution.get();
            ids.put(applicationKey(stream), rule.ruleId());
            if (rule.inserted()) maps(config.get("applications")).stream().filter(item -> rule.ruleId().equals(item.get("id"))).findFirst()
                    .ifPresent(item -> added.add(new LinkedHashMap<>(item)));
        }
        return new RuleDiscovery(List.copyOf(added), Map.copyOf(ids));
    }

    private SnapshotSet observations(AudioGraph graph, Map<String,Object> config, PipeWireBackend.Snapshot snapshot, List<BrowserAdapter> adapters, Map<String,String> ruleIds) {
        List<DynamicInstanceRegistry.InstanceSnapshot> apps = new ArrayList<>();
        List<AudioNode> live = snapshot.streams().stream().filter(s -> !excluded(s)).sorted(Comparator.comparing(AudioNode::applicationId)
                .thenComparing(AudioNode::processName).thenComparing(AudioNode::applicationName)).toList();
        for (AudioNode stream : live) {
            String ruleId = ruleIds.getOrDefault(applicationKey(stream), "");
            BrowserAdapter browser = adapters.stream().filter(a -> a.matches(stream.applicationId(), stream.processName(), stream.applicationName())).findFirst().orElse(null);
            if (browser == null || (stream.applicationId().isBlank() && stream.processName().isBlank())) apps.add(withRuleId(DynamicInstanceRegistry.application(stream), ruleId));
            else {
                apps.add(withRuleId(DynamicInstanceRegistry.application(stream, browser.id(), browser.displayName()), ruleId));
                apps.add(withRuleId(DynamicInstanceRegistry.browserParentApplication(browser.id(), stream.applicationId(), stream.processName(), browser.displayName()), ruleId));
            }
        }
        Map<String,List<DynamicInstanceRegistry.InstanceSnapshot>> result = new LinkedHashMap<>();
        List<Map<String,Object>> devices = maps(config.get("devices")); diagnostic = hapticDiagnostic(devices);
        List<DynamicGeneratorNode> generators = graph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance).map(DynamicGeneratorNode.class::cast).toList();
        for (DynamicGeneratorNode generator : generators) {
            List<DynamicInstanceRegistry.InstanceSnapshot> items = new ArrayList<>();
            if ("applications".equals(generator.registry())) {
                items.addAll(apps);
                // The session system input is routed like an application, but it is not one: it
                // carries the sounds that no application stream owns, so it gets a single instance.
                if (systemInputPresent(snapshot)) items.add(DynamicInstanceRegistry.systemInput());
            }
            else if ("outputs".equals(generator.registry())) for (AudioNode sink : snapshot.sinks()) {
                if (AudioMatrixOwnedStream.matches(sink)) continue;
                String role = exactRole(sink, devices), fingerprint = sink.identity() == null ? "" : sink.identity().fingerprint();
                // A new physical endpoint is never selected by discovery. Saved instance choices
                // are restored by the registry's stable identity policy, not by a hardware name.
                items.add(DynamicInstanceRegistry.output(sink, false, endpointGain(config, "device:" + fingerprint), role));
            } else if ("browser_tabs".equals(generator.registry())) {
                items.addAll(DynamicBrowserTabSnapshots.collect(graph.id(),config,generator,snapshot,adapters,ruleIds));
            }
            result.put(generator.id(), List.copyOf(items));
        }
        return new SnapshotSet(result, parentComposites(generators, apps));
    }

    /** The session default sink whose monitor Audio Matrix routes into the system input instance. */
    private static boolean systemInputPresent(PipeWireBackend.Snapshot snapshot) {
        return snapshot.sinks().stream()
                .anyMatch(sink -> PipeWireBackend.SYSTEM_MIXER_SINK_NAME.equals(sink.name()));
    }


    private static Map<String,String> parentComposites(List<DynamicGeneratorNode> generators, List<DynamicInstanceRegistry.InstanceSnapshot> apps) {
        List<DynamicGeneratorNode> tabs = generators.stream().filter(g -> "browser_tabs".equals(g.registry())).toList();
        Map<String,DynamicInstanceRegistry.InstanceSnapshot> parents = apps.stream().filter(a -> a.stableKey().startsWith("browser-parent:"))
                .collect(java.util.stream.Collectors.toMap(DynamicInstanceRegistry.InstanceSnapshot::stableKey, a -> a, (a,b) -> b));
        if (tabs.size() != 1 || parents.size() != 1) return Map.of();
        DynamicInstanceRegistry.InstanceSnapshot parent = parents.values().iterator().next();
        List<DynamicGeneratorNode> owners = generators.stream().filter(g -> "applications".equals(g.registry())).filter(g -> accepts(g.selector(), parent)).toList();
        return owners.size() == 1 ? Map.of(tabs.getFirst().id(), DynamicInstanceRegistry.stableCompositeId(owners.getFirst().id(), parent.stableKey())) : Map.of();
    }
    private static boolean accepts(String selector, DynamicInstanceRegistry.InstanceSnapshot p) {
        Map<String,Object> b = p.runtimeBinding(); return Set.of("application:*", "application:unassigned", "application:" + text(b.get("applicationId")), "process:" + text(b.get("processName"))).contains(selector);
    }
    static String applicationKey(AudioNode stream) { return text(stream.applicationId()) + "\u0000" + text(stream.processName()); }
    private static DynamicInstanceRegistry.InstanceSnapshot withRuleId(DynamicInstanceRegistry.InstanceSnapshot app, String ruleId) {
        if (ruleId.isBlank()) return app; Map<String,Object> binding = new LinkedHashMap<>(app.runtimeBinding()); binding.put("applicationRuleId", ruleId);
        return new DynamicInstanceRegistry.InstanceSnapshot(app.registry(), app.stableKey(), app.displayName(), app.selector(), app.channels(), binding, app.confidence());
    }
    static boolean excluded(AudioNode stream) { return !ApplicationStreamEligibility.isEligibleApplicationStream(stream); }

}
