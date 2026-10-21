package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.RouterNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Creates and migrates the single active Audio Matrix graph. */
public final class UnifiedGraphConfig {
    public static final String ROUTE_ID = "main";
    public static final String SWITCH_ID = "octine-main-switch";
    public static final String MONITOR_OUTPUT_ID = "octine-monitor-output";
    public static final String MUSIC_GAIN_ID = "octine-vibro-music-volume";
    public static final String EVENT_GAIN_ID = "octine-vibro-events-volume";
    public static final String EVENT_SOURCE_ID = "octine-event-source";
    public static final String LIMITER_ID = "octine-vibro-limiter";

    private UnifiedGraphConfig() {}

    public static AudioGraph defaults() {
        return UnifiedGraphFactory.defaults();
    }

    /** Replaces legacy profile stacks after their full pre-migration copy has been archived. */
    public static void migrate(Map<String, Object> config) {
        UnifiedGraphLegacyMigration.migrate(config);
    }

    public static void installDefault(Map<String, Object> config) {
        AudioGraph graph = defaults();
        installMainGraph(config, graph);
        QuickSettingsConfig.migrate(config);
        UnifiedGraphPins.installDefaults(config, graph);
    }

    static void installMigratedGraph(Map<String, Object> config, AudioGraph graph) {
        installMainGraph(config, graph);
        UnifiedGraphPins.migrate(config, graph);
        QuickSettingsConfig.migrate(config);
    }

    private static void installMainGraph(Map<String, Object> config, AudioGraph graph) {
        Map<String, Object> profile = DefaultConfig.map("id", "main", "name", "Основной",
                "mode", "multi-output", "selected_route_id", ROUTE_ID, "default_output", "",
                "active_buses", new ArrayList<>(), "active_routes", new ArrayList<>(),
                "device_overrides", DefaultConfig.map());
        Map<String, Object> route = new LinkedHashMap<>(GraphCodec.toMap(graph));
        route.put("profile_id", "main");
        route.put("group_id", "main");
        config.put("profiles", new ArrayList<>(List.of(profile)));
        config.put("active_profile", "main");
        config.put("route_groups", new ArrayList<>(List.of(DefaultConfig.map(
                "id", "main", "name", "Основной", "profile_id", "main"))));
        config.put("route_library", new ArrayList<>(List.of(route)));
        config.put("active_route", ROUTE_ID);
    }
}

final class UnifiedGraphLegacyMigration {
    private UnifiedGraphLegacyMigration() {}

