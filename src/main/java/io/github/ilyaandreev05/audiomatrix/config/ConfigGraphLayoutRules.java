package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Editor layout and scale rules for the saved route library. */
final class ConfigGraphLayoutRules {
    private ConfigGraphLayoutRules() {}

    static void validateLayouts(Map<String,Object> config,Map<String,AudioGraph> graphs,Set<String> routeIds,List<String> errors){
        Object value=config.get("graph_ui_layout");if(value==null)return;
        if(!(value instanceof Map<?,?> layouts)){errors.add("graph_ui_layout must be an object");return;}
        for(Map.Entry<?,?> entry:layouts.entrySet()){
            String routeId=String.valueOf(entry.getKey());AudioGraph graph=graphs.get(routeId);
            if(!routeIds.contains(routeId)||graph==null){errors.add("graph_ui_layout references a missing route: "+routeId);continue;}
            if(!(entry.getValue() instanceof Map<?,?> nodeLayouts)){errors.add("graph_ui_layout["+routeId+"] must be an object");continue;}
            Set<String> nodeIds=graph.nodes().stream().map(GraphNode::id).collect(java.util.stream.Collectors.toSet());
            for(Map.Entry<?,?> nodeEntry:nodeLayouts.entrySet()){
                String nodeId=String.valueOf(nodeEntry.getKey());if(!nodeIds.contains(nodeId)){errors.add("graph_ui_layout["+routeId+"] references a missing node: "+nodeId);continue;}
                if(!(nodeEntry.getValue() instanceof Map<?,?> point)){errors.add("graph_ui_layout["+routeId+"]."+nodeId+" must be an object");continue;}
                for(String axis:List.of("x","y")){Object coordinate=point.get(axis);if(!(coordinate instanceof Number number)||!Double.isFinite(number.doubleValue())||number.doubleValue()<0||number.doubleValue()>10000)errors.add("graph_ui_layout["+routeId+"]."+nodeId+"."+axis+" must be between 0 and 10000");}
                if(point.size()!=2)errors.add("graph_ui_layout["+routeId+"]."+nodeId+" must only contain x and y");
            }
        }
    }

    static void validateUiScales(Map<String,Object> config,Set<String> routeIds,List<String> errors){
        Object value=config.get("graph_ui_scale");if(value==null)return;
        if(!(value instanceof Map<?,?> scales)){errors.add("graph_ui_scale must be an object");return;}
        for(Map.Entry<?,?> entry:scales.entrySet()){
            String routeId=String.valueOf(entry.getKey());Object scale=entry.getValue();
            if(!routeIds.contains(routeId)){errors.add("graph_ui_scale references a missing route: "+routeId);continue;}
            if(!(scale instanceof Number number)||!Double.isFinite(number.doubleValue())||number.doubleValue()<0.5||number.doubleValue()>2.0)
                errors.add("graph_ui_scale["+routeId+"] must be between 0.5 and 2.0");
        }
    }
}
