package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.ilyaandreev05.audiomatrix.ui.MixerCommandFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The Mixer input commands must materialize a runtime-generated port that only ever existed in the
 * effective graph. Before this behavior existed the saved graph was the only source of truth, so a
 * slider move in Quick Settings failed with {@code Mixer input no longer exists} even though the
 * published panel showed a live level for that exact port.
 */
class GnomeGraphMixerCommandsTest {

    @Test void generatedInputMaterializesFromItsLiveOwnerAndSurvivesReconciliation() {
        Map<String, Object> config = config();
        DynamicInstanceRegistry.ReconcileResult live = registry(config);
        String port = generatedPort(live);
        assertFalse(savedMixer(config).inputGains().containsKey(port),
                "the runtime port is absent from the saved graph, which is exactly the reported failure");
        assertTrue(effectiveMixer(live).inputGains().containsKey(port));

        GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID, MIXER, port, 0.35, null, false, live);

        MixerNode saved = savedMixer(config);
        assertEquals(0.35, saved.inputGains().get(port), 1e-9);
        assertFalse(saved.inputMuted(port));
        assertEquals(1.0, saved.inputGains().get("applications"), 1e-9, "other inputs keep their saved levels");
        assertEquals(2, nodes(config).size(), "only the saved topology may be serialized");
        assertEquals(1, edges(config).size(), "no runtime boundary connection is persisted");
        assertEquals(port, saved.inputGains().keySet().stream()
                .filter(DynamicInstanceRegistry::isGeneratedMixerInputPort).findFirst().orElseThrow());

