package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.EventSoundStudioSource;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import io.github.ilyaandreev05.audiomatrix.graph.SwitchNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the single-profile graph from stable node identifiers. */
final class UnifiedGraphFactory {
    static final String HAPTIC_MUSIC_PORT_ID = "haptic";
    private static final List<String> CHANNELS = GraphCommonVibro.CHANNELS;

    private UnifiedGraphFactory() {}

    static AudioGraph defaults() {
        return create(Settings.defaults());
    }

    static AudioGraph create(Settings settings) {
        MixerNode mainMixer = new MixerNode(GraphCommonVibro.MAIN_MIXER_ID, "Основной микшер", CHANNELS,
                Map.of("applications", clamp(settings.mainApps()), "additional", clamp(settings.mainAdditional())),
                Map.of("applications", settings.mainAppsMute(), "additional", settings.mainAdditionalMute()));
        MixerNode hapticMerge = new MixerNode(GraphCommonVibro.VIBRO_MIXER_ID, "Сумматор вибродинамика", CHANNELS,
                Map.of("music", 1.0, "events", 1.0));
        List<String> switchOutputs = switchOutputs(settings.normalOutputPorts());
        SwitchNode multiSwitch = new SwitchNode(UnifiedGraphConfig.SWITCH_ID, "MultiSwitch", CHANNELS,
                switchOutputs, settings.activeLinks());

        DynamicGeneratorNode applications = new DynamicGeneratorNode(GraphCommonVibro.MAIN_SOURCE_ID,
                "Все приложения, кроме событий", "application:unassigned", CHANNELS,
                Map.of("registry", "applications",
                        "connect_to", Map.of("node_id", GraphCommonVibro.MAIN_MIXER_ID, "port_id", "applications")));
        DynamicGeneratorNode normalOutputs = new DynamicGeneratorNode(UnifiedGraphConfig.MONITOR_OUTPUT_ID,
                "Обычные выходы", "device:*", CHANNELS,
                Map.of("registry", "outputs",
                        "connect_to", Map.of("node_id", UnifiedGraphConfig.SWITCH_ID, "port_id", "in"),
                        "excluded_selectors", List.of("device:role:haptic_output"),
                        "instances", settings.normalOutputInstances(),
                        "auto_select_trusted_hdmi_on_first_discovery",
                        settings.autoSelectTrustedHdmiOnFirstDiscovery()));
        DynamicGeneratorNode hapticOutput = new DynamicGeneratorNode(GraphCommonVibro.VIBRO_OUTPUT_ID,
                "Выход вибродинамика", "device:role:haptic_output", CHANNELS,
                Map.of("registry", "outputs",
                        "connect_to", Map.of("node_id", UnifiedGraphConfig.LIMITER_ID, "port_id", "out"),
                        "auto_select_trusted_hdmi_on_first_discovery", false));

        List<GraphNode> nodes = new ArrayList<>(List.of(
                applications,
                mainMixer,
                multiSwitch,
                normalOutputs,
                hapticOutput,
                new GainNode(UnifiedGraphConfig.MUSIC_GAIN_ID, "Музыка на вибродинамик", CHANNELS,
                        clamp(settings.musicVolume()), settings.musicMute()),
                new SourceNode(UnifiedGraphConfig.EVENT_SOURCE_ID, "Звуки событий",
                        EventSoundStudioSource.SELECTOR, CHANNELS),
                new GainNode(UnifiedGraphConfig.EVENT_GAIN_ID, "Громкость событий", CHANNELS,
                        clamp(settings.eventVolume()), settings.eventMute()),
                hapticMerge,
                new GainNode(GraphCommonVibro.VIBRO_GAIN_ID, "Общая сила вибродинамика", CHANNELS,
                        clamp(settings.totalVolume()), settings.totalMute()),
                copyFilter(settings.limiter(), UnifiedGraphConfig.LIMITER_ID, settings.limiter().name())));

        List<GraphConnection> edges = new ArrayList<>();
        edges.add(GraphCommonVibro.edge("haptic-music", UnifiedGraphConfig.SWITCH_ID,
                HAPTIC_MUSIC_PORT_ID, UnifiedGraphConfig.MUSIC_GAIN_ID, "in"));
        edges.add(GraphCommonVibro.edge("music-merge", UnifiedGraphConfig.MUSIC_GAIN_ID, "out",
                GraphCommonVibro.VIBRO_MIXER_ID, "music"));
        edges.add(GraphCommonVibro.edge("event-volume", UnifiedGraphConfig.EVENT_SOURCE_ID, "out",
                UnifiedGraphConfig.EVENT_GAIN_ID, "in"));
        edges.add(GraphCommonVibro.edge("event-merge", UnifiedGraphConfig.EVENT_GAIN_ID, "out",
                GraphCommonVibro.VIBRO_MIXER_ID, "events"));
        appendFilters(nodes, edges, GraphCommonVibro.MAIN_MIXER_ID, "out",
                UnifiedGraphConfig.SWITCH_ID, "in", settings.mainFilters(), "main-filter", "main-switch");
        appendFilters(nodes, edges, GraphCommonVibro.VIBRO_MIXER_ID, "out",
                GraphCommonVibro.VIBRO_GAIN_ID, "in", settings.hapticFilters(), "haptic-filter", "vibro-total");
        edges.add(GraphCommonVibro.edge("vibro-limiter", GraphCommonVibro.VIBRO_GAIN_ID, "out",
                UnifiedGraphConfig.LIMITER_ID, "in"));

        return new AudioGraph(UnifiedGraphConfig.ROUTE_ID, "Основной", nodes, edges);
    }

