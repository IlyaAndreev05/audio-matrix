package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import io.github.ilyaandreev05.audiomatrix.graph.SwitchNode;
import java.util.List;
import java.util.Map;

/** Builds the shipped single-profile routing graph from stable node identifiers. */
final class UnifiedGraphFactory {
    private static final List<String> CHANNELS = GraphCommonVibro.CHANNELS;
    private UnifiedGraphFactory() {}

    static AudioGraph defaults() {
        return create("system_default", "", 0.6, false, 0.65, false, 0.6, false, true);
    }

    static AudioGraph create(String monitorDevice, String vibroDevice,
                             double musicVolume, boolean musicMute,
                             double eventVolume, boolean eventMute,
                             double totalVolume, boolean totalMute,
                             boolean limiterEnabled) {
        return create(monitorDevice, vibroDevice, musicVolume, musicMute, eventVolume, eventMute,
                totalVolume, totalMute, 1.0, false, 1.0, false, limiterEnabled);
    }

    static AudioGraph create(String monitorDevice, String vibroDevice,
                             double musicVolume, boolean musicMute,
                             double eventVolume, boolean eventMute,
                             double totalVolume, boolean totalMute,
                             double mainApps, boolean mainAppsMute,
                             double mainAdditional, boolean mainAdditionalMute,
                             boolean limiterEnabled) {
        MixerNode mainMixer = new MixerNode(io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro.MAIN_MIXER_ID,
                "Основной микшер", CHANNELS,
                Map.of("applications", clamp(mainApps), "additional", clamp(mainAdditional)),
                Map.of("applications", mainAppsMute, "additional", mainAdditionalMute));
        MixerNode vibroMerge = new MixerNode(GraphCommonVibro.VIBRO_MIXER_ID, "Сумматор вибродинамика", CHANNELS,
                Map.of("music", 1.0, "events", 1.0));
        FilterNode limiter = new FilterNode(UnifiedGraphConfig.LIMITER_ID, "Ограничитель", CHANNELS,
                "limiter", limiterEnabled, Map.of("ceiling_db", -1.0, "lookahead_ms", 5.0, "release_ms", 80.0));
        List<GraphNode> nodes = List.of(
                new DynamicGeneratorNode(GraphCommonVibro.MAIN_SOURCE_ID, "Все приложения, кроме событий",
                        "application:unassigned", CHANNELS),
                mainMixer,
                new SwitchNode(UnifiedGraphConfig.SWITCH_ID, "Маршрутизация музыки", CHANNELS,
                        List.of("monitor", "vibro"), List.of("in->monitor")),
                new DeviceSlotNode(UnifiedGraphConfig.MONITOR_OUTPUT_ID, "Монитор", monitorDevice, CHANNELS),
                new GainNode(UnifiedGraphConfig.MUSIC_GAIN_ID, "Музыка на вибродинамик", CHANNELS,
                        clamp(musicVolume), musicMute),
                new SourceNode(UnifiedGraphConfig.EVENT_SOURCE_ID, "Звуки событий", "application:" +
                        io.github.ilyaandreev05.audiomatrix.graph.EventSoundStudioSource.APPLICATION_ID, CHANNELS),
                new GainNode(UnifiedGraphConfig.EVENT_GAIN_ID, "Громкость событий", CHANNELS,
                        clamp(eventVolume), eventMute),
                vibroMerge,
                new GainNode(GraphCommonVibro.VIBRO_GAIN_ID, "Общая сила вибродинамика", CHANNELS,
                        clamp(totalVolume), totalMute),
                limiter,
                new DeviceSlotNode(GraphCommonVibro.VIBRO_OUTPUT_ID, "Устройство вибродинамика", vibroDevice, CHANNELS));
        List<GraphConnection> edges = List.of(
                GraphCommonVibro.edge("main-input", GraphCommonVibro.MAIN_SOURCE_ID, "out", GraphCommonVibro.MAIN_MIXER_ID, "applications"),
                GraphCommonVibro.edge("main-switch", GraphCommonVibro.MAIN_MIXER_ID, "out", UnifiedGraphConfig.SWITCH_ID, "in"),
                GraphCommonVibro.edge("monitor-output", UnifiedGraphConfig.SWITCH_ID, "monitor", UnifiedGraphConfig.MONITOR_OUTPUT_ID, "in"),
                GraphCommonVibro.edge("music-volume", UnifiedGraphConfig.SWITCH_ID, "vibro", UnifiedGraphConfig.MUSIC_GAIN_ID, "in"),
                GraphCommonVibro.edge("music-merge", UnifiedGraphConfig.MUSIC_GAIN_ID, "out", GraphCommonVibro.VIBRO_MIXER_ID, "music"),
                GraphCommonVibro.edge("event-volume", UnifiedGraphConfig.EVENT_SOURCE_ID, "out", UnifiedGraphConfig.EVENT_GAIN_ID, "in"),
                GraphCommonVibro.edge("event-merge", UnifiedGraphConfig.EVENT_GAIN_ID, "out", GraphCommonVibro.VIBRO_MIXER_ID, "events"),
                GraphCommonVibro.edge("vibro-total", GraphCommonVibro.VIBRO_MIXER_ID, "out", GraphCommonVibro.VIBRO_GAIN_ID, "in"),
                GraphCommonVibro.edge("vibro-limiter", GraphCommonVibro.VIBRO_GAIN_ID, "out", UnifiedGraphConfig.LIMITER_ID, "in"),
                GraphCommonVibro.edge("vibro-output", UnifiedGraphConfig.LIMITER_ID, "out", GraphCommonVibro.VIBRO_OUTPUT_ID, "in"));
        return new AudioGraph(UnifiedGraphConfig.ROUTE_ID, "Основной", nodes, edges);
    }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
}
