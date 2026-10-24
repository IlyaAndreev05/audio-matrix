package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.*;
import io.github.ilyaandreev05.audiomatrix.gnome.BrowserAdapter;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import java.util.*;

/** Pure projections and diagnostics used while preparing one runtime route graph. */
final class DynamicRouteSupport {
    private DynamicRouteSupport() {
    }

    static DynamicInstanceRegistry.GainState applicationGain(Map<String,Object> config, DynamicInstanceRegistry.InstanceSnapshot instance) {
        Map<String,Object> b = instance.runtimeBinding(); String app = text(b.get("applicationId")), process = text(b.get("processName"));
        for (Map<String,Object> rule : maps(config.get("applications"))) { Map<String,Object> m = object(rule.get("match")); Object id = m.get("app_id"), proc = m.get("process");
            boolean exact = id instanceof String value && !value.isBlank() ? value.equals(app) && (!(proc instanceof String p) || p.isBlank() || p.equals(process)) : proc instanceof String p && !p.isBlank() && p.equals(process);
            if (exact) return new DynamicInstanceRegistry.GainState(level(rule.get("volume"), 1), Boolean.TRUE.equals(rule.get("mute")));
        }
        return null;
    }
    static DynamicInstanceRegistry.GainState endpointGain(Map<String,Object> config, String key) {
        String fingerprint = key.startsWith("device:") ? key.substring(7) : key;
        List<Map<String,Object>> found = maps(config.get("devices")).stream().filter(d -> d.get("identity") instanceof Map<?,?> raw && DeviceIdentity.fromMap(object(raw)).reliable()
                && fingerprint.equals(DeviceIdentity.fromMap(object(raw)).fingerprint())).toList();
        if (found.size() != 1) return null;
        Map<String,Object> d = found.getFirst(); return new DynamicInstanceRegistry.GainState(level(d.get("volume"), 1), Boolean.TRUE.equals(d.get("mute")));
    }
    static String exactRole(AudioNode sink, List<Map<String,Object>> devices) {
        if (sink.identity() == null || !sink.identity().reliable() || devices.stream().filter(d -> "haptic_output".equals(d.get("role"))).count() != 1) return "";
        List<Map<String,Object>> found = devices.stream().filter(d -> d.get("identity") instanceof Map<?,?> raw && DeviceIdentity.fromMap(object(raw)).reliable()
                && sink.identity().fingerprint().equals(DeviceIdentity.fromMap(object(raw)).fingerprint())).toList();
        return found.size() == 1 && "haptic_output".equals(found.getFirst().get("role")) ? "haptic_output" : "";
    }
    static String hapticDiagnostic(List<Map<String,Object>> devices) {
        List<Map<String,Object>> records = devices.stream().filter(d -> "haptic_output".equals(d.get("role"))).toList();
        if (records.size() > 1) return "Haptic output unavailable: multiple device records have the haptic_output role; no device was selected.";
        if (records.isEmpty()) return "";
        Object value = records.getFirst().get("identity");
        if (!(value instanceof Map<?,?> raw) || !DeviceIdentity.fromMap(object(raw)).reliable()) return "Haptic output unavailable: its saved device identity is missing or unreliable; no fallback was selected.";
        DeviceIdentity id = DeviceIdentity.fromMap(object(raw));
        long count = devices.stream().filter(d -> d.get("identity") instanceof Map<?,?> identity && DeviceIdentity.fromMap(object(identity)).reliable() && id.fingerprint().equals(DeviceIdentity.fromMap(object(identity)).fingerprint())).count();
        return count == 1 ? "" : "Haptic output unavailable: its reliable device identity is ambiguous across saved devices; no fallback was selected.";
    }

    static Map<String,CapturedStreamBinding> captureBindings(Map<String,Object> runtime) {
        Map<String,CapturedStreamBinding> result = new LinkedHashMap<>();
        for (Map<String,Object> item : maps(runtime.get("graph_capture_sources"))) {
            String key = text(item.get("capture_stream_key")), name = text(item.get("node_name"));
            if (!key.isBlank() && name.equals("audio-matrix-chrome-" + key)) result.put(name, new CapturedStreamBinding(name, text(item.get("source_id")), text(item.get("application_rule_id")),
                    text(item.get("parent_application_id")), text(item.get("parent_process_name")), text(item.get("instance_key")), level(item.get("tab_gain"), 1), Boolean.TRUE.equals(item.get("tab_muted"))));
        }
        return Map.copyOf(result);
    }

    static Map<String,Object> configWithGraph(Map<String,Object> config, String routeId, AudioGraph graph) {
        Map<String,Object> result = new LinkedHashMap<>(config); List<Map<String,Object>> routes = mutableMaps(config.get("route_library"));
        Map<String,Object> route = routes.stream().filter(r -> routeId.equals(r.get("id"))).findFirst().orElse(null);
        if (route != null) { Map<String,Object> encoded = GraphCodec.toMap(graph); route.put("nodes", encoded.get("nodes")); route.put("connections", encoded.get("connections")); result.put("route_library", routes); }
        return result;
    }

    static String stateSignature(Map<String,Object> config, PipeWireBackend.Snapshot snapshot, List<BrowserAdapter> adapters, long generation) {
        List<String> browser = adapters.stream().flatMap(a -> a.snapshots().stream().flatMap(b -> b.tabs().stream().map(tab ->
                String.join("\u0000", a.id(), b.instanceId(), b.profileId(), tab.sessionToken(), tab.title(), Boolean.toString(tab.audible()), Boolean.toString(tab.muted()), tab.volumeCapability(), tab.captureStreamKey())))).sorted().toList();
        Map<String,Object> stable = DynamicRegistryStore.stableConfig(config);
        return RouteInputSignature.create(stable, snapshot, generation) + ":" + RouteInputSignature.dynamicLevels(stable) + ":" + RouteInputSignature.dynamicBindings(snapshot) + ":" + browser;
    }

    static String selectedRoute(Map<String,Object> config) {
        String active = text(config.get("active_route")); return active.isBlank() ? maps(config.get("profiles")).stream()
                .filter(p -> GraphRouteLibrary.activeProfileId(config).equals(p.get("id"))).map(p -> text(p.get("selected_route_id")))
                .filter(value -> !value.isBlank()).findFirst().orElse("") : active;
    }

    @SuppressWarnings("unchecked") static List<Map<String,Object>> maps(Object value) { return value instanceof List<?> list ? (List<Map<String,Object>>)list : List.of(); }
    static List<Map<String,Object>> mutableMaps(Object value) { List<Map<String,Object>> result = new ArrayList<>(); for (Map<String,Object> item : maps(value)) result.add(new LinkedHashMap<>(item)); return result; }
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object value) { return value instanceof Map<?,?> map ? (Map<String,Object>)map : Map.of(); }
    static String text(Object value) { return value instanceof String text ? text : ""; }
    static double level(Object value, double fallback) { return value instanceof Number number && Double.isFinite(number.doubleValue()) ? Math.max(0, Math.min(1, number.doubleValue())) : fallback; }
}