    static void migrate(Map<String, Object> config) {
        LegacyLinearScaleMigration.cube(config);
        Map<String, Object> profile = activeProfile(config);
        String profileRoute = String.valueOf(profile.getOrDefault("selected_route_id", ""));
        AudioGraph oldActive = route(config, firstText(config.get("active_route"), profileRoute));
        if (oldActive == null) oldActive = route(config, profileRoute);
        if (oldActive == null) oldActive = route(config, "octine-monitor");
        AudioGraph oldShared = null;
        try { oldShared = GraphCommonVibro.graph(config); }
        catch (RuntimeException ignored) { }

        Set<String> selectedOutputIds = LegacyGraphOutputMigration.selectedOutputDeviceIds(oldActive);
        LegacyGraphOutputMigration.OutputState outputs =
                LegacyGraphOutputMigration.outputs(oldActive, profile, config, selectedOutputIds, oldShared);
        List<String> activeLinks = new ArrayList<>(outputs.activeLinks());
        if (LegacyGraphOutputMigration.hapticMusicSelected(oldShared, config, selectedOutputIds))
            activeLinks.add("in->" + UnifiedGraphFactory.HAPTIC_MUSIC_PORT_ID);

        FilterNode limiter = LegacyGraphFilterMigration.limiter(oldShared, oldActive);
        Set<String> hapticExcluded = new HashSet<>(Set.of(UnifiedGraphConfig.LIMITER_ID));
        if (limiter != null) hapticExcluded.add(limiter.id());
        List<FilterNode> mainFilters = LegacyGraphFilterMigration.preserve(oldActive, hapticExcluded);
        mainFilters.forEach(filter -> hapticExcluded.add(filter.id()));
        List<FilterNode> hapticFilters = LegacyGraphFilterMigration.preserve(oldShared, hapticExcluded);
        UnifiedGraphFactory.Settings settings = new UnifiedGraphFactory.Settings(
                gain(oldShared, UnifiedGraphConfig.MUSIC_GAIN_ID, mixerInput(oldShared, GraphCommonVibro.VIBRO_MIXER_ID, "bass", 0.6)),
                muted(oldShared, UnifiedGraphConfig.MUSIC_GAIN_ID)
                        || mixerInputMuted(oldShared, GraphCommonVibro.VIBRO_MIXER_ID, "bass"),
                mixerInput(oldShared, GraphCommonVibro.VIBRO_MIXER_ID, "events", 0.65),
                mixerInputMuted(oldShared, GraphCommonVibro.VIBRO_MIXER_ID, "events"),
                gain(oldShared, GraphCommonVibro.VIBRO_GAIN_ID, 0.6),
                muted(oldShared, GraphCommonVibro.VIBRO_GAIN_ID),
                mixerInput(oldActive, GraphCommonVibro.MAIN_MIXER_ID, "applications", 1.0),
                mixerInputMuted(oldActive, GraphCommonVibro.MAIN_MIXER_ID, "applications"),
                mixerInput(oldActive, GraphCommonVibro.MAIN_MIXER_ID, "additional", 1.0),
                mixerInputMuted(oldActive, GraphCommonVibro.MAIN_MIXER_ID, "additional"),
                limiter, outputs.instances(), outputs.ports(), activeLinks, mainFilters, hapticFilters, false);
        AudioGraph next = UnifiedGraphFactory.create(settings);
        config.remove("common_vibro_graph");
        config.remove("octine_profiles_available");
        UnifiedGraphConfig.installMigratedGraph(config, next);

        Map<String, Object> compiled = io.github.ilyaandreev05.audiomatrix.graph.GraphPipeWireCompiler.compile(next, config);
        config.clear();
        config.putAll(compiled);
    }

    private static double gain(AudioGraph graph, String nodeId, double fallback) {
        if (graph == null) return fallback;
        return graph.nodes().stream().filter(node -> node.id().equals(nodeId) && node instanceof GainNode)
                .map(GainNode.class::cast).mapToDouble(GainNode::gain).findFirst().orElse(fallback);
    }

    private static boolean muted(AudioGraph graph, String nodeId) {
        if (graph == null) return false;
        return graph.nodes().stream().filter(node -> node.id().equals(nodeId) && node instanceof GainNode)
                .map(GainNode.class::cast).anyMatch(GainNode::muted);
    }

    private static double mixerInput(AudioGraph graph, String nodeId, String input, double fallback) {
        if (graph == null) return fallback;
        return graph.nodes().stream().filter(node -> node.id().equals(nodeId) && node instanceof MixerNode)
                .map(MixerNode.class::cast)
                .mapToDouble(node -> node.inputGains().getOrDefault(input, fallback)).findFirst().orElse(fallback);
    }

    private static boolean mixerInputMuted(AudioGraph graph, String nodeId, String input) {
        if (graph == null) return false;
        return graph.nodes().stream().filter(node -> node.id().equals(nodeId) && node instanceof MixerNode)
                .map(MixerNode.class::cast).anyMatch(node -> node.inputMuted(input));
    }

    private static AudioGraph route(Map<String, Object> config, String id) {
        if (id.isBlank()) return null;
        Map<String, Object> encoded = maps(config.get("route_library")).stream()
                .filter(item -> id.equals(item.get("id"))).findFirst().orElse(null);
        if (encoded == null) return null;
        try { return GraphCodec.fromMap(encoded); }
        catch (RuntimeException ignored) { return null; }
    }