        DynamicInstanceRegistry.ReconcileResult reconnected = registry(config);
        assertEquals(port, generatedPort(reconnected), "the port identity is stable across a reconnect");
        assertEquals(0.35, effectiveMixer(reconnected).inputGains().get(port), 1e-9,
                "reconciliation keeps the level the user set");
    }

    @Test void generatedInputMuteRetainsTheLevelAndASliderMoveUnmutesIt() {
        Map<String, Object> config = config();
        String port = generatedPort(registry(config));
        GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID, MIXER, port, 0.35, null, false, registry(config));

        GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID, MIXER, port, null, null, true, registry(config));
        assertEquals(0.35, savedMixer(config).inputGains().get(port), 1e-9, "mute never discards the level");
        assertTrue(savedMixer(config).inputMuted(port));
        assertTrue(effectiveMixer(registry(config)).inputMuted(port));

        GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID, MIXER, port, 0.6, null, false, registry(config));
        assertEquals(0.6, savedMixer(config).inputGains().get(port), 1e-9);
        assertFalse(savedMixer(config).inputMuted(port), "moving the slider unmutes the input");

        GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID, MIXER, port, null, true, false, registry(config));
        assertTrue(savedMixer(config).inputMuted(port), "the desktop callback shares the same mutation");
        assertEquals(0.6, savedMixer(config).inputGains().get(port), 1e-9);
    }

    @Test void staticInputsKeepTheirOwnSemanticsAndAMissingPortIsRejected() {
        Map<String, Object> config = config();
        GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID, MIXER, "applications", 0.7, null, false, null);
        assertEquals(0.7, savedMixer(config).inputGains().get("applications"), 1e-9);
        assertEquals(1, savedMixer(config).inputGains().size(), "a static edit never materializes a generated port");

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID, MIXER, "removed", 0.4, null, false, null));
        assertEquals("Mixer input no longer exists", missing.getMessage());
        assertEquals(1, savedMixer(config).inputGains().size());

        IllegalArgumentException volume = assertThrows(IllegalArgumentException.class,
                () -> GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID, MIXER, "applications", 1.4, null, false, null));
        assertEquals("Mixer input volume must be between 0 and 1", volume.getMessage());
        assertEquals(0.7, savedMixer(config).inputGains().get("applications"), 1e-9);
    }

    @Test void foreignRouteMixerAndUnknownRuntimePortNeverCreateConfiguration() {
        Map<String, Object> config = config();
        DynamicInstanceRegistry.ReconcileResult live = registry(config);
        String port = generatedPort(live);
        Map<String, Object> before = snapshot(config);

        assertThrows(IllegalArgumentException.class,
                () -> GnomeGraphMixerCommands.changeMixerInput(config, "other-route", MIXER, port, 0.3, null, false, live));
        assertThrows(IllegalArgumentException.class,
                () -> GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID, "other-mixer", port, 0.3, null, false, live));
        assertThrows(IllegalArgumentException.class, () -> GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID,
                MIXER, DynamicInstanceRegistry.stableMixerInputPortId(GENERATOR, "app:org.example.Absent"),
                0.3, null, false, live));
        assertThrows(IllegalArgumentException.class,
                () -> GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID, MIXER, port, 0.3, null, false, null));
        assertEquals(before, snapshot(config), "a rejected command leaves the configuration untouched");
    }

    @Test void removedInstanceOfflineBindingAndMissingBoundaryAreNotOwners() {
        Map<String, Object> config = config();
        String port = generatedPort(registry(config));
        Map<String, Object> before = snapshot(config);

        DynamicInstanceRegistry.ReconcileResult removed = DynamicInstanceRegistry.reconcile(savedGraph(config),
                Map.of(GENERATOR, List.of()));
        assertTrue(removed.instances().isEmpty(), "the stream disappeared from the discovery");
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID, MIXER, port, 0.3, null, false, removed));
        assertEquals("Mixer input has no live owner", missing.getMessage());

        DynamicInstanceRegistry.ReconcileResult offline = manualRegistry(config, port, false, true, true);
        assertThrows(IllegalArgumentException.class,
                () -> GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID, MIXER, port, 0.3, null, false, offline));

        DynamicInstanceRegistry.ReconcileResult unbound = manualRegistry(config, port, true, false, true);
        assertThrows(IllegalArgumentException.class,
                () -> GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID, MIXER, port, 0.3, null, false, unbound));

        DynamicInstanceRegistry.ReconcileResult boundaryless = manualRegistry(config, port, true, true, false);
        assertThrows(IllegalArgumentException.class,
                () -> GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID, MIXER, port, 0.3, null, false, boundaryless));

        DynamicInstanceRegistry.ReconcileResult staleDefinition = DynamicInstanceRegistry.reconcile(
                staleGeneratorGraph(config), Map.of(GENERATOR, List.of(DynamicInstanceRegistry.application(stream(51)))));
        assertEquals(port, generatedPort(staleDefinition));
        assertThrows(IllegalArgumentException.class, () -> GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID,
                MIXER, port, 0.3, null, false, staleDefinition));

        assertEquals(before, snapshot(config), "no rejected owner can write a port, a level, or a mute");
    }

    @Test void liveManualRegistryProvesTheValidatorAcceptsExactlyTheValidatedOwner() {
        Map<String, Object> config = config();
        String port = generatedPort(registry(config));
        GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID, MIXER, port, 0.25, null, false,
                manualRegistry(config, port, true, true, true));
        assertEquals(0.25, savedMixer(config).inputGains().get(port), 1e-9);
    }

    @Test void generatedPortNeverChangesWithoutItsLiveDiscovery() {
        Map<String, Object> config = config();
        String port = generatedPort(registry(config));
        GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID, MIXER, port, 0.35, null, false, registry(config));
        config.put("active_route", "inactive");
        Map<String, Object> before = snapshot(config);

        IllegalArgumentException unavailable = assertThrows(IllegalArgumentException.class,
                () -> GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID, MIXER, port, 0.5, null, false, null));
        assertEquals("Mixer input registry is unavailable", unavailable.getMessage(),
                "the panel disables this row, so the command must reject it too");
        assertEquals(before, snapshot(config));

        config.put("active_route", ROUTE_ID);
        GnomeGraphMixerCommands.changeMixerInput(config, ROUTE_ID, MIXER, "applications", 0.5, null, false, null);
        assertEquals(0.5, savedMixer(config).inputGains().get("applications"), 1e-9,
                "a static saved input never needs runtime discovery");
    }
}

/** Fixtures shared by the Mixer command behavior tests. */
final class MixerCommandFixture {
    static final String ROUTE_ID = "main";
    static final String GENERATOR = "apps";
    static final String MIXER = "mixer";
    static final String STREAM_APPLICATION = "org.example.Player";
    static final List<String> CHANNELS = List.of("FL", "FR");

    static Map<String, Object> config() {
        Map<String, Object> config = DefaultConfig.create();
        config.put("active_route", ROUTE_ID);
        Map<String, Object> route = new LinkedHashMap<>(GraphCodec.toMap(graph()));
        route.put("profile_id", "main");
        route.put("group_id", "");
        config.put("route_library", new ArrayList<>(List.of(route)));
        return config;
    }

    static AudioGraph graph() {
        return graph("application:*");
    }

    static AudioGraph graph(String selector) {
        DynamicGeneratorNode generator = new DynamicGeneratorNode(GENERATOR, "Приложения", selector, CHANNELS,
                Map.of("connect_to", Map.of("node_id", MIXER, "port_id", "applications")));
        MixerNode mixer = new MixerNode(MIXER, "Микшер", CHANNELS, Map.of("applications", 1.0));
        return new AudioGraph(ROUTE_ID, "Маршрут", List.of(generator, mixer),
                List.of(new GraphConnection("apps-mixer", GENERATOR, "out", MIXER, "applications", true, 1)));
    }

