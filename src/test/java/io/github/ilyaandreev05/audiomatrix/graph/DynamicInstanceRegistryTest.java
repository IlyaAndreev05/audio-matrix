package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DynamicInstanceRegistryTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test void applicationIdsLevelsAndChildNodesSurviveDisappearAndReappear() {
        DynamicGeneratorNode generator = generator("apps", "application:unassigned", "applications",
                "default-application", Map.of("node_id", "merge", "port_id", "applications"));
        AudioGraph graph = new AudioGraph("route", "Dynamic apps", List.of(generator,
                new MixerNode("merge", "Merge", STEREO, Map.of("applications", 1.0)),
                new OutputNode("out", "Monitor", "device:monitor", STEREO)),
                List.of(edge("merge-out", "merge", "out", "out", "in")));
        DynamicInstanceRegistry.InstanceSnapshot app = DynamicInstanceRegistry.application(stream(7,
                "org.example.music", "music-player", "Music"));
        DynamicInstanceRegistry.ApplicationGainResolver appRule = (selector, instance) -> {
            assertEquals("application:org.example.music", selector);
            return new DynamicInstanceRegistry.GainState(0.37, true);
        };

        DynamicInstanceRegistry.ReconcileResult first = DynamicInstanceRegistry.reconcile(graph,
                Map.of("apps", List.of(app)), appRule);
        DynamicInstanceRegistry.RuntimeInstance online = first.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("apps", "app:org.example.music"));
        assertTrue(first.configChanged());
        assertTrue(online.available());
        assertTrue(online.bindingAvailable());
        String sourceId = online.nodeIds().get("source");
        String gainId = online.nodeIds().get("gain");
        assertNotEquals(sourceId, gainId);
        GainNode onlineGain = DynamicInstanceRegistry.generatedGain(first, "apps", "app:org.example.music");
        assertEquals(0.37, onlineGain.gain());
        assertTrue(onlineGain.muted());

        DynamicInstanceRegistry.ReconcileResult missing = DynamicInstanceRegistry.reconcile(first.configuredGraph(),
                Map.of(), appRule);
        DynamicInstanceRegistry.RuntimeInstance offline = missing.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("apps", "app:org.example.music"));
        assertFalse(missing.configChanged());
        assertFalse(offline.available());
        assertFalse(offline.bindingAvailable());
        assertEquals(sourceId, offline.nodeIds().get("source"));
        assertEquals(gainId, offline.nodeIds().get("gain"));
        assertEquals(0.37, DynamicInstanceRegistry.generatedGain(missing, "apps", "app:org.example.music").gain());

        DynamicInstanceRegistry.ReconcileResult returned = DynamicInstanceRegistry.reconcile(missing.configuredGraph(),
                Map.of("apps", List.of(app)), appRule);
        DynamicInstanceRegistry.RuntimeInstance reappeared = returned.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("apps", "app:org.example.music"));
        assertFalse(returned.configChanged());
        assertTrue(reappeared.available());
        assertEquals(sourceId, reappeared.nodeIds().get("source"));
        assertEquals(gainId, reappeared.nodeIds().get("gain"));
    }

    @Test void ownedAndEventSoundStudioStreamsAreExcludedIndependentlyOfSelector() {
        DynamicGeneratorNode generator = generator("apps", "application:unassigned", "applications",
                "default-application", Map.of());
        AudioGraph graph = new AudioGraph("route", "Dynamic apps", List.of(generator), List.of());
        List<DynamicInstanceRegistry.InstanceSnapshot> snapshot = List.of(
                DynamicInstanceRegistry.application(stream(1, "io.github.ilyaandree05.eventsoundstudio", "pw-cat", "ESS")),
                DynamicInstanceRegistry.application(stream(2, "io.github.ilyaandree05.audiomatrix", "audio-matrix", "Audio Matrix")),
                DynamicInstanceRegistry.application(stream(3, "org.example.music", "music-player", "Music")));

        DynamicInstanceRegistry.ReconcileResult result = DynamicInstanceRegistry.reconcile(graph,
                Map.of("apps", snapshot));

        assertEquals(1, result.instances().size());
        assertTrue(result.instances().containsKey(new DynamicInstanceRegistry.InstanceAddress("apps", "app:org.example.music")));
        assertTrue(result.effectiveGraph().nodes().stream().noneMatch(node -> node.name().contains("ESS")
                || node.name().contains("Audio Matrix")));
    }

    @Test void explicitFalseOverridesRemainPresentAndApplicationGainCannotBeDuplicated() {
        DynamicGeneratorNode generator = generator("tabs", "browser:*", "browser_tabs", "default-tab", Map.of());
        AudioGraph graph = new AudioGraph("route-tabs", "Tabs", List.of(generator), List.of());
        DynamicInstanceRegistry.InstanceSnapshot tab = DynamicInstanceRegistry.browserTab("chrome", "profile-instance",
                "Default", "session-token", 99, "Documentation", true, false, "captured", "capture-key",
                "app-rule", "org.example.browser", "browser");
        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(graph,
                Map.of("tabs", List.of(tab)));
        Map<String, Object> route = new LinkedHashMap<>(GraphCodec.toMap(reconciled.configuredGraph()));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("route_library", new ArrayList<>(List.of(route)));

        assertTrue(DynamicInstanceRegistry.setInstanceOverride(config, "route-tabs", "tabs",
                tab.stableKey(), "muted", false));
        assertEquals(false, DynamicInstanceRegistry.instanceOverride(config, "route-tabs", "tabs",
                tab.stableKey(), "muted").orElseThrow());
        assertFalse(DynamicInstanceRegistry.setInstanceOverride(config, "route-tabs", "tabs",
                tab.stableKey(), "muted", false));
        assertEquals(new DynamicInstanceRegistry.GainState(1.0, false),
                DynamicInstanceRegistry.instanceGainBaseline(config, "route-tabs", "tabs"));
        assertThrows(IllegalArgumentException.class, () -> DynamicInstanceRegistry.setInstanceOverride(config,
                "route-tabs", "tabs", tab.stableKey(), "gain", 1.1));
        assertTrue(DynamicInstanceRegistry.generatedGain(reconciled, "tabs", tab.stableKey()).id()
                .equals(reconciled.instances().get(new DynamicInstanceRegistry.InstanceAddress("tabs", tab.stableKey()))
                        .nodeIds().get("gain")));
    }

    @Test void outputPortsAreDeviceIdentityStableAndNewUnknownSinksStayInactive() {
        SwitchNode multiSwitch = new SwitchNode("multi", "Outputs", STEREO, List.of("unused"), List.of());
        DynamicGeneratorNode generator = generator("outputs", "device:*", "outputs", "default-output",
                Map.of("node_id", "multi", "port_id", "in"));
        AudioGraph graph = new AudioGraph("route-outputs", "Output routes", List.of(generator, multiSwitch), List.of());
        AudioNode hdmi = output(11, "alsa_output.pci-0000_09_00.1.hdmi-stereo", "HDMI monitor");
        AudioNode unknown = output(12, "alsa_output.example.unknown", "Unknown output");

        DynamicInstanceRegistry.ReconcileResult result = DynamicInstanceRegistry.reconcile(graph,
                Map.of("outputs", List.of(DynamicInstanceRegistry.output(hdmi, true),
                        DynamicInstanceRegistry.output(unknown))));
        DynamicInstanceRegistry.RuntimeInstance hdmiInstance = result.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("outputs", "device:" + hdmi.identity().fingerprint()));
        DynamicInstanceRegistry.RuntimeInstance unknownInstance = result.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("outputs", "device:" + unknown.identity().fingerprint()));
        SwitchNode reconciledSwitch = result.configuredGraph().nodes().stream().filter(SwitchNode.class::isInstance)
                .map(SwitchNode.class::cast).findFirst().orElseThrow();
        String selectedPort = DynamicInstanceRegistry.stableOutputPortId("outputs", hdmi.identity().fingerprint());
        String unknownPort = DynamicInstanceRegistry.stableOutputPortId("outputs", unknown.identity().fingerprint());
        assertTrue(hdmiInstance.available());
        assertTrue(unknownInstance.available());
        assertTrue(reconciledSwitch.outputs().stream().anyMatch(port -> port.id().equals(selectedPort)));
        assertTrue(reconciledSwitch.outputs().stream().anyMatch(port -> port.id().equals(unknownPort)));
        assertTrue(reconciledSwitch.activeLinks().contains("in->" + selectedPort));
        assertFalse(reconciledSwitch.activeLinks().contains("in->" + unknownPort));
        assertTrue(result.effectiveGraph().connections().stream().anyMatch(edge -> edge.fromNode().equals("multi")
                && edge.fromPort().equals(selectedPort)));

        DynamicGeneratorNode transitioned = result.configuredGraph().nodes().stream()
                .filter(DynamicGeneratorNode.class::isInstance).map(DynamicGeneratorNode.class::cast)
                .findFirst().orElseThrow();
        assertFalse(transitioned.autoSelectTrustedHdmiOnFirstDiscovery());
        DynamicInstanceRegistry.ReconcileResult missing = DynamicInstanceRegistry.reconcile(result.configuredGraph(), Map.of());
        DynamicInstanceRegistry.RuntimeInstance offline = missing.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("outputs", "device:" + hdmi.identity().fingerprint()));
        assertFalse(missing.configChanged());
        assertFalse(offline.available());
        assertFalse(offline.bindingAvailable());
        assertEquals(hdmiInstance.nodeIds(), offline.nodeIds());
        DynamicInstanceRegistry.ReconcileResult returned = DynamicInstanceRegistry.reconcile(missing.configuredGraph(),
                Map.of("outputs", List.of(DynamicInstanceRegistry.output(hdmi))));
        assertFalse(returned.configChanged());
        assertEquals(hdmiInstance.nodeIds(), returned.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("outputs", "device:" + hdmi.identity().fingerprint())).nodeIds());
        SwitchNode returnedSwitch = returned.configuredGraph().nodes().stream().filter(SwitchNode.class::isInstance)
                .map(SwitchNode.class::cast).findFirst().orElseThrow();
        assertTrue(returnedSwitch.activeLinks().contains("in->" + selectedPort));
    }

    @Test void browserTabsNestUnderAnOfflineLogicalParentWithoutInventingPcm() {
        DynamicGeneratorNode apps = generator("apps", "application:unassigned", "applications",
                "default-application", Map.of());
        DynamicGeneratorNode tabs = generator("tabs", "browser:*", "browser_tabs", "default-tab", Map.of());
        AudioGraph graph = new AudioGraph("route-browser", "Browser", List.of(apps, tabs), List.of());
        DynamicInstanceRegistry.InstanceSnapshot parent =
                DynamicInstanceRegistry.browserParentApplication("chrome", "", "", "Chrome");
        DynamicInstanceRegistry.InstanceSnapshot tab = DynamicInstanceRegistry.browserTab("chrome", "instance",
                "Default", "session-1", 12, "Docs", true, false, "captured", "capture-1");
        String parentComposite = DynamicInstanceRegistry.stableCompositeId("apps", "browser-parent:chrome");
        

        DynamicInstanceRegistry.ReconcileResult result = DynamicInstanceRegistry.reconcile(graph,
                Map.of("apps", List.of(parent), "tabs", List.of(tab)), Map.of("tabs", parentComposite), null);
        DynamicInstanceRegistry.RuntimeInstance parentState = result.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("apps", "browser-parent:chrome"));
        DynamicInstanceRegistry.RuntimeInstance tabState = result.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("tabs", tab.stableKey()));
        GraphSubgraph tabComposite = result.generatedSubgraphs().stream()
                .filter(group -> group.id().equals(tabState.compositeId())).findFirst().orElseThrow();

        assertFalse(parentState.available());
        assertFalse(parentState.bindingAvailable());
        assertTrue(parentState.runtimeBinding().get(\"streams\") instanceof List<?> streams && streams.isEmpty());
        assertTrue(tabState.available());
        assertTrue(tabState.bindingAvailable());
        assertFalse(tabState.controlAvailable());
        assertEquals(parentComposite, tabComposite.parentId());
        assertEquals("tabs", tabComposite.generatorId());
    }

    private static DynamicGeneratorNode generator(String id, String selector, String registry, String template,
                                                  Map<String, Object> connectTo) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("registry", registry);
        parameters.put("template", template);
        parameters.put("connect_to", connectTo);
        parameters.put("instances", Map.of());
        return new DynamicGeneratorNode(id, "Generator " + id, selector, STEREO, parameters);
    }

    private static AudioNode stream(int id, String applicationId, String process, String name) {
        return new AudioNode(id, "stream-" + id, name, "Stream/Output/Audio", "running", name, process,
                applicationId, 2, List.of(48_000), 1.0, false, null);
    }

    private static AudioNode output(int id, String name, String description) {
        Map<String, String> properties = Map.of("device.bus-path", "pci-0000:01:00.0", "device.serial", "serial-" + id,
                "device.profile.name", "hdmi-stereo", "device.port.name", "hdmi-output-" + id, "audio.position", "FL,FR");
        DeviceIdentity identity = DeviceIdentity.from(properties, 2);
        return new AudioNode(id, name, description, "Audio/Sink", "running", "", "", "", 2,
                List.of(48_000), 0.6, false, null, identity);
    }

    private static GraphConnection edge(String id, String from, String fromPort, String to, String toPort) {
        return new GraphConnection(id, from, fromPort, to, toPort, true, 1);
    }
}