    private static Map<String, Object> activeProfile(Map<String, Object> config) {
        String id = String.valueOf(config.getOrDefault("active_profile", ""));
        return maps(config.get("profiles")).stream().filter(item -> id.equals(item.get("id"))).findFirst()
                .orElseGet(() -> maps(config.get("profiles")).stream().findFirst().orElse(DefaultConfig.map()));
    }

    private static String firstText(Object raw, String fallback) {
        String value = raw == null ? "" : String.valueOf(raw).trim();
        return value.isBlank() ? fallback : value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object raw) {
        return raw instanceof List<?> list ? (List<Map<String, Object>>) list : new ArrayList<>();
    }
}

final class LegacyGraphOutputMigration {
    private LegacyGraphOutputMigration() {}

    static OutputState outputs(AudioGraph graph, Map<String, Object> profile, Map<String, Object> config,
                               Set<String> selectedDeviceIds, AudioGraph oldShared) {
        Map<String, Object> instances = new LinkedHashMap<>();
        Set<String> ports = new LinkedHashSet<>();
        Set<String> links = new LinkedHashSet<>();
        Set<String> hapticRefs = hapticDeviceRefs(graph, oldShared);
        boolean hasRouter = graph != null && graph.nodes().stream().anyMatch(RouterNode.class::isInstance);
        if (graph != null) for (GraphNode node : graph.nodes()) {
            String deviceId = deviceId(node);
            if (deviceId.isBlank()) continue;
            LegacyGraphDeviceBindings.preserveSlotLevels(config, deviceId, node);
            addOutput(deviceId, config, hapticRefs, instances, ports, links,
                    selectedDeviceIds.contains(LegacyGraphDeviceBindings.normalize(deviceId)));
        }
        String defaultOutput = String.valueOf(profile.getOrDefault("default_output", ""));
        if (!defaultOutput.isBlank() && !"system_default".equals(defaultOutput)) {
            boolean selected = !hasRouter && selectedDeviceIds.isEmpty();
            addOutput(defaultOutput, config, hapticRefs, instances, ports, links, selected);
        }
        return new OutputState(instances, List.copyOf(ports), List.copyOf(links));
    }

    private static void addOutput(String deviceId, Map<String, Object> config, Set<String> hapticRefs,
                                  Map<String, Object> instances, Set<String> ports, Set<String> links,
                                  boolean selected) {
        LegacyGraphDeviceBindings.DeviceBinding binding = LegacyGraphDeviceBindings.find(config, deviceId);
        if (binding == null || binding.identity() == null || !binding.identity().reliable()
                || "haptic_output".equals(binding.role())
                || hapticRefs.contains(LegacyGraphDeviceBindings.normalize(deviceId))) return;
        String fingerprint = binding.identity().fingerprint();
        String stableKey = "device:" + fingerprint;
        instances.putIfAbsent(stableKey,
                DynamicInstanceRegistry.seedOutputInstanceRecord(UnifiedGraphConfig.MONITOR_OUTPUT_ID, binding.identity()));
        String portId = UnifiedGraphFactory.outputPortId(fingerprint);
        ports.add(portId);
        if (selected) links.add("in->" + portId);
    }

    static Set<String> selectedOutputDeviceIds(AudioGraph graph) {
        if (graph == null) return Set.of();
        Map<String, GraphNode> nodes = new HashMap<>();
        graph.nodes().forEach(node -> nodes.put(node.id(), node));
        Set<String> selected = new LinkedHashSet<>();
        for (GraphNode node : graph.nodes()) if (node instanceof SourceNode) {
            for (GraphConnection edge : outgoing(graph, node.id()))
                visitSelected(graph, nodes, edge.toNode(), edge.toPort(), new HashSet<>(), selected);
        }
        return Set.copyOf(selected);
    }

