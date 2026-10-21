package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.UnifiedGraphConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.STEREO;
import static org.junit.jupiter.api.Assertions.*;

class DynamicGeneratorOutputPartitionTest {
    @Test void outputGeneratorsRequireDisjointRolePartitionsAndMultiSwitchAnchors() {
        String hapticSelector = "device:role:haptic_output";
        DynamicGeneratorNode haptic = new DynamicGeneratorNode("haptic-output", "Haptic", hapticSelector, STEREO,
                Map.of("registry", "outputs", "connect_to", Map.of(
                        "node_id", UnifiedGraphConfig.LIMITER_ID, "port_id", "out")));
        SwitchNode multiSwitch = new SwitchNode("switch", "MultiSwitch", STEREO, List.of("monitor"), List.of());
        AudioGraph hapticPath = hapticGraph(haptic);
        DynamicGeneratorNode broad = new DynamicGeneratorNode("all-outputs", "All outputs", "device:*", STEREO,
                Map.of("registry", "outputs", "connect_to", Map.of("node_id", "switch", "port_id", "in")));
        List<GraphNode> overlappingNodes = new ArrayList<>(hapticPath.nodes());
        overlappingNodes.add(broad);
        overlappingNodes.add(multiSwitch);
        AudioGraph overlapping = new AudioGraph("output-overlap", "Outputs", overlappingNodes, hapticPath.connections());
        assertTrue(GraphValidator.validate(overlapping).stream().anyMatch(message -> message.contains("overlap")));

        DynamicGeneratorNode partitioned = new DynamicGeneratorNode("all-outputs", "All outputs", "device:*", STEREO,
                Map.of("registry", "outputs", "connect_to", Map.of("node_id", "switch", "port_id", "in"),
                        "excluded_selectors", List.of(hapticSelector)));
        List<GraphNode> disjointNodes = new ArrayList<>(hapticPath.nodes());
        disjointNodes.add(partitioned);
        disjointNodes.add(multiSwitch);
        AudioGraph disjoint = new AudioGraph("output-disjoint", "Outputs", disjointNodes, hapticPath.connections());
        assertEquals(List.of(), GraphValidator.validate(disjoint));

        DynamicGeneratorNode direct = new DynamicGeneratorNode("direct", "Direct", "device:*", STEREO,
                Map.of("registry", "outputs", "connect_to", Map.of(
                        "node_id", UnifiedGraphConfig.LIMITER_ID, "port_id", "out")));
        assertFalse(GraphValidator.validate(hapticGraph(direct)).isEmpty());

        DynamicGeneratorNode hapticDirect = new DynamicGeneratorNode("haptic-direct", "Haptic output",
                hapticSelector, STEREO,
                Map.of("registry", "outputs", "connect_to", Map.of(
                        "node_id", UnifiedGraphConfig.LIMITER_ID, "port_id", "out")));
        assertEquals(List.of(), GraphValidator.validate(hapticGraph(hapticDirect)));
    }

    private static AudioGraph hapticGraph(DynamicGeneratorNode generator) {
        SourceNode eventSource = new SourceNode(UnifiedGraphConfig.EVENT_SOURCE_ID, "Events",
                EventSoundStudioSource.SELECTOR, STEREO);
        GainNode eventGain = new GainNode(UnifiedGraphConfig.EVENT_GAIN_ID, "Events volume", STEREO, 1.0, false);
        MixerNode hapticMixer = new MixerNode(GraphCommonVibro.VIBRO_MIXER_ID, "Haptic merge", STEREO,
                Map.of("music", 1.0, "events", 1.0));
        GainNode hapticGain = new GainNode(GraphCommonVibro.VIBRO_GAIN_ID, "Haptic volume", STEREO, 1.0, false);
        FilterNode limiter = new FilterNode(UnifiedGraphConfig.LIMITER_ID, "Final haptic limiter",
                STEREO, "limiter", true, Map.of());
        return new AudioGraph("route-haptic-anchor", "Haptic anchor",
                List.of(generator, eventSource, eventGain, hapticMixer, hapticGain, limiter), List.of(
                new GraphConnection("event-source-gain", eventSource.id(), "out", eventGain.id(), "in", true, 1.0),
                new GraphConnection("event-merge", eventGain.id(), "out", hapticMixer.id(), "events", true, 1.0),
                new GraphConnection("haptic-volume", hapticMixer.id(), "out", hapticGain.id(), "in", true, 1.0),
                new GraphConnection("haptic-limiter", hapticGain.id(), "out", limiter.id(), "in", true, 1.0)));
    }
}
