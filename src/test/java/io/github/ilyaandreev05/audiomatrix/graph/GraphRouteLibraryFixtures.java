package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;

import java.util.List;
import java.util.Map;

/** Shared graph fixtures for the route library test classes. */
final class GraphRouteLibraryFixtures {
    static final List<String> STEREO=List.of("FL","FR");

    private GraphRouteLibraryFixtures(){}

    static AudioGraph graph(String id,String name,String group){return new AudioGraph(id,name,List.of(
            new SourceNode(id+"-source","Chrome","application:chrome",STEREO),
            new GainNode(id+"-gain","Chrome volume",STEREO,.7,false),
            new OutputNode(id+"-output","Monitor","device:monitor",STEREO)),List.of(
            new GraphConnection(id+"-link-a",id+"-source","out",id+"-gain","in",true,1),
            new GraphConnection(id+"-link-b",id+"-gain","out",id+"-output","in",true,1)));}

    static DynamicGeneratorNode dynamicGenerator(AudioGraph graph){
        return graph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).findFirst().orElseThrow();
    }

    static GraphSubgraph subgraph(Map<String,Object> config,String routeId,String name){
        return GraphSubgraphLibrary.forRoute(config,routeId).stream().filter(group->name.equals(group.name()))
                .findFirst().orElseThrow();
    }

    static Map<String,Object> pin(Map<String,Object> config,String routeId,String nodeId){
        return QuickSettingsConfig.pins(config).stream().filter(value->routeId.equals(value.get("route_id"))
                &&nodeId.equals(value.get("node_id"))).findFirst().orElseThrow();
    }
}
