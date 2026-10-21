package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared graph fixtures for the subgraph library test classes. */
final class GraphSubgraphLibraryFixtures {
    static final List<String> STEREO = List.of("FL", "FR");

    private GraphSubgraphLibraryFixtures() {}

    static Map<String,Object> configWithGroups(AudioGraph graph, List<Map<String,Object>> groups) {
        Map<String,Object> config = DefaultConfig.create();
        GraphRouteLibrary.saveRoute(config, graph, "");
        GraphSubgraphLibrary.mutableByRoute(config).put(graph.id(), new ArrayList<>(groups));
        return config;
    }

    static void storeSubgraphs(Map<String,Object> config, String routeId, List<GraphSubgraph> additions) {
        List<GraphSubgraph> groups = new ArrayList<>(GraphSubgraphLibrary.forRoute(config, routeId));
        groups.addAll(additions);
        GraphSubgraphLibrary.mutableByRoute(config).put(routeId, new ArrayList<>(GraphSubgraphLibrary.encoded(groups)));
    }

    static AudioGraph graphWithGenerator(Map<String,String> composites) {
        AudioGraph graph = new AudioGraph("route-subgraph", "Subgraph test", List.of(
                new DynamicGeneratorNode("generator", "Browser generator", "application:unassigned", STEREO),
                new GainNode("gain-a", "Input gain", STEREO, .8, false),
                new GainNode("gain-b", "Output gain", STEREO, .6, false),
                new OutputNode("output", "Monitor", "device:monitor", STEREO)), List.of(
                edge("one", "generator", "gain-a"), edge("two", "gain-a", "gain-b"), edge("three", "gain-b", "output")));
        return withGeneratorInstances(graph, composites);
    }

    static AudioGraph withGeneratorInstances(AudioGraph graph, Map<String,String> composites) {
        List<GraphNode> nodes = new ArrayList<>(graph.nodes());
        DynamicGeneratorNode generator = nodes.stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).findFirst().orElseThrow();
        Map<String,Object> instances = new LinkedHashMap<>(generator.instances());
        for (Map.Entry<String,String> entry : composites.entrySet()) {
            String sourceId = "generated-" + entry.getKey() + "-source";
            String gainId = "generated-" + entry.getKey() + "-gain";
            nodes.add(new SourceNode(sourceId, "Generated source", "application:" + entry.getKey(), STEREO));
            nodes.add(new GainNode(gainId, "Generated gain", STEREO, 1, false));
            instances.put(entry.getKey(), Map.of(
                    "node_ids", Map.of("source", sourceId, "gain", gainId),
                    "composite_id", entry.getValue(), "overrides", Map.of()));
        }
        nodes.replaceAll(node -> node.id().equals(generator.id()) ? generator.withInstances(instances) : node);
        return new AudioGraph(graph.id(), graph.name(), nodes, graph.connections());
    }

    static AudioGraph graph() {
        return new AudioGraph("route-subgraph", "Subgraph test", List.of(
                new SourceNode("source", "Browser", "application:chrome", STEREO),
                new GainNode("gain-a", "Input gain", STEREO, .8, false),
                new GainNode("gain-b", "Output gain", STEREO, .6, false),
                new OutputNode("output", "Monitor", "device:monitor", STEREO)), List.of(
                edge("one", "source", "gain-a"), edge("two", "gain-a", "gain-b"), edge("three", "gain-b", "output")));
    }

    static GraphConnection edge(String id, String from, String to) {
        return new GraphConnection(id, from, "out", to, "in", true, 1);
    }

    @SuppressWarnings("unchecked") static Map<String,Object> route(Map<String,Object> config) {
        return ((List<Map<String,Object>>) config.get("route_library")).stream()
                .filter(item -> "route-subgraph".equals(item.get("id"))).findFirst().orElseThrow();
    }
}
