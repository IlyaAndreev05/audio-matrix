package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.*;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.gnome.BrowserAdapter;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Reconciles live bindings into one effective graph and derives the runtime capture bindings. */
final class DynamicRouteRuntime implements AutoCloseable {
    private static final String TRUSTED_HDMI = "alsa_output.pci-0000_09_00.1.hdmi-stereo";
    private static final long FALLBACK_MILLIS = 5_000;
    record Prepared(String routeId, DynamicInstanceRegistry.ReconcileResult reconcile, Map<String,Object> runtimeConfig, Map<String,CapturedStreamBinding> bindings, String signature, AudioGraph baseGraph, List<Map<String,Object>> newRules) {}
    private record Cached(String input, long at, DynamicInstanceRegistry.ReconcileResult result) {}
    private record SnapshotSet(Map<String,List<DynamicInstanceRegistry.InstanceSnapshot>> byGenerator, Map<String,String> parents) {}
    private record Draft(String routeId, AudioGraph base, DynamicInstanceRegistry.ReconcileResult result, String signature, String input, Map<String,Object> compileBase, List<Map<String,Object>> newRules) {}
    private record RuleDiscovery(List<Map<String,Object>> newRules, Map<String,String> ruleIds) {}
    private final DynamicRegistryStore registryStore;
    private final ConcurrentMap<String,Cached> cache = new ConcurrentHashMap<>();
    private volatile List<BrowserAdapter> adapters = List.of();
    private volatile Runnable browserChange = () -> {};
    private volatile String diagnostic = "";
    private final Consumer<BrowserAdapter.BrowserEvent> listener = event -> browserChange.run();
    private boolean listening;

    DynamicRouteRuntime(ConfigStore store) { registryStore = new DynamicRegistryStore(store); }
    String diagnostic() { return diagnostic; }
    synchronized void watch(List<BrowserAdapter> shared, Runnable changed) { browserChange = changed == null ? () -> {} : changed; if (!listening) { adapters = shared == null ? List.of() : List.copyOf(shared); adapters.forEach(a -> a.addListener(listener)); listening = true; } }
    @Override public synchronized void close() { if (listening) adapters.forEach(a -> a.removeListener(listener)); listening = false; browserChange = () -> {}; }

    Prepared prepareNow(Map<String,Object> config, PipeWireBackend.Snapshot snapshot, List<BrowserAdapter> shared, long generation) {
        Draft d = draft(config, snapshot, shared, generation); Map<String,Object> runtime = d.result == null ? d.compileBase : GraphPipeWireCompiler.compile(d.result, d.compileBase);
        return new Prepared(d.routeId, d.result, runtime, captureBindings(runtime), d.signature, d.base, d.newRules);
    }
    String signature(Map<String,Object> config, PipeWireBackend.Snapshot snapshot, List<BrowserAdapter> shared, long generation) { return draft(config, snapshot, shared, generation).signature; }
    CompletableFuture<DynamicInstanceRegistry.ReconcileResult> reconcileOnly(Map<String,Object> config, PipeWireBackend.Snapshot snapshot, List<BrowserAdapter> shared, long generation) {
        return CompletableFuture.supplyAsync(() -> draft(config, snapshot, shared, generation)).thenCompose(d -> persist(config, d, true).thenApply(ignored -> d.result));
    }
    CompletableFuture<Void> persist(Map<String,Object> config, Prepared p, boolean enabled) {
        return persist(config, new Draft(p.routeId, p.baseGraph, p.reconcile, p.signature, "", Map.of(), p.newRules), enabled);
    }
    private CompletableFuture<Void> persist(Map<String,Object> config, Draft d, boolean enabled) {
        return registryStore.persist(config, d.routeId, d.base, d.result, d.newRules, enabled);
    }