    private static void visitSelected(AudioGraph graph, Map<String, GraphNode> nodes, String nodeId,
                                      String incomingPort, Set<String> path, Set<String> outputIds) {
        if (!path.add(nodeId)) return;
        GraphNode node = nodes.get(nodeId);
        if (node == null) return;
        if (node instanceof DeviceSlotNode slot) {
            if (!slot.deviceId().isBlank()) outputIds.add(LegacyGraphDeviceBindings.normalize(slot.deviceId()));
            return;
        }
        if (node instanceof OutputNode output) {
            if (!output.deviceId().isBlank()) outputIds.add(LegacyGraphDeviceBindings.normalize(output.deviceId()));
            return;
        }
        for (GraphConnection edge : outgoing(graph, nodeId)) {
            if (node instanceof RouterNode router
                    && !router.activeLinks().contains(incomingPort + "->" + edge.fromPort())) continue;
            visitSelected(graph, nodes, edge.toNode(), edge.toPort(), new HashSet<>(path), outputIds);
        }
    }

    static boolean hapticMusicSelected(AudioGraph shared, Map<String, Object> config,
                                       Set<String> selectedOutputIds) {
        for (String selectedId : selectedOutputIds) {
            LegacyGraphDeviceBindings.DeviceBinding binding = LegacyGraphDeviceBindings.find(config, selectedId);
            if (isAssignedHaptic(binding)) return true;
        }
        String sharedOutput = deviceId(shared == null ? null : shared.nodes().stream()
                .filter(node -> GraphCommonVibro.VIBRO_OUTPUT_ID.equals(node.id())).findFirst().orElse(null));
        return !sharedOutput.isBlank()
                && isAssignedHaptic(LegacyGraphDeviceBindings.find(config, sharedOutput))
                && hasSelectedPath(shared, GraphCommonVibro.MAIN_SOURCE_ID, GraphCommonVibro.VIBRO_OUTPUT_ID);
    }

    private static boolean isAssignedHaptic(LegacyGraphDeviceBindings.DeviceBinding binding) {
        return binding != null && "haptic_output".equals(binding.role())
                && binding.identity() != null && binding.identity().reliable();
    }

    private static boolean hasSelectedPath(AudioGraph graph, String sourceId, String targetId) {
        if (graph == null) return false;
        Map<String, GraphNode> nodes = new HashMap<>();
        graph.nodes().forEach(node -> nodes.put(node.id(), node));
        for (GraphConnection edge : outgoing(graph, sourceId))
            if (visitTarget(graph, nodes, edge.toNode(), edge.toPort(), targetId, new HashSet<>())) return true;
        return false;
    }

    private static boolean visitTarget(AudioGraph graph, Map<String, GraphNode> nodes, String nodeId,
                                       String incomingPort, String targetId, Set<String> path) {
        if (nodeId.equals(targetId)) return true;
        if (!path.add(nodeId)) return false;
        GraphNode node = nodes.get(nodeId);
        if (node == null) return false;
        for (GraphConnection edge : outgoing(graph, nodeId)) {
            if (node instanceof RouterNode router
                    && !router.activeLinks().contains(incomingPort + "->" + edge.fromPort())) continue;
            if (visitTarget(graph, nodes, edge.toNode(), edge.toPort(), targetId, new HashSet<>(path))) return true;
        }
        return false;
    }

    private static Set<String> hapticDeviceRefs(AudioGraph first, AudioGraph second) {
        Set<String> refs = new HashSet<>();
        for (AudioGraph graph : new AudioGraph[]{first, second}) {
            if (graph == null) continue;
            graph.nodes().stream().filter(node -> GraphCommonVibro.VIBRO_OUTPUT_ID.equals(node.id()))
                    .map(LegacyGraphOutputMigration::deviceId).filter(id -> !id.isBlank())
                    .map(LegacyGraphDeviceBindings::normalize).forEach(refs::add);
        }
        return refs;
    }

    private static String deviceId(GraphNode node) {
        if (node instanceof DeviceSlotNode slot) return slot.deviceId();
        if (node instanceof OutputNode output) return output.deviceId();
        return "";
    }

    private static List<GraphConnection> outgoing(AudioGraph graph, String nodeId) {
        return graph.connections().stream().filter(GraphConnection::enabled)
                .filter(edge -> edge.fromNode().equals(nodeId)).toList();
    }

