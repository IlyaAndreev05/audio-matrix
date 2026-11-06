package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;
import io.github.ilyaandreev05.audiomatrix.config.ConfigValidator;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.STEREO;
import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.link;

class GraphSplitVibroDeviceTest {
    private static AudioGraph graph(List<String> outputs) {
        SourceNode source = new SourceNode("source", "Events", "application:unassigned", STEREO);
        DeviceSlotNode vibro = new DeviceSlotNode(GraphCommonVibro.VIBRO_OUTPUT_ID, "Vibro", "card", STEREO);
        OutputNode rear = new OutputNode("rear", "Rear extension", "card", STEREO,
                new ChannelMatrix(STEREO, outputs, List.of(List.of(1.0, 0.0), List.of(0.0, 1.0))));
        return new AudioGraph("route", "Split", List.of(source, vibro, rear), List.of(
                link("source", "out", vibro.id(), "in"), link("source", "out", rear.id(), "in")));
    }

    @Test void allowsOnlyDisjointRearPairOnSharedVibroCard() {
        assertDoesNotThrow(() -> GraphPipeWireCompiler.compile(graph(List.of("RL", "RR")), DefaultConfig.create()));
    }

    private static AudioGraph mixedGraph(int release) {
        SourceNode a = new SourceNode("a", "A", "application:a", STEREO);
        SourceNode b = new SourceNode("b", "B", "application:b", STEREO);
        MixerNode mixer = new MixerNode("mix", "Mix", STEREO, java.util.Map.of("a", 1.0, "b", 1.0));
        FilterNode limiter = new FilterNode("limiter", "Limiter", STEREO, "limiter", true,
                java.util.Map.of("ceiling_db", -1, "lookahead_ms", 5, "release_ms", release));
        DeviceSlotNode vibro = new DeviceSlotNode(GraphCommonVibro.VIBRO_OUTPUT_ID, "Vibro", "card", STEREO);
        OutputNode rear = new OutputNode("rear", "Rear", "card", STEREO,
                new ChannelMatrix(STEREO, List.of("RL", "RR"),
                        List.of(List.of(1.0, 0.0), List.of(0.0, 1.0))));
        return new AudioGraph("route", "Split", List.of(a, b, mixer, limiter, vibro, rear), List.of(
                link("a", "out", "mix", "a"), link("b", "out", "mix", "b"),
                link("mix", "out", "limiter", "in"), link("limiter", "out", vibro.id(), "in"),
                link("mix", "out", "rear", "in")));
    }

    @Test void equivalentIntegerAndDoubleLimiterSettingsShareOnePhysicalLimiter() {
        assertDoesNotThrow(() -> GraphPipeWireCompiler.compile(mixedGraph(80), DefaultConfig.create()));
    }

