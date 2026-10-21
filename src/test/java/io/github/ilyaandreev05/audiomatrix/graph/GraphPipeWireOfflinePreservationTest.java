package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GraphPipeWireOfflinePreservationTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test void offlineDynamicApplicationsPreserveCanonicalRulesAndAddEssWithoutFakingActiveSources() {
        DynamicGeneratorNode applications = new DynamicGeneratorNode("apps", "Applications",
                "application:unassigned", STEREO, Map.of("connect_to", Map.of(
                "node_id", "merge", "port_id", "applications")));
        MixerNode merge = new MixerNode("merge", "Application Merge", STEREO,
                Map.of("applications", 1.0, "additional", 1.0));
        SourceNode events = new SourceNode("ess-source", "Events", EventSoundStudioSource.SELECTOR, STEREO);
        GainNode eventGain = new GainNode("ess-gain", "Events volume", STEREO, 1.0, false);
        OutputNode output = new OutputNode("ess-output", "Monitor", "device:monitor", STEREO);
        AudioGraph graph = new AudioGraph("route-offline-apps", "Offline applications",
                List.of(applications, merge, events, eventGain, output), List.of(
                edge("events-gain", "ess-source", "out", "ess-gain", "in"),
                edge("events-output", "ess-gain", "out", "ess-output", "in")));
        Map<String, Object> savedChromeRule = Map.of("id", "saved-chrome-rule", "match",
                Map.of("app_id", "com.google.Chrome"), "bus", "main", "volume", 0.27,
                "mute", true, "filters", List.of(Map.of("processor", "high_pass", "frequency_hz", 90.0)));
        Map<String, Object> baseConfig = DefaultConfig.create();
        baseConfig.put("applications", List.of(savedChromeRule));

        DynamicInstanceRegistry.ReconcileResult offline = DynamicInstanceRegistry.reconcile(graph, Map.of());
        Map<String, Object> compiled = GraphPipeWireCompiler.compile(offline, baseConfig);
        List<Map<String, Object>> rules = maps(compiled.get("applications"));
        Map<String, Object> preserved = rules.stream().filter(rule -> "saved-chrome-rule".equals(rule.get("id")))
                .findFirst().orElseThrow();
        Map<String, Object> ess = rules.stream().filter(rule -> EventSoundStudioSource.APPLICATION_ID.equals(
                object(rule.get("match")).get("app_id"))).findFirst().orElseThrow();
        List<Map<String, Object>> routes = maps(compiled.get("routes"));
        Map<String, Object> profile = maps(compiled.get("profiles")).stream()
                .filter(value -> "main".equals(value.get("id"))).findFirst().orElseThrow();

        assertEquals(0.27, preserved.get("volume"));
        assertEquals(true, preserved.get("mute"));
        assertEquals(savedChromeRule.get("filters"), preserved.get("filters"));
        assertEquals("main", preserved.get("bus"));
        assertEquals("ess-source", ess.get("bus"));
        assertEquals(List.of("ess-source"), profile.get("active_buses"));
        assertEquals(1, routes.size());
        assertEquals(1.0, routes.getFirst().get("volume"));
        assertEquals(Set.of("main", "ess-source"), maps(compiled.get("buses")).stream()
                .map(bus -> String.valueOf(bus.get("id"))).collect(java.util.stream.Collectors.toSet()));
        assertFalse(compiled.containsKey("graph_capture_sources"));
        assertEquals(List.of(savedChromeRule), baseConfig.get("applications"));
    }

    private static GraphConnection edge(String id, String from, String fromPort, String to, String toPort) {
        return new GraphConnection(id, from, fromPort, to, toPort, true, 1.0);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
