package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import io.github.ilyaandreev05.audiomatrix.graph.SwitchNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the shipped single-profile routing graph from stable node identifiers. */
final class UnifiedGraphFactory {
    private static final List<String> CHANNELS = GraphCommonVibro.CHANNELS;
    private UnifiedGraphFactory() {}

    static AudioGraph defaults() {
        return create(0.6, false, 0.65, false, 0.6, false, 1.0, false, 1.0, false,
                PreservedState.defaults());
    }

    static AudioGraph create(double musicVolume, boolean musicMute,
                             double eventVolume, boolean eventMute,
                             double totalVolume, boolean totalMute,
                             double mainApps, boolean mainAppsMute,
                             double mainAdditional, boolean mainAdditionalMute,
                             PreservedState state) {
        String mainMixerId = GraphCommonVibro.MAIN_MIXER_ID;
        MixerNode mainMerge = new MixerNode(mainMixerId, "Основной Merge", CHANNELS,
                Map.of("applications", clamp(mainApps), "additional", clamp(mainAdditional)),
                Map.of("applications", mainAppsMute, "additional", mainAdditionalMute));
        MixerNode hapticMerge = new MixerNode(GraphCommonVibro.VIBRO_MIXER_ID, "Сумматор вибродинамика",
                CHANNELS, Map.of("music", 1.0, "events", 1.0));
        List<String> switchOutputs = new ArrayList<>();
        switchOutputs.add(UnifiedGraphConfig.HAPTIC_MUSIC_PORT_ID);
        switchOutputs.addAll(state.outputPorts());
        List<String> activeLinks = state.selectedOutputPorts().stream()
                .map(port -> "in->" + port).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (state.hapticMusicSelected()) activeLinks.add("in->" + UnifiedGraphConfig.HAPTIC_MUSIC_PORT_ID);
        SwitchNode multiSwitch = new SwitchNode(UnifiedGraphConfig.SWITCH_ID, "Маршрутизация выходов",
                CHANNELS, switchOutputs, activeLinks);
        FilterNode limiter = state.limiter();

        List<GraphNode> nodes = new ArrayList<>();
        nodes.add(applicationGenerator(mainMixerId));
        nodes.add(mainMerge);
        nodes.add(multiSwitch);
        nodes.add(outputGenerator(UnifiedGraphConfig.OUTPUTS_GENERATOR_ID, "Выходы",
                "device:*", UnifiedGraphConfig.SWITCH_ID, "in", state.outputInstances(),
                state.excludedOutputSelectors(), state.autoSelectTrustedHdmiOnFirstDiscovery()));
        nodes.add(outputGenerator(UnifiedGraphConfig.HAPTIC_OUTPUTS_GENERATOR_ID, "Выход вибродинамика",
                state.hapticOutputSelector(), limiter.id(), "out",
                state.hapticOutputInstances(), List.of(), false));

        for (FilterNode filter : state.routeFilters()) nodes.add(filter);
        nodes.add(new GainNode(UnifiedGraphConfig.MUSIC_GAIN_ID, "Громкость музыки на вибродинамик",
                CHANNELS, clamp(musicVolume), musicMute));
        nodes.add(new SourceNode(UnifiedGraphConfig.EVENT_SOURCE_ID, "Звуки событий",
                state.eventSelector(), CHANNELS));
        nodes.add(new GainNode(UnifiedGraphConfig.EVENT_GAIN_ID, "Громкость событий", CHANNELS,
                clamp(eventVolume), eventMute));
        nodes.add(hapticMerge);
        nodes.add(new GainNode(GraphCommonVibro.VIBRO_GAIN_ID, "Общая сила вибродинамика", CHANNELS,
                clamp(totalVolume), totalMute));
        for (FilterNode filter : state.hapticFilters()) nodes.add(filter);
        nodes.add(limiter);

        List<GraphConnection> edges = new ArrayList<>();
        String firstRouteNode = state.routeFilters().isEmpty()
                ? UnifiedGraphConfig.SWITCH_ID : state.routeFilters().getFirst().id();
        edges.add(edge("main-switch", mainMixerId, "out", firstRouteNode, "in"));
        for (int index = 0; index < state.routeFilters().size(); index++) {
            FilterNode filter = state.routeFilters().get(index);
            String nextNode = index + 1 < state.routeFilters().size()
                    ? state.routeFilters().get(index + 1).id() : UnifiedGraphConfig.SWITCH_ID;
            edges.add(edge("main-filter-" + index, filter.id(), "out", nextNode, "in"));
        }

        String firstHapticMusicNode = state.hapticFilters().isEmpty()
                ? UnifiedGraphConfig.MUSIC_GAIN_ID : state.hapticFilters().getFirst().id();
        edges.add(edge("haptic-music", UnifiedGraphConfig.SWITCH_ID,
                UnifiedGraphConfig.HAPTIC_MUSIC_PORT_ID, firstHapticMusicNode, "in"));
        for (int index = 0; index < state.hapticFilters().size(); index++) {
            FilterNode filter = state.hapticFilters().get(index);
            String nextNode = index + 1 < state.hapticFilters().size()
                    ? state.hapticFilters().get(index + 1).id() : UnifiedGraphConfig.MUSIC_GAIN_ID;
            edges.add(edge("haptic-filter-" + index, filter.id(), "out", nextNode, "in"));
        }
        edges.add(edge("music-merge", UnifiedGraphConfig.MUSIC_GAIN_ID, "out",
                GraphCommonVibro.VIBRO_MIXER_ID, "music"));
        edges.add(edge("event-volume", UnifiedGraphConfig.EVENT_SOURCE_ID, "out",
                UnifiedGraphConfig.EVENT_GAIN_ID, "in"));
        edges.add(edge("event-merge", UnifiedGraphConfig.EVENT_GAIN_ID, "out",
                GraphCommonVibro.VIBRO_MIXER_ID, "events"));
        edges.add(edge("vibro-total", GraphCommonVibro.VIBRO_MIXER_ID, "out",
                GraphCommonVibro.VIBRO_GAIN_ID, "in"));
        edges.add(edge("vibro-limiter", GraphCommonVibro.VIBRO_GAIN_ID, "out", limiter.id(), "in"));
        return new AudioGraph(UnifiedGraphConfig.ROUTE_ID, "Основной", nodes, edges);
    }

