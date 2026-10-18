package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A single persisted tactile graph, composed with the selected ordinary-audio workspace. */
public final class GraphCommonVibro {
    public static final String COMMON_ROUTE_ID = "octine-common-vibro";
    public static final String MAIN_SOURCE_ID = "octine-main-source";
    public static final String MAIN_MIXER_ID = "octine-main-mixer";
    public static final String VIBRO_GAIN_ID = "octine-vibro-gain";
    public static final String VIBRO_MIXER_ID = "octine-vibro-mixer";
    public static final String VIBRO_OUTPUT_ID = "octine-vibro-output";
    public static final List<String> CHANNELS = List.of("FL", "FR");
    private GraphCommonVibro() {}

    public static boolean isBuiltinRoute(String id) {
        return List.of("octine-monitor", "octine-headphones", "octine-free").contains(id);
    }

    public static SourceNode mainSource() {
        return new SourceNode(MAIN_SOURCE_ID, "Все приложения (без EVENT)", "application:unassigned", CHANNELS);
    }

    public static MixerNode mainMixer() {
        return new MixerNode(MAIN_MIXER_ID, "Main Mixer", CHANNELS, Map.of("applications", 1.0, "additional", 1.0));
    }

    public static AudioGraph defaults() {
        List<GraphNode> nodes = List.of(mainSource(), mainMixer(),
                new FilterNode("octine-bass-low-pass", "Басы · 120 Hz", CHANNELS, "low_pass", true,
                        Map.of("frequency_hz", 120.0, "q", 0.70710678)),
                new SourceNode("octine-event-source", "Event Sound Studio", EventSoundStudioSource.SELECTOR, CHANNELS),
                new MixerNode(VIBRO_MIXER_ID, "Vibro Mixer", CHANNELS, Map.of("bass", 0.6, "events", 0.65)),
                new GainNode(VIBRO_GAIN_ID, "Vibro Gain / Mute", CHANNELS, 0.6, false),
                new FilterNode("octine-vibro-limiter", "Vibro Limiter", CHANNELS, "limiter", true,
                        Map.of("ceiling_db", -1.0, "lookahead_ms", 5.0, "release_ms", 80.0)),
                new DeviceSlotNode(VIBRO_OUTPUT_ID, "Vibro Output · назначьте устройство", "", CHANNELS));
        return new AudioGraph(COMMON_ROUTE_ID, "Общий закреплённый Vibro", nodes, List.of(
                edge("main-input", MAIN_SOURCE_ID, "out", MAIN_MIXER_ID, "applications"),
                edge("bass-copy", MAIN_MIXER_ID, "out", "octine-bass-low-pass", "in"),
                edge("bass-input", "octine-bass-low-pass", "out", VIBRO_MIXER_ID, "bass"),
                edge("event-input", "octine-event-source", "out", VIBRO_MIXER_ID, "events"),
                edge("vibro-level", VIBRO_MIXER_ID, "out", VIBRO_GAIN_ID, "in"),
                edge("vibro-protection", VIBRO_GAIN_ID, "out", "octine-vibro-limiter", "in"),
                edge("vibro-output", "octine-vibro-limiter", "out", VIBRO_OUTPUT_ID, "in")));
    }

    public static AudioGraph graph(Map<String, Object> config) {
        if (!(config.get("common_vibro_graph") instanceof Map<?, ?> raw)) return null;
        Map<String, Object> encoded = new LinkedHashMap<>();
        raw.forEach((key, value) -> encoded.put(String.valueOf(key), value));
        return GraphCodec.fromMap(encoded);
    }

    public static void save(Map<String, Object> config, AudioGraph graph) {
        if (!COMMON_ROUTE_ID.equals(graph.id())) throw new IllegalArgumentException("Not the common Vibro graph");
        List<String> errors = GraphValidator.validate(graph);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        GraphVibroInvariant.require(graph);
        config.put("common_vibro_graph", new LinkedHashMap<>(GraphCodec.toMap(graph)));
    }

    /** Configuration validation entry point for the fixed shared topology. */
    public static void requireValid(AudioGraph graph) {
        if (!COMMON_ROUTE_ID.equals(graph.id())) throw new IllegalArgumentException("Not the common Vibro graph");
        GraphVibroInvariant.require(graph);
    }

    /** Legacy/custom routes are never rewritten or implicitly attached to the tactile graph. */
    public static AudioGraph effectiveGraph(AudioGraph selected, Map<String, Object> config) {
        if (!isBuiltinRoute(selected.id())) return selected;
        AudioGraph common = graph(config);
        if (common == null) throw new IllegalArgumentException("The common Vibro graph is missing");
        Map<String, GraphNode> templateNodes = new LinkedHashMap<>();
        defaults().nodes().forEach(node -> templateNodes.put(node.id(), node));
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        // Unmodified profile copies follow the shared Main source/mixer; explicit profile edits win.
        selected.nodes().forEach(node -> nodes.put(node.id(), node));
        common.nodes().forEach(node -> {
            GraphNode current = nodes.get(node.id());
            if (node instanceof DeviceSlotNode slot && current instanceof DeviceSlotNode existing
                    && !existing.deviceId().isBlank()) return;
            boolean main = MAIN_SOURCE_ID.equals(node.id()) || MAIN_MIXER_ID.equals(node.id());
            if (!main) { nodes.put(node.id(), node); return; }
            if (current == null || sameMainParameters(current, templateNodes.get(node.id())))
                nodes.put(node.id(), node);
        });
        Map<String, GraphConnection> edges = new LinkedHashMap<>();
        selected.connections().forEach(edge -> edges.put(edge.id(), edge));
        GraphVibroInvariant.require(common);
        boolean selectedMain = selected.nodes().stream().anyMatch(node -> MAIN_SOURCE_ID.equals(node.id()))
                && selected.nodes().stream().anyMatch(node -> MAIN_MIXER_ID.equals(node.id()));
        common.connections().forEach(edge -> {
            // A profile that owns the Main source/mixer keeps its own wiring, including an explicit disconnect.
            if (selectedMain && MAIN_SOURCE_ID.equals(edge.fromNode()) && MAIN_MIXER_ID.equals(edge.toNode())) return;
            edges.put(edge.id(), edge);
        });
        return new AudioGraph(selected.id(), selected.name(), new ArrayList<>(nodes.values()), new ArrayList<>(edges.values()));
    }

    private static boolean sameMainParameters(GraphNode node, GraphNode template) {
        if (template == null || !node.getClass().equals(template.getClass())) return false;
        if (node instanceof SourceNode source)
            return source.selector().equals(((SourceNode) template).selector());
        if (node instanceof MixerNode mixer) {
            MixerNode base = (MixerNode) template;
            return mixer.inputGains().equals(base.inputGains()) && mixer.inputMutes().equals(base.inputMutes());
        }
        return false;
    }

    public static GraphConnection edge(String id, String from, String fromPort, String to, String toPort) {
        return new GraphConnection("octine-link-" + id, from, fromPort, to, toPort, true, 1.0);
    }
}
