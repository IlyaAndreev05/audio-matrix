package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphSubgraph;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Editor subgraph grouping rules for the saved route library. */
final class ConfigGraphSubgraphRules {
    private ConfigGraphSubgraphRules() {}

    static void validate(Map<String,Object> config,Map<String,AudioGraph> graphs,Set<String> routeIds,List<String> errors){
        Object raw=config.get("graph_ui_subgraphs");
        Map<?,?> byRoute;
        if(raw==null)byRoute=Map.of();
        else if(raw instanceof Map<?,?> map)byRoute=map;
        else{errors.add("graph_ui_subgraphs must be an object");byRoute=Map.of();}
        Set<String> subgraphRoutes=new HashSet<>();
        for(Map.Entry<?,?> entry:byRoute.entrySet()){
            String routeId=String.valueOf(entry.getKey());AudioGraph graph=graphs.get(routeId);
            if(!routeIds.contains(routeId)||graph==null){errors.add("graph_ui_subgraphs references a missing route: "+routeId);continue;}
            subgraphRoutes.add(routeId);
            validateList(entry.getValue(),graph,"graph_ui_subgraphs["+routeId+"]",errors);
        }
        for(Map.Entry<String,AudioGraph> entry:graphs.entrySet())
            if(routeIds.contains(entry.getKey())&&!subgraphRoutes.contains(entry.getKey()))
                validateList(List.of(),entry.getValue(),"graph_ui_subgraphs["+entry.getKey()+"]",errors);
        List<Map<String,Object>> copies=ConfigValidationValues.optionalList(config.get("saved_copies"),"saved_copies",new ArrayList<>());
        for(int i=0;i<copies.size();i++){
            Map<String,Object> copy=copies.get(i);
            Object rawRoute=copy.get("route");if(!(rawRoute instanceof Map<?,?> route))continue;
            Object rawGroups=copy.get("ui_subgraphs");
            if(rawGroups!=null&&!(rawGroups instanceof List<?>)){errors.add("saved_copies["+i+"].ui_subgraphs must be an array");continue;}
            List<?> groups=rawGroups instanceof List<?> list?list:List.of();
            try{Map<String,Object> decoded=new java.util.LinkedHashMap<>();route.forEach((key,value)->decoded.put(String.valueOf(key),value));
                validateList(groups,GraphCodec.fromMap(decoded),"saved_copies["+i+"].ui_subgraphs",errors);
            }catch(RuntimeException invalid){errors.add("saved_copies["+i+"].ui_subgraphs cannot be checked: "+invalid.getMessage());}
        }
    }

    static void validateList(Object raw,AudioGraph graph,String path,List<String> errors){
        if(!(raw instanceof List<?> list)){errors.add(path+" must be an array");return;}
        Set<String> graphNodes=graph.nodes().stream().map(GraphNode::id).collect(java.util.stream.Collectors.toSet());
        Set<String> groupIds=new HashSet<>(),claimedNodes=new HashSet<>();
        Map<String,GraphSubgraph> groupsById=new java.util.LinkedHashMap<>();
        List<GraphSubgraph> groups=new ArrayList<>();
        for(int i=0;i<list.size();i++){
            if(!(list.get(i) instanceof Map<?,?> map)){errors.add(path+"["+i+"] must be an object");continue;}
            try{
                GraphSubgraph group=GraphSubgraph.fromMap(map);
                groups.add(group);
                if(!groupIds.add(group.id()))errors.add(path+" contains duplicate id "+group.id());
                else groupsById.put(group.id(),group);
                for(String nodeId:group.nodeIds()){
                    if(!graphNodes.contains(nodeId))errors.add(path+"["+i+"] references a missing node "+nodeId);
                    if(!claimedNodes.add(nodeId))errors.add(path+" node belongs to more than one subgraph: "+nodeId);
                }
            }catch(RuntimeException invalid){errors.add(path+"["+i+"] is invalid: "+invalid.getMessage());}
        }
        for(GraphSubgraph group:groups)
            if(group.parentId()!=null&&!groupIds.contains(group.parentId()))
                errors.add(path+" subgraph "+group.id()+" references a missing parent "+group.parentId());
        validateSubgraphGeneratorAssociations(groups,groupsById,graph,path,errors);
        validateSubgraphParentCycles(groupsById,path,errors);
    }

    private static void validateSubgraphParentCycles(Map<String,GraphSubgraph> groups,String path,List<String> errors){
        Set<String> completed=new HashSet<>();
        for(String start:groups.keySet()){
            if(completed.contains(start))continue;
            Set<String> chain=new HashSet<>();
            String current=start;
            while(current!=null&&groups.containsKey(current)&&!completed.contains(current)){
                if(!chain.add(current)){errors.add(path+" contains a parent cycle involving "+current);break;}
                current=groups.get(current).parentId();
            }
            completed.addAll(chain);
        }
    }

    private static void validateSubgraphGeneratorAssociations(List<GraphSubgraph> groups,
                                                              Map<String,GraphSubgraph> groupsById,
                                                              AudioGraph graph,String path,List<String> errors){
        Map<String,List<String>> ownersByComposite=new java.util.LinkedHashMap<>();
        for(GraphNode node:graph.nodes()){
            if(!(node instanceof DynamicGeneratorNode generator))continue;
            for(Object rawInstance:generator.instances().values()){
                if(!(rawInstance instanceof Map<?,?> instance))continue;
                if(!(instance.get("composite_id") instanceof String compositeId)||compositeId.isBlank())continue;
                ownersByComposite.computeIfAbsent(compositeId,ignored->new ArrayList<>()).add(generator.id());
            }
        }
        for(Map.Entry<String,List<String>> entry:ownersByComposite.entrySet()){
            GraphSubgraph group=groupsById.get(entry.getKey());
            if(group==null){
                errors.add(path+" generator instance references a missing subgraph "+entry.getKey());
            }else if(entry.getValue().size()!=1||!entry.getValue().getFirst().equals(group.generatorId())){
                errors.add(path+" generator instance composite_id "+entry.getKey()+" does not match exactly one subgraph generator_id");
            }
        }
        for(GraphSubgraph group:groups){
            if(group.generatorId()==null)continue;
            GraphNode owner=graph.nodes().stream().filter(node->group.generatorId().equals(node.id())).findFirst().orElse(null);
            if(!(owner instanceof DynamicGeneratorNode)){
                errors.add(path+" subgraph "+group.id()+" references a missing generator "+group.generatorId());
                continue;
            }
            if(ownersByComposite.getOrDefault(group.id(),List.of()).size()!=1)
                errors.add(path+" subgraph "+group.id()+" must match exactly one generator instance composite_id");
        }
    }
}