    record OutputState(Map<String, Object> instances, List<String> ports, List<String> activeLinks) {}
}

final class LegacyGraphDeviceBindings {
    private LegacyGraphDeviceBindings() {}

    static DeviceBinding find(Map<String, Object> config, String deviceRef) {
        String id = normalize(deviceRef);
        if (id.isBlank() || "system_default".equals(id)) return null;
        List<Map<String, Object>> matches = maps(config.get("devices")).stream()
                .filter(device -> id.equals(String.valueOf(device.getOrDefault("id", "")))).toList();
        if (matches.size() != 1) return null;
        Map<String, Object> device = matches.getFirst();
        DeviceIdentity identity = device.get("identity") instanceof Map<?, ?>
                ? DeviceIdentity.fromMap(object(device.get("identity"))) : null;
        return new DeviceBinding(identity, String.valueOf(device.getOrDefault("role", "")));
    }

    static void preserveSlotLevels(Map<String, Object> config, String deviceRef, GraphNode node) {
        if (!(node instanceof DeviceSlotNode slot)) return;
        String id = normalize(deviceRef);
        List<Map<String, Object>> devices = maps(config.get("devices"));
        for (int index = 0; index < devices.size(); index++) {
            Map<String, Object> current = devices.get(index);
            if (!id.equals(String.valueOf(current.getOrDefault("id", "")))) continue;
            if (current.get("volume") instanceof Number && current.get("mute") instanceof Boolean) return;
            List<Map<String, Object>> updatedDevices = new ArrayList<>(devices);
            Map<String, Object> updated = new LinkedHashMap<>(current);
            if (!(updated.get("volume") instanceof Number)) updated.put("volume", slot.volume());
            if (!(updated.get("mute") instanceof Boolean)) updated.put("mute", slot.muted());
            updatedDevices.set(index, updated);
            config.put("devices", updatedDevices);
            return;
        }
    }

    static String normalize(String ref) {
        if (ref == null) return "";
        return ref.startsWith("device:") ? ref.substring("device:".length()) : ref;
    }

    private static Map<String, Object> object(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map)
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object raw) {
        return raw instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    record DeviceBinding(DeviceIdentity identity, String role) {}
}

final class LegacyGraphFilterMigration {
    private LegacyGraphFilterMigration() {}

    static FilterNode limiter(AudioGraph... graphs) {
        FilterNode fallback = null;
        for (AudioGraph graph : graphs) if (graph != null) for (GraphNode node : graph.nodes()) {
            if (!(node instanceof FilterNode filter) || !"limiter".equals(filter.processor())) continue;
            if (UnifiedGraphConfig.LIMITER_ID.equals(filter.id())) return copy(filter);
            if (fallback == null) fallback = filter;
        }
        return fallback == null ? null : copy(fallback);
    }

    static List<FilterNode> preserve(AudioGraph graph, Set<String> excludedIds) {
        if (graph == null) return List.of();
        List<FilterNode> result = new ArrayList<>();
        Set<String> seen = new HashSet<>(excludedIds);
        for (GraphNode node : graph.nodes()) {
            if (!(node instanceof FilterNode filter) || !seen.add(filter.id()) || untouchedDefaultBass(filter)) continue;
            result.add(copy(filter));
        }
        return List.copyOf(result);
    }

    private static boolean untouchedDefaultBass(FilterNode filter) {
        if (!"octine-bass-low-pass".equals(filter.id()) || !"low_pass".equals(filter.processor())) return false;
        double frequency = number(filter.parameters().get("frequency_hz"), -1);
        double slope = number(filter.parameters().get("slope_db_oct"), -1);
        double q = number(filter.parameters().get("q"), -1);
        return frequency == 60.0 && slope == 48.0 && q < 0
                || frequency == 120.0 && q == 0.70710678 && slope < 0;
    }

    private static FilterNode copy(FilterNode filter) {
        return new FilterNode(filter.id(), filter.name(), GraphCommonVibro.CHANNELS,
                filter.processor(), filter.enabled(), new LinkedHashMap<>(filter.parameters()));
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
}
