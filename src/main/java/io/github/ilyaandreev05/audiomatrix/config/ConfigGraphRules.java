package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphValidator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConfigGraphRules {
    private ConfigGraphRules() {}
    static void validateGraphLibrary(Map<String,Object> config,List<String> errors) {
        List<Map<String,Object>> groups=ConfigValidationValues.optionalList(config.get("route_groups"),"route_groups",errors);
        List<Map<String,Object>> routes=ConfigValidationValues.optionalList(config.get("route_library"),"route_library",errors);
        List<Map<String,Object>> copies=ConfigValidationValues.optionalList(config.get("saved_copies"),"saved_copies",errors);
        List<Map<String,Object>> controls=ConfigValidationValues.optionalList(config.get("quick_controls"),"quick_controls",errors);
        ConfigValidationValues.checkUniqueIds(groups,"route_groups",errors);ConfigValidationValues.checkUniqueIds(routes,"route_library",errors);ConfigValidationValues.checkUniqueIds(copies,"saved_copies",errors);ConfigValidationValues.checkUniqueIds(controls,"quick_controls",errors);
        Set<String> groupIds=ConfigValidationValues.ids(groups),routeIds=ConfigValidationValues.ids(routes),profileIds=ConfigValidationValues.ids(ConfigValidationValues.optionalList(config.get("profiles"),"profiles",errors));
        String active=String.valueOf(config.getOrDefault("active_route",""));
        String activeProfile=String.valueOf(config.getOrDefault("active_profile",""));
        if(!active.isBlank()&&!routeIds.contains(active))errors.add("active_route does not reference a saved route");
        for(int i=0;i<groups.size();i++){
            String profileId=String.valueOf(groups.get(i).getOrDefault("profile_id",""));
            if(!profileIds.contains(profileId))errors.add("route_groups["+i+"].profile_id must reference a profile");
        }
        Map<String,AudioGraph> graphs=new java.util.LinkedHashMap<>();
        for(int i=0;i<routes.size();i++){
            Map<String,Object> route=routes.get(i);if(!(route.get("name") instanceof String name)||name.isBlank())errors.add("route_library["+i+"].name must not be blank");
            String profileId=String.valueOf(route.getOrDefault("profile_id",""));
            if(!profileIds.contains(profileId))errors.add("route_library["+i+"].profile_id must reference a profile");
            Object group=route.get("group_id");if(group!=null&&!String.valueOf(group).isBlank()){
                if(!groupIds.contains(String.valueOf(group)))errors.add("route_library["+i+"].group_id must reference a route group");
                else {
                    Map<String,Object> owner=groups.stream().filter(item->String.valueOf(group).equals(item.get("id"))).findFirst().orElse(null);
                    if(owner!=null&&!profileId.equals(owner.get("profile_id")))errors.add("route_library["+i+"].group_id must reference a group in the same profile");
                }
            }
            try{AudioGraph graph=GraphCodec.fromMap(route);for(String problem:GraphValidator.validate(graph))errors.add("route_library["+i+"]: "+problem);graphs.put(graph.id(),graph);}
            catch(RuntimeException invalid){errors.add("route_library["+i+"] is invalid: "+invalid.getMessage());}
        }
        for(int i=0;i<groups.size();i++)if(!(groups.get(i).get("name") instanceof String name)||name.isBlank())errors.add("route_groups["+i+"].name must not be blank");
        if(!active.isBlank()){
            Map<String,Object> activeGraph=routes.stream().filter(route->active.equals(route.get("id"))).findFirst().orElse(null);
            if(activeGraph!=null&&!activeProfile.equals(activeGraph.get("profile_id")))errors.add("active_route must belong to active_profile");
        }
        List<Map<String,Object>> profiles=ConfigValidationValues.optionalList(config.get("profiles"),"profiles",errors);
        for(int i=0;i<profiles.size();i++){
            Object selected=profiles.get(i).get("selected_route_id");
            if(selected==null||String.valueOf(selected).isBlank())continue;
            String selectedId=String.valueOf(selected),profileId=String.valueOf(profiles.get(i).get("id"));
            Map<String,Object> selectedRoute=routes.stream().filter(route->selectedId.equals(route.get("id"))).findFirst().orElse(null);
            if(selectedRoute==null)errors.add("profiles["+i+"].selected_route_id must reference a saved route");
            else if(!profileId.equals(selectedRoute.get("profile_id")))errors.add("profiles["+i+"].selected_route_id must reference a route in the same profile");
        }
        for(int i=0;i<copies.size();i++){
            Map<String,Object> copy=copies.get(i);if(!Boolean.FALSE.equals(copy.get("active")))errors.add("saved_copies["+i+"] must remain inactive");
            if(!(copy.get("route") instanceof Map<?,?> raw)){errors.add("saved_copies["+i+"].route must contain a route snapshot");continue;}
            Map<String,Object> route=new java.util.LinkedHashMap<>();raw.forEach((key,value)->route.put(String.valueOf(key),value));
            try{AudioGraph graph=GraphCodec.fromMap(route);for(String problem:GraphValidator.validate(graph))errors.add("saved_copies["+i+"]: "+problem);}
            catch(RuntimeException invalid){errors.add("saved_copies["+i+"] is invalid: "+invalid.getMessage());}
        }
        if (config.containsKey("common_vibro_graph")) {
            try {
                AudioGraph common = GraphCommonVibro.graph(config);
                if (common == null) throw new IllegalArgumentException("common_vibro_graph must be an object");
                if (!GraphCommonVibro.COMMON_ROUTE_ID.equals(common.id()))
                    errors.add("common_vibro_graph.id must be " + GraphCommonVibro.COMMON_ROUTE_ID);
                for (String problem : GraphValidator.validate(common)) errors.add("common_vibro_graph: " + problem);
                GraphCommonVibro.requireValid(common);
                if (graphs.containsKey(common.id())) errors.add("common_vibro_graph id collides with a saved route");
                graphs.put(common.id(), common);
                routeIds = new HashSet<>(routeIds);
                routeIds.add(common.id());
            } catch (RuntimeException invalid) { errors.add("common_vibro_graph is invalid: " + invalid.getMessage()); }
        }
        for(int i=0;i<controls.size();i++){
            Map<String,Object> control=controls.get(i);String routeId=String.valueOf(control.getOrDefault("route_id",active));AudioGraph graph=graphs.get(routeId);
            if(graph==null){errors.add("quick_controls["+i+"] references a missing route");continue;}
            String nodeId=String.valueOf(control.getOrDefault("node_id","")),parameter=String.valueOf(control.getOrDefault("parameter_id",""));
            GraphNode node=graph.nodes().stream().filter(item->item.id().equals(nodeId)).findFirst().orElse(null);
            if(node==null||!node.parameters().containsKey(parameter))errors.add("quick_controls["+i+"] references an unavailable node parameter");
        }
        ConfigPinnedNodeRules.validate(config, graphs, controls, errors);
        validatePhysicalAssignments(graphs, active, errors);
        validateGraphLayouts(config,graphs,routeIds,errors);
        ConfigGraphMatrixViewRules.validate(config, graphs, routeIds, errors);
        validateGraphUiScales(config,routeIds,errors);
        validateGraphSubgraphs(config,graphs,routeIds,errors);
    }
    static void validatePhysicalAssignments(Map<String,AudioGraph> graphs,String activeRoute,List<String> errors){
        ConfigGraphPhysicalRules.validate(graphs,activeRoute,errors);
    }

    static void validateGraphSubgraphs(Map<String,Object> config,Map<String,AudioGraph> graphs,Set<String> routeIds,List<String> errors){
        ConfigGraphSubgraphRules.validate(config,graphs,routeIds,errors);
    }
    static void validateSubgraphList(Object raw,AudioGraph graph,String path,List<String> errors){
        ConfigGraphSubgraphRules.validateList(raw,graph,path,errors);
    }
    static void validateGraphLayouts(Map<String,Object> config,Map<String,AudioGraph> graphs,Set<String> routeIds,List<String> errors){
        ConfigGraphLayoutRules.validateLayouts(config,graphs,routeIds,errors);
    }
    static void validateGraphUiScales(Map<String,Object> config,Set<String> routeIds,List<String> errors){
        ConfigGraphLayoutRules.validateUiScales(config,routeIds,errors);
    }}
