package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.STEREO;
import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.maps;
import static org.junit.jupiter.api.Assertions.*;

class DynamicGeneratorCompilerTest {
    @Test void compilerDoesNotTreatGeneratorControlNodeAsPcmSource() {
        DynamicGeneratorNode generator = new DynamicGeneratorNode("apps", "Apps", "application:unassigned", STEREO);
        GainNode gain = new GainNode("gain", "Gain", STEREO, 1.0, false);
        OutputNode output = new OutputNode("output", "Output", "monitor", STEREO);
        AudioGraph graph = new AudioGraph("control-only", "Control only", List.of(generator, gain, output), List.of(
                new GraphConnection("generator-anchor", "apps", "out", "gain", "in", true, 1.0),
                new GraphConnection("gain-output", "gain", "out", "output", "in", true, 1.0)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GraphPipeWireCompiler.compile(graph, DefaultConfig.create()));
        assertTrue(error.getMessage().contains("at least one source"));
    }

    @Test void capturedTabCompilationCarriesExactRuntimeBindingWithoutRouteGain() {
        String instanceKey = "tab:chrome:instance:profile:session";
        String captureKey = "capture-42";
        String sourceId = DynamicInstanceRegistry.stableNodeId("tabs", instanceKey, "source");
        String gainId = DynamicInstanceRegistry.stableNodeId("tabs", instanceKey, "gain");
        String compositeId = DynamicInstanceRegistry.stableCompositeId("tabs", instanceKey);
        Map<String, Object> instanceRecord = Map.of(
                "node_ids", Map.of("source", sourceId, "gain", gainId),
                "composite_id", compositeId,
                "overrides", Map.of("gain", 0.4, "muted", true));
        DynamicGeneratorNode tabs = new DynamicGeneratorNode("tabs", "Browser tabs", "browser:*", STEREO,
                Map.of("registry", "browser_tabs", "connect_to", Map.of("node_id", "mixer", "port_id", "tab"),
                        "instances", Map.of(instanceKey, instanceRecord)));
        MixerNode mixer = new MixerNode("mixer", "Tabs input", STEREO, Map.of("tab", 1.0));
        SourceNode source = new SourceNode(sourceId, "Tab", "capture:" + captureKey, STEREO);
        GainNode gain = new GainNode(gainId, "Tab volume", STEREO, 0.4, true);
        OutputNode output = new OutputNode("output", "Monitor", "monitor", STEREO);
        AudioGraph configuredGraph = new AudioGraph("captured-route", "Captured tab", List.of(tabs, mixer), List.of());
        AudioGraph graph = new AudioGraph("captured-route", "Captured tab",
                List.of(tabs, mixer, source, gain, output), List.of(
                new GraphConnection("source-gain", sourceId, "out", gainId, "in", true, 1.0),
                new GraphConnection("gain-output", gainId, "out", output.id(), "in", true, 1.0)));
        Map<String, Object> runtimeBinding = Map.of(
                "captureStreamKey", captureKey,
                "nodeName", "audio-matrix-chrome-" + captureKey,
                "applicationRuleId", "chrome-tab-rule",
                "parentApplicationId", "com.google.Chrome",
                "parentProcessName", "chrome");
        DynamicInstanceRegistry.RuntimeInstance runtime = new DynamicInstanceRegistry.RuntimeInstance(
                "tabs", instanceKey, Map.of("source", sourceId, "gain", gainId), compositeId,
                true, true, true, DynamicInstanceRegistry.Confidence.HIGH, runtimeBinding, Map.of("gain", 0.4, "muted", true));
        DynamicInstanceRegistry.ReconcileResult reconciled = new DynamicInstanceRegistry.ReconcileResult(
                configuredGraph, graph, Map.of(new DynamicInstanceRegistry.InstanceAddress("tabs", instanceKey), runtime),
                List.of(), false);

        Map<String, Object> baseConfig = DefaultConfig.create();
        Map<String, Object> compiled = GraphPipeWireCompiler.compile(reconciled, baseConfig);
        assertFalse(baseConfig.containsKey("graph_capture_sources"));
        Map<String, Object> capture = maps(compiled.get("graph_capture_sources")).getFirst();
        Map<String, Object> route = maps(compiled.get("routes")).getFirst();

        assertEquals(Map.of(
                "source_id", sourceId,
                "instance_key", instanceKey,
                "capture_stream_key", captureKey,
                "node_name", "audio-matrix-chrome-" + captureKey,
                "application_rule_id", "chrome-tab-rule",
                "parent_application_id", "com.google.Chrome",
                "parent_process_name", "chrome",
                "tab_gain", 0.4,
                "tab_muted", true), capture);
        assertEquals(List.of(), maps(compiled.get("applications")));
        assertEquals(1.0, route.get("volume"));
        assertEquals(false, route.get("mute"));
        assertThrows(IllegalArgumentException.class,
                () -> GraphPipeWireCompiler.compile(graph, baseConfig));
    }
}