    @Test void genuinelyDifferentLimiterSettingsAreStillRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> GraphPipeWireCompiler.compile(mixedGraph(40), DefaultConfig.create()));
    }

    @Test void devicePrefixAliasesStillRequireTheSameLimiter() {
        AudioGraph original = mixedGraph(40);
        var nodes = new java.util.ArrayList<GraphNode>();
        for (GraphNode node : original.nodes()) {
            if (node instanceof OutputNode output && output.id().equals("rear"))
                nodes.add(new OutputNode(output.id(), output.name(), "device:card", STEREO, output.channelMatrix()));
            else nodes.add(node);
        }
        var aliased = new AudioGraph(original.id(), original.name(), nodes, original.connections());
        assertThrows(IllegalArgumentException.class, () -> GraphPipeWireCompiler.compile(aliased, DefaultConfig.create()));
    }

    @Test void savedSplitRoutePassesWholeConfigurationValidation() {
        var config = DefaultConfig.create();
        config.put("devices", new java.util.ArrayList<>(List.of(DefaultConfig.map("id", "card",
                "name", "Card", "selector", DefaultConfig.map("kind", "name_equals", "value", "test-card"),
                "volume", 0.064, "mute", false))));
        GraphRouteLibrary.saveRoute(config, graph(List.of("RL", "RR")), "");
        assertEquals(List.of(), ConfigValidator.validate(config));
    }

    @Test void disconnectedRearOutputDoesNotProduceAnAudioRoute() {
        AudioGraph connected = graph(List.of("RL", "RR"));
        AudioGraph idleRear = new AudioGraph(connected.id(), connected.name(), connected.nodes(),
                connected.connections().stream().filter(edge -> !edge.toNode().equals("rear")).toList());
        var compiled = GraphPipeWireCompiler.compile(idleRear, DefaultConfig.create());
        @SuppressWarnings("unchecked") var routes = (List<java.util.Map<String, Object>>) compiled.get("routes");
        assertEquals(1, routes.size());
        assertFalse(routes.getFirst().containsKey("channel_matrix"));
    }

    @Test void rejectsTwoRearOutputsSharingTheSamePair() {
        AudioGraph original = graph(List.of("RL", "RR"));
        var nodes = new java.util.ArrayList<>(original.nodes());
        nodes.add(new OutputNode("rear2", "Second rear", "card", STEREO,
                new ChannelMatrix(STEREO, List.of("RL", "RR"),
                        List.of(List.of(1.0, 0.0), List.of(0.0, 1.0)))));
        var config = DefaultConfig.create();
        config.put("devices", new java.util.ArrayList<>(List.of(DefaultConfig.map("id", "card",
                "name", "Card", "selector", DefaultConfig.map("kind", "name_equals", "value", "test-card"),
                "volume", 0.064, "mute", false))));
        GraphRouteLibrary.saveRoute(config, new AudioGraph(original.id(), original.name(), nodes,
                original.connections()), "");
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(error -> error.contains("duplicates physical output")));
    }

    @Test void rejectsRearMatrixThatIncludesFrontChannel() {
        assertThrows(IllegalArgumentException.class,
                () -> GraphPipeWireCompiler.compile(graph(List.of("FL", "RR")), DefaultConfig.create()));
    }
    private static OutputNode matrix(String id, String device, List<String> channels) {
        return new OutputNode(id, id, device, STEREO,
                new ChannelMatrix(STEREO, channels, List.of(List.of(1.0, 0.0), List.of(0.0, 0.0))));
    }

    private static AudioGraph endpoints(GraphNode... endpoints) {
        var nodes = new java.util.ArrayList<GraphNode>();
        nodes.add(new SourceNode("source", "Source", "application:unassigned", STEREO));
        nodes.addAll(List.of(endpoints));
        var links = new java.util.ArrayList<GraphConnection>();
        for (GraphNode endpoint : endpoints) links.add(link("source", "out", endpoint.id(), "in"));
        return new AudioGraph("arbitrary", "Arbitrary", nodes, links);
    }

    @Test void arbitraryMatricesCanShareAnOutputRegardlessOfOrderAndZeroRows() {
        var front = matrix("alpha", "device:card", List.of("FR", "FL"));
        var rear = matrix("beta", "card", List.of("SR", "SL"));
        var compiled = GraphPipeWireCompiler.compile(endpoints(front, rear), DefaultConfig.create());
        @SuppressWarnings("unchecked") var routes = (List<java.util.Map<String, Object>>) compiled.get("routes");
        assertEquals(2, routes.size());
        assertTrue(routes.stream().allMatch(route -> "card".equals(route.get("to"))));
        var overlapsZeroRow = matrix("gamma", "card", List.of("RL", "FL"));
        assertThrows(IllegalArgumentException.class,
                () -> GraphPipeWireCompiler.compile(endpoints(front, overlapsZeroRow), DefaultConfig.create()));
    }

    @Test void passthroughAndArbitrarySlotShareOnlyDisjointMatrixPositions() {
        var rear = matrix("matrix", "device:card", List.of("RR", "RL"));
        var passthrough = new OutputNode("plain", "Plain", "card", STEREO);
        var slot = new DeviceSlotNode("custom-slot", "Slot", "card", STEREO);
        assertDoesNotThrow(() -> GraphPipeWireCompiler.compile(endpoints(passthrough, rear), DefaultConfig.create()));
        assertDoesNotThrow(() -> GraphPipeWireCompiler.compile(endpoints(slot, rear), DefaultConfig.create()));
        assertThrows(IllegalArgumentException.class,
                () -> GraphPipeWireCompiler.compile(endpoints(slot, passthrough), DefaultConfig.create()));
        var mono = new DeviceSlotNode("mono", "Mono", "card", List.of("MONO"));
        assertFalse(GraphOutputChannelIsolation.disjoint(mono, rear));
    }

    @Test void allPairsAreCheckedWithoutVibro() {
        var first = matrix("one", "card", List.of("FL", "FR"));
        var second = matrix("two", "card", List.of("RL", "RR"));
        var third = matrix("three", "device:card", List.of("RR", "RC"));
        assertThrows(IllegalArgumentException.class,
                () -> GraphPipeWireCompiler.compile(endpoints(first, second, third), DefaultConfig.create()));
    }
}