    static String outputPortId(String fingerprint) {
        return io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry
                .stableOutputPortId(UnifiedGraphConfig.MONITOR_OUTPUT_ID, fingerprint);
    }

    private static List<String> switchOutputs(List<String> normalOutputPorts) {
        List<String> result = new ArrayList<>();
        result.add(HAPTIC_MUSIC_PORT_ID);
        for (String port : normalOutputPorts) {
            if (port != null && !port.isBlank() && !result.contains(port)) result.add(port);
        }
        return List.copyOf(result);
    }

    private static void appendFilters(List<GraphNode> nodes, List<GraphConnection> edges,
                                      String fromNode, String fromPort, String toNode, String toPort,
                                      List<FilterNode> filters, String edgePrefix, String terminalEdgeId) {
        List<FilterNode> additions = filters.stream()
                .filter(filter -> nodes.stream().noneMatch(node -> node.id().equals(filter.id())))
                .toList();
        if (additions.isEmpty()) {
            edges.add(GraphCommonVibro.edge(terminalEdgeId, fromNode, fromPort, toNode, toPort));
            return;
        }
        String previous = fromNode;
        String previousPort = fromPort;
        int index = 0;
        for (FilterNode filter : additions) {
            FilterNode copy = copyFilter(filter, filter.id(), filter.name());
            nodes.add(copy);
            edges.add(GraphCommonVibro.edge(edgePrefix + "-" + index++, previous, previousPort,
                    copy.id(), "in"));
            previous = copy.id();
            previousPort = "out";
        }
        edges.add(GraphCommonVibro.edge(terminalEdgeId, previous, previousPort, toNode, toPort));
    }

    private static FilterNode copyFilter(FilterNode filter, String id, String name) {
        return new FilterNode(id, name, CHANNELS, filter.processor(), filter.enabled(),
                new LinkedHashMap<>(filter.parameters()));
    }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }

    record Settings(double musicVolume, boolean musicMute, double eventVolume, boolean eventMute,
                    double totalVolume, boolean totalMute, double mainApps, boolean mainAppsMute,
                    double mainAdditional, boolean mainAdditionalMute, FilterNode limiter,
                    Map<String, Object> normalOutputInstances, List<String> normalOutputPorts,
                    List<String> activeLinks, List<FilterNode> mainFilters, List<FilterNode> hapticFilters,
                    boolean autoSelectTrustedHdmiOnFirstDiscovery) {
        Settings {
            if (limiter == null) limiter = new FilterNode(UnifiedGraphConfig.LIMITER_ID, "Ограничитель",
                    CHANNELS, "limiter", true,
                    Map.of("ceiling_db", -1.0, "lookahead_ms", 5.0, "release_ms", 80.0));
            normalOutputInstances = Map.copyOf(normalOutputInstances);
            normalOutputPorts = List.copyOf(normalOutputPorts);
            activeLinks = List.copyOf(activeLinks);
            mainFilters = List.copyOf(mainFilters);
            hapticFilters = List.copyOf(hapticFilters);
        }

        static Settings defaults() {
            return new Settings(0.6, false, 0.65, false, 0.6, false,
                    1.0, false, 1.0, false, null, Map.of(),
                    List.of(), List.of(), List.of(), List.of(), false);
        }
    }
}