    private static DynamicGeneratorNode applicationGenerator(String mainMixerId) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("registry", "applications");
        parameters.put("template", "default-application");
        parameters.put("excluded_selectors", List.of("application:"
                + io.github.ilyaandreev05.audiomatrix.graph.EventSoundStudioSource.APPLICATION_ID));
        parameters.put("connect_to", Map.of("node_id", mainMixerId, "port_id", "applications"));
        parameters.put("initial_gain", 1.0);
        return new DynamicGeneratorNode(GraphCommonVibro.MAIN_SOURCE_ID, "Все приложения, кроме событий",
                "application:unassigned", CHANNELS, parameters);
    }

    private static DynamicGeneratorNode outputGenerator(String id, String name, String selector,
                                                        String upstreamNode, String upstreamPort,
                                                        Map<String, Object> instances,
                                                        List<String> excludedSelectors,
                                                        boolean autoSelectTrustedHdmiOnFirstDiscovery) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("registry", "outputs");
        parameters.put("template", "default-output");
        parameters.put("connect_to", Map.of("node_id", upstreamNode, "port_id", upstreamPort));
        parameters.put("initial_gain", 1.0);
        parameters.put("auto_select_trusted_hdmi_on_first_discovery", autoSelectTrustedHdmiOnFirstDiscovery);
        if (!instances.isEmpty()) parameters.put("instances", instances);
        if (!excludedSelectors.isEmpty()) parameters.put("excluded_selectors", excludedSelectors);
        return new DynamicGeneratorNode(id, name, selector, CHANNELS, parameters);
    }

    private static GraphConnection edge(String id, String from, String fromPort, String to, String toPort) {
        return GraphCommonVibro.edge(id, from, fromPort, to, toPort);
    }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    static FilterNode defaultLimiter() {
        return new FilterNode(UnifiedGraphConfig.LIMITER_ID, "Ограничитель", CHANNELS,
                "limiter", true, Map.of("ceiling_db", -1.0, "lookahead_ms", 5.0, "release_ms", 80.0));
    }

    record PreservedState(Map<String, Object> outputInstances,
                          Map<String, Object> hapticOutputInstances,
                          List<String> outputPorts,
                          List<String> selectedOutputPorts,
                          List<String> excludedOutputSelectors,
                          boolean hapticMusicSelected,
                          boolean autoSelectTrustedHdmiOnFirstDiscovery,
                          String hapticOutputSelector,
                          String eventSelector,
                          List<FilterNode> routeFilters,
                          List<FilterNode> hapticFilters,
                          FilterNode limiter) {
        PreservedState {
            outputInstances = Collections.unmodifiableMap(new LinkedHashMap<>(outputInstances));
            hapticOutputInstances = Collections.unmodifiableMap(new LinkedHashMap<>(hapticOutputInstances));
            outputPorts = List.copyOf(outputPorts);
            selectedOutputPorts = List.copyOf(selectedOutputPorts);
            excludedOutputSelectors = List.copyOf(excludedOutputSelectors);
            routeFilters = List.copyOf(routeFilters);
            hapticFilters = List.copyOf(hapticFilters);
        }

        static PreservedState defaults() {
            return new PreservedState(Map.of(), Map.of(), List.of(), List.of(),
                    List.of("device:haptic-assigned"), false, true, "device:haptic-assigned",
                    "application:" + io.github.ilyaandreev05.audiomatrix.graph.EventSoundStudioSource.APPLICATION_ID,
                    List.of(), List.of(),
                    defaultLimiter());
        }
    }
}
