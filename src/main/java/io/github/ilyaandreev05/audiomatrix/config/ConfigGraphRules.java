package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphSubgraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphValidator;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import java.nio.file.Path;
import java.util.ArrayList;
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
    /**
     * Two slots in one graph must not share a physical output, and the active route must not
     * double the always-applied Vibro output. Inactive routes may pre-bind shared hardware so
     * their outputs stay visible in the controls without becoming part of the applied graph.
     */
    static void validatePhysicalAssignments(Map<String,AudioGraph> graphs,String activeRoute,List<String> errors){
        String commonId=GraphCommonVibro.COMMON_ROUTE_ID, vibroDevice="";
        AudioGraph common=graphs.get(commonId);
        if(common!=null)for(GraphNode node:common.nodes())
            if(node instanceof DeviceSlotNode slot&&GraphCommonVibro.VIBRO_OUTPUT_ID.equals(slot.id()))
                vibroDevice=deviceReference(slot.deviceId());
        for(Map.Entry<String,AudioGraph> entry:graphs.entrySet()){
            String routeId=entry.getKey();Set<String> assigned=new HashSet<>();
            boolean applied=commonId.equals(routeId)||routeId.equals(activeRoute);
            for(GraphNode node:entry.getValue().nodes()){
                String device=deviceId(node);
                if(device.isBlank()||"system_default".equals(device))continue;
                if(!assigned.add(device))errors.add("graph "+routeId+" node "+node.id()+" duplicates physical output "+device);
                if(applied&&!commonId.equals(routeId)&&!vibroDevice.isBlank()&&device.equals(vibroDevice))
                    errors.add("graph "+routeId+" node "+node.id()+" reuses the Vibro physical output "+device);
            }
        }
    }

    private static String deviceId(GraphNode node){
        if(node instanceof DeviceSlotNode slot)return deviceReference(slot.deviceId());
        if(node instanceof OutputNode output)return deviceReference(output.deviceId());
        return "";
    }

    private static String deviceReference(String deviceId){
        return deviceId!=null&&deviceId.startsWith("device:")?deviceId.substring("device:".length()):deviceId;
    }
    static void validateGraphSubgraphs(Map<String,Object> config,Map<String,AudioGraph> graphs,Set<String> routeIds,List<String> errors){
        Object raw=config.get("graph_ui_subgraphs");if(raw==null)return;
        if(!(raw instanceof Map<?,?> byRoute)){errors.add("graph_ui_subgraphs must be an object");return;}
        for(Map.Entry<?,?> entry:byRoute.entrySet()){
            String routeId=String.valueOf(entry.getKey());AudioGraph graph=graphs.get(routeId);
            if(!routeIds.contains(routeId)||graph==null){errors.add("graph_ui_subgraphs references a missing route: "+routeId);continue;}
            validateSubgraphList(entry.getValue(),graph,"graph_ui_subgraphs["+routeId+"]",errors);
        }
        List<Map<String,Object>> copies=ConfigValidationValues.optionalList(config.get("saved_copies"),"saved_copies",new ArrayList<>());
        for(int i=0;i<copies.size();i++){
            Map<String,Object> copy=copies.get(i);if(!(copy.get("ui_subgraphs") instanceof List<?> groups))continue;
            Object rawRoute=copy.get("route");if(!(rawRoute instanceof Map<?,?> route))continue;
            try{Map<String,Object> decoded=new java.util.LinkedHashMap<>();route.forEach((key,value)->decoded.put(String.valueOf(key),value));
                validateSubgraphList(groups,GraphCodec.fromMap(decoded),"saved_copies["+i+"].ui_subgraphs",errors);
            }catch(RuntimeException invalid){errors.add("saved_copies["+i+"].ui_subgraphs cannot be checked: "+invalid.getMessage());}
        }
    }
    static void validateSubgraphList(Object raw,AudioGraph graph,String path,List<String> errors){
        if(!(raw instanceof List<?> list)){errors.add(path+" must be an array");return;}
        Set<String> graphNodes=graph.nodes().stream().map(GraphNode::id).collect(java.util.stream.Collectors.toSet());
        Set<String> groupIds=new HashSet<>(),claimedNodes=new HashSet<>();
        for(int i=0;i<list.size();i++){
            if(!(list.get(i) instanceof Map<?,?> map)){errors.add(path+"["+i+"] must be an object");continue;}
            try{
                GraphSubgraph group=GraphSubgraph.fromMap(map);
                if(!groupIds.add(group.id()))errors.add(path+" contains duplicate id "+group.id());
                for(String nodeId:group.nodeIds()){
                    if(!graphNodes.contains(nodeId))errors.add(path+"["+i+"] references a missing node "+nodeId);
                    if(!claimedNodes.add(nodeId))errors.add(path+" node belongs to more than one subgraph: "+nodeId);
                }
            }catch(RuntimeException invalid){errors.add(path+"["+i+"] is invalid: "+invalid.getMessage());}
        }
    }
    static void validateGraphLayouts(Map<String,Object> config,Map<String,AudioGraph> graphs,Set<String> routeIds,List<String> errors){
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
    static void validateGraphUiScales(Map<String,Object> config,Set<String> routeIds,List<String> errors){
        Object value=config.get("graph_ui_scale");if(value==null)return;
        if(!(value instanceof Map<?,?> scales)){errors.add("graph_ui_scale must be an object");return;}
        for(Map.Entry<?,?> entry:scales.entrySet()){
            String routeId=String.valueOf(entry.getKey());Object scale=entry.getValue();
            if(!routeIds.contains(routeId)){errors.add("graph_ui_scale references a missing route: "+routeId);continue;}
            if(!(scale instanceof Number number)||!Double.isFinite(number.doubleValue())||number.doubleValue()<0.5||number.doubleValue()>2.0)
                errors.add("graph_ui_scale["+routeId+"] must be between 0.5 and 2.0");
        }
    }}