    static AudioGraph staleGeneratorGraph(Map<String, Object> config) {
        AudioGraph saved = savedGraph(config);
        AudioGraph other = graph("process:player");
        List<GraphNode> nodes = new ArrayList<>(saved.nodes());
        GraphNode replacement = other.nodes().stream().filter(DynamicGeneratorNode.class::isInstance).findFirst().orElseThrow();
        nodes.replaceAll(node -> node.id().equals(GENERATOR) ? replacement : node);
        return new AudioGraph(saved.id(), saved.name(), nodes, saved.connections());
    }

    static DynamicInstanceRegistry.ReconcileResult registry(Map<String, Object> config) {
        return DynamicInstanceRegistry.reconcile(savedGraph(config),
                Map.of(GENERATOR, List.of(DynamicInstanceRegistry.application(stream(51)))));
    }

    /** A registry whose exact port and boundary exist, with the owner flags under test. */
    static DynamicInstanceRegistry.ReconcileResult manualRegistry(Map<String, Object> config, String port,
            boolean available, boolean bindingAvailable, boolean boundary) {
        AudioGraph saved = savedGraph(config);
        String gainId = DynamicInstanceRegistry.stableNodeId(GENERATOR, "app:" + STREAM_APPLICATION, "gain");
        MixerNode mixer = savedMixer(config);
        Map<String, Double> gains = new LinkedHashMap<>(mixer.inputGains());
        gains.put(port, 1.0);
        List<GraphNode> nodes = new ArrayList<>(saved.nodes());
        nodes.replaceAll(node -> node.id().equals(MIXER)
                ? new MixerNode(mixer.id(), mixer.name(), CHANNELS, gains) : node);
        nodes.add(new io.github.ilyaandreev05.audiomatrix.graph.GainNode(gainId, "Проигрыватель", CHANNELS, 0.9, false));
        List<GraphConnection> edges = new ArrayList<>(saved.connections());
        if (boundary) edges.add(new GraphConnection("generated-link", gainId, "out", MIXER, port, true, 1));
        AudioGraph effective = new AudioGraph(saved.id(), saved.name(), nodes, edges);
        DynamicInstanceRegistry.RuntimeInstance owner = new DynamicInstanceRegistry.RuntimeInstance(GENERATOR,
                "app:" + STREAM_APPLICATION, Map.of("gain", gainId), "subgraph", available, bindingAvailable,
                bindingAvailable, DynamicInstanceRegistry.Confidence.HIGH,
                Map.of("applicationName", "Проигрыватель", "hasPcmBinding", true), Map.of());
        return new DynamicInstanceRegistry.ReconcileResult(saved, effective,
                Map.of(new DynamicInstanceRegistry.InstanceAddress(GENERATOR, "app:" + STREAM_APPLICATION), owner),
                List.of(), false);
    }

    static AudioNode stream(int id) {
        return new AudioNode(id, "player-stream", "Player", "Stream/Output/Audio", "running",
                "Player", "player", STREAM_APPLICATION, 2, List.of(48000), 0.9, false, null);
    }

    static String generatedPort(DynamicInstanceRegistry.ReconcileResult registry) {
        return registry.instances().values().stream()
                .map(instance -> DynamicInstanceRegistry.stableMixerInputPortId(
                        instance.generatorId(), instance.instanceKey()))
                .findFirst().orElseThrow();
    }

    static MixerNode effectiveMixer(DynamicInstanceRegistry.ReconcileResult registry) {
        return mixer(registry.effectiveGraph());
    }

    static MixerNode savedMixer(Map<String, Object> config) {
        return mixer(savedGraph(config));
    }

    static MixerNode mixer(AudioGraph graph) {
        return graph.nodes().stream().filter(MixerNode.class::isInstance).map(MixerNode.class::cast)
                .filter(node -> node.id().equals(MIXER)).findFirst().orElseThrow();
    }

    static AudioGraph savedGraph(Map<String, Object> config) {
        return GraphCodec.fromMap(route(config));
    }

    static Map<String, Object> route(Map<String, Object> config) {
        return routes(config).stream().filter(route -> ROUTE_ID.equals(route.get("id"))).findFirst().orElseThrow();
    }

    static List<Map<String, Object>> nodes(Map<String, Object> config) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> nodes = (List<Map<String, Object>>) route(config).get("nodes");
        return nodes;
    }

    static List<Map<String, Object>> edges(Map<String, Object> config) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> edges = (List<Map<String, Object>>) route(config).get("connections");
        return edges;
    }

    /** A deep copy so a rejected command can be compared without identity tricks. */
    static Map<String, Object> snapshot(Map<String, Object> config) {
        return GraphCodec.toMap(GraphCodec.fromMap(route(config)));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> routes(Map<String, Object> config) {
        return (List<Map<String, Object>>) config.get("route_library");
    }
}