    private Draft draft(Map<String,Object> config, PipeWireBackend.Snapshot snapshot, List<BrowserAdapter> shared, long generation) {
        List<BrowserAdapter> browsers = shared == null ? List.of() : shared;
        Map<String,Object> ruleConfig = new LinkedHashMap<>(config); ruleConfig.put("applications", mutableMaps(config.get("applications")));
        RuleDiscovery discovery = discoverRules(ruleConfig, snapshot); String input = stateSignature(config, snapshot, browsers, generation), routeId = selectedRoute(config);
        if (routeId.isBlank()) return new Draft("", null, null, stateSignature(ruleConfig, snapshot, browsers, generation), input, ruleConfig, discovery.newRules());
        Map<String,Object> route = maps(config.get("route_library")).stream().filter(item -> routeId.equals(item.get("id"))).findFirst().orElseThrow(() -> new IllegalArgumentException("Active graph route is missing: " + routeId));
        AudioGraph graph = GraphCodec.fromMap(route); Cached old = cache.get(routeId); long now = System.currentTimeMillis();
        DynamicInstanceRegistry.ReconcileResult result = old != null && old.input.equals(input) && now - old.at < FALLBACK_MILLIS ? old.result : null;
        if (result == null) {
            SnapshotSet observed = observations(graph, ruleConfig, snapshot, browsers, discovery.ruleIds());
            result = DynamicInstanceRegistry.reconcile(graph, observed.byGenerator, observed.parents,
                    (selector, instance) -> applicationGain(ruleConfig, instance), (key, observation) -> endpointGain(ruleConfig, key));
            cache.put(routeId, new Cached(input, now, result));
        }
        Map<String,Object> base = configWithGraph(ruleConfig, routeId, result.configuredGraph());
        return new Draft(routeId, graph, result, stateSignature(ruleConfig, snapshot, browsers, generation), input, base, discovery.newRules());
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
                boolean selected = generator.autoSelectTrustedHdmiOnFirstDiscovery() && TRUSTED_HDMI.equals(sink.name())
                        && sink.identity() != null && sink.identity().reliable() && !"haptic_output".equals(role) && !"device:role:haptic_output".equals(generator.selector());
                items.add(DynamicInstanceRegistry.output(sink, selected, endpointGain(config, "device:" + fingerprint), role));
            } else if ("browser_tabs".equals(generator.registry())) items.addAll(browserTabs(snapshot, adapters, ruleIds));
            result.put(generator.id(), List.copyOf(items));
        }
        return new SnapshotSet(result, parentComposites(generators, apps));
    }

    /** The session default sink whose monitor Audio Matrix routes into the system input instance. */
    private static boolean systemInputPresent(PipeWireBackend.Snapshot snapshot) {
        return snapshot.sinks().stream()
                .anyMatch(sink -> PipeWireBackend.SYSTEM_MIXER_SINK_NAME.equals(sink.name()));
    }

    private static List<DynamicInstanceRegistry.InstanceSnapshot> browserTabs(PipeWireBackend.Snapshot snapshot, List<BrowserAdapter> adapters, Map<String,String> ruleIds) {
        List<DynamicInstanceRegistry.InstanceSnapshot> result = new ArrayList<>(); List<AudioNode> parents = snapshot.streams().stream().filter(s -> !excluded(s)).toList();
        for (BrowserAdapter a : adapters) for (BrowserAdapter.BrowserSnapshot b : a.snapshots()) {
            if (!a.id().equals(b.browser())) continue;
            AudioNode parent = parents.stream().filter(s -> (!s.applicationId().isBlank() || !s.processName().isBlank())
                    && a.matches(s.applicationId(), s.processName(), s.applicationName())).findFirst().orElse(null);
            String ruleId = parent == null ? "" : ruleIds.getOrDefault(applicationKey(parent), "");
            for (BrowserAdapter.BrowserTab tab : b.tabs()) result.add(DynamicInstanceRegistry.browserTab(a.id(), b.instanceId(), b.profileId(),
                    tab.sessionToken(), tab.tabId(), tab.title(), tab.audible(), tab.muted(), tab.volumeCapability(), tab.captureStreamKey(),
                    ruleId, parent == null ? "" : text(parent.applicationId()), parent == null ? "" : text(parent.processName())));
        }
        return result;
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
    private static String applicationKey(AudioNode stream) { return text(stream.applicationId()) + "\u0000" + text(stream.processName()); }
    private static DynamicInstanceRegistry.InstanceSnapshot withRuleId(DynamicInstanceRegistry.InstanceSnapshot app, String ruleId) {
        if (ruleId.isBlank()) return app; Map<String,Object> binding = new LinkedHashMap<>(app.runtimeBinding()); binding.put("applicationRuleId", ruleId);
        return new DynamicInstanceRegistry.InstanceSnapshot(app.registry(), app.stableKey(), app.displayName(), app.selector(), app.channels(), binding, app.confidence());
    }
    private static boolean excluded(AudioNode stream) { return AudioMatrixOwnedStream.matches(stream) || EventSoundStudioSource.APPLICATION_ID.equals(stream.applicationId()) || EventSoundStudioSource.APPLICATION_ID.equals(stream.processName()); }

    private static DynamicInstanceRegistry.GainState applicationGain(Map<String,Object> config, DynamicInstanceRegistry.InstanceSnapshot instance) {
        Map<String,Object> b = instance.runtimeBinding(); String app = text(b.get("applicationId")), process = text(b.get("processName"));
        for (Map<String,Object> rule : maps(config.get("applications"))) { Map<String,Object> m = object(rule.get("match")); Object id = m.get("app_id"), proc = m.get("process");
            boolean exact = id instanceof String value && !value.isBlank() ? value.equals(app) && (!(proc instanceof String p) || p.isBlank() || p.equals(process)) : proc instanceof String p && !p.isBlank() && p.equals(process);
            if (exact) return new DynamicInstanceRegistry.GainState(level(rule.get("volume"), 1), Boolean.TRUE.equals(rule.get("mute")));
        }
        return null;
    }
    private static DynamicInstanceRegistry.GainState endpointGain(Map<String,Object> config, String key) {
        String fingerprint = key.startsWith("device:") ? key.substring(7) : key;
        List<Map<String,Object>> found = maps(config.get("devices")).stream().filter(d -> d.get("identity") instanceof Map<?,?> raw && DeviceIdentity.fromMap(object(raw)).reliable()
                && fingerprint.equals(DeviceIdentity.fromMap(object(raw)).fingerprint())).toList();
        if (found.size() != 1) return null;
        Map<String,Object> d = found.getFirst(); return new DynamicInstanceRegistry.GainState(level(d.get("volume"), 1), Boolean.TRUE.equals(d.get("mute")));
    }
    private static String exactRole(AudioNode sink, List<Map<String,Object>> devices) {
        if (sink.identity() == null || !sink.identity().reliable() || devices.stream().filter(d -> "haptic_output".equals(d.get("role"))).count() != 1) return "";
        List<Map<String,Object>> found = devices.stream().filter(d -> d.get("identity") instanceof Map<?,?> raw && DeviceIdentity.fromMap(object(raw)).reliable()
                && sink.identity().fingerprint().equals(DeviceIdentity.fromMap(object(raw)).fingerprint())).toList();
        return found.size() == 1 && "haptic_output".equals(found.getFirst().get("role")) ? "haptic_output" : "";
    }
    private static String hapticDiagnostic(List<Map<String,Object>> devices) {
        List<Map<String,Object>> records = devices.stream().filter(d -> "haptic_output".equals(d.get("role"))).toList();
        if (records.size() > 1) return "Haptic output unavailable: multiple device records have the haptic_output role; no device was selected.";
        if (records.isEmpty()) return "";
        Object value = records.getFirst().get("identity");
        if (!(value instanceof Map<?,?> raw) || !DeviceIdentity.fromMap(object(raw)).reliable()) return "Haptic output unavailable: its saved device identity is missing or unreliable; no fallback was selected.";
        DeviceIdentity id = DeviceIdentity.fromMap(object(raw));
        long count = devices.stream().filter(d -> d.get("identity") instanceof Map<?,?> identity && DeviceIdentity.fromMap(object(identity)).reliable() && id.fingerprint().equals(DeviceIdentity.fromMap(object(identity)).fingerprint())).count();
        return count == 1 ? "" : "Haptic output unavailable: its reliable device identity is ambiguous across saved devices; no fallback was selected.";
    }

    private static Map<String,CapturedStreamBinding> captureBindings(Map<String,Object> runtime) {
        Map<String,CapturedStreamBinding> result = new LinkedHashMap<>();
        for (Map<String,Object> item : maps(runtime.get("graph_capture_sources"))) {
            String key = text(item.get("capture_stream_key")), name = text(item.get("node_name"));
            if (!key.isBlank() && name.equals("audio-matrix-chrome-" + key)) result.put(name, new CapturedStreamBinding(name, text(item.get("source_id")), text(item.get("application_rule_id")),
                    text(item.get("parent_application_id")), text(item.get("parent_process_name")), text(item.get("instance_key")), level(item.get("tab_gain"), 1), Boolean.TRUE.equals(item.get("tab_muted"))));
        }
        return Map.copyOf(result);
    }

    private static Map<String,Object> configWithGraph(Map<String,Object> config, String routeId, AudioGraph graph) {
        Map<String,Object> result = new LinkedHashMap<>(config); List<Map<String,Object>> routes = mutableMaps(config.get("route_library"));
        Map<String,Object> route = routes.stream().filter(r -> routeId.equals(r.get("id"))).findFirst().orElse(null);
        if (route != null) { Map<String,Object> encoded = GraphCodec.toMap(graph); route.put("nodes", encoded.get("nodes")); route.put("connections", encoded.get("connections")); result.put("route_library", routes); }
        return result;
    }

    private static String stateSignature(Map<String,Object> config, PipeWireBackend.Snapshot snapshot, List<BrowserAdapter> adapters, long generation) {
        List<String> browser = adapters.stream().flatMap(a -> a.snapshots().stream().flatMap(b -> b.tabs().stream().map(tab ->
                String.join("\u0000", a.id(), b.instanceId(), b.profileId(), tab.sessionToken(), tab.title(), Boolean.toString(tab.audible()), Boolean.toString(tab.muted()), tab.volumeCapability(), tab.captureStreamKey())))).sorted().toList();
        Map<String,Object> stable = DynamicRegistryStore.stableConfig(config);
        return RouteInputSignature.create(stable, snapshot, generation) + ":" + RouteInputSignature.dynamicLevels(stable) + ":" + RouteInputSignature.dynamicBindings(snapshot) + ":" + browser;
    }
    private static String selectedRoute(Map<String,Object> config) {
        String active = text(config.get("active_route")); return active.isBlank() ? maps(config.get("profiles")).stream()
                .filter(p -> GraphRouteLibrary.activeProfileId(config).equals(p.get("id"))).map(p -> text(p.get("selected_route_id")))
                .filter(value -> !value.isBlank()).findFirst().orElse("") : active;
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value) { return value instanceof List<?> list ? (List<Map<String,Object>>)list : List.of(); }
    private static List<Map<String,Object>> mutableMaps(Object value) { List<Map<String,Object>> result = new ArrayList<>(); for (Map<String,Object> item : maps(value)) result.add(new LinkedHashMap<>(item)); return result; }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { return value instanceof Map<?,?> map ? (Map<String,Object>)map : Map.of(); }
    private static String text(Object value) { return value instanceof String text ? text : ""; }
    private static double level(Object value, double fallback) { return value instanceof Number number && Double.isFinite(number.doubleValue()) ? Math.max(0, Math.min(1, number.doubleValue())) : fallback; }
}
