package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphOutputChannelIsolation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Physical output assignment rules for the saved route library. */
final class ConfigGraphPhysicalRules {
    private ConfigGraphPhysicalRules() {}

    /** Inactive routes may pre-bind common hardware, but must be internally consistent. */
    static void validate(Map<String,AudioGraph> graphs,String activeRoute,List<String> errors){
        String commonId=GraphCommonVibro.COMMON_ROUTE_ID;
        AudioGraph common=graphs.get(commonId);
        Map<String,List<GraphNode>> commonEndpoints=common==null?Map.of():endpoints(common);
        for(Map.Entry<String,AudioGraph> entry:graphs.entrySet()){
            String routeId=entry.getKey();
            Map<String,List<GraphNode>> assigned=new HashMap<>();
            AudioGraph graph=entry.getValue();
            for(GraphNode node:graph.nodes()){
                if(!GraphOutputChannelIsolation.isEndpoint(graph,node))continue;
                String device=GraphOutputChannelIsolation.deviceReference(node);
                if(device.isBlank()||"unbound".equals(device)||"system_default".equals(device))continue;
                List<GraphNode> previous=assigned.computeIfAbsent(device,ignored->new ArrayList<>());
                if(previous.stream().anyMatch(other->!GraphOutputChannelIsolation.disjoint(other,node)))
                    errors.add("graph "+routeId+" node "+node.id()+" duplicates physical output "+device);
                previous.add(node);
                if(!commonId.equals(routeId)&&routeId.equals(activeRoute)
                        &&commonEndpoints.getOrDefault(device,List.of()).stream()
                                .anyMatch(other->!GraphOutputChannelIsolation.disjoint(other,node)))
                    errors.add("graph "+routeId+" node "+node.id()+" reuses the common physical output "+device);
            }
        }
    }

    private static Map<String,List<GraphNode>> endpoints(AudioGraph graph){
        Map<String,List<GraphNode>> result=new HashMap<>();
        for(GraphNode node:graph.nodes()){
            if(!GraphOutputChannelIsolation.isEndpoint(graph,node))continue;
            String device=GraphOutputChannelIsolation.deviceReference(node);
            if(device.isBlank()||"unbound".equals(device)||"system_default".equals(device))continue;
            result.computeIfAbsent(device,ignored->new ArrayList<>()).add(node);
        }
        return result;
    }
}
