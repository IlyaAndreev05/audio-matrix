package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GnomeQuickSettingsPins {
    private GnomeQuickSettingsPins() {}

    static List<Map<String, Object>> build(Map<String, Object> config, PipeWireBackend.Snapshot snapshot,
                                           List<Map<String, Object>> quickControls,
                                           List<Map<String, Object>> applications,
                                           List<Map<String, Object>> outputs,
                                           AudioGraph effectiveGraph,
                                           Map<DynamicInstanceRegistry.InstanceAddress,
                                                   DynamicInstanceRegistry.RuntimeInstance> instances) {
        String routeId = String.valueOf(config.getOrDefault("active_route", ""));
        List<Map<String,Object>> savedPins=QuickSettingsConfig.pins(config).stream()
                .filter(pin->!Boolean.FALSE.equals(pin.get("pinned"))).toList();
        GnomeQuickSettingsNodeState.Inputs input=new GnomeQuickSettingsNodeState.Inputs(config,snapshot,quickControls,applications,
                instances==null?Map.of():instances);
        AudioGraph commonGraph = null;
        try { commonGraph = GraphCommonVibro.graph(config); }
        catch (RuntimeException invalid) { /* Invalid common state is not published. */ }
        Map<String, AudioGraph> graphs = new LinkedHashMap<>();
        Map<String, List<String>> claims = new LinkedHashMap<>();
        Set<String> generatedNodes = generatedNodeIds(instances);
        if (commonGraph != null) {
            graphs.put(GraphCommonVibro.COMMON_ROUTE_ID, commonGraph);
            claims.put(GraphCommonVibro.COMMON_ROUTE_ID, GnomeQuickSettingsPinDetails.claimSelectors(commonGraph, generatedNodes, savedPins));
        }
        AudioGraph activeGraph = effectiveGraph != null && routeId.equals(effectiveGraph.id())
                ? effectiveGraph : routeGraph(config, routeId);
        if (activeGraph != null) {
            graphs.put(routeId, activeGraph);
            claims.put(routeId, GnomeQuickSettingsPinDetails.claimSelectors(activeGraph, generatedNodes, savedPins));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> deferredSharedGain = new ArrayList<>();
        for (Map<String, Object> pin : savedPins) {
            String pinRouteId = String.valueOf(pin.getOrDefault("route_id", ""));
            boolean common = GraphCommonVibro.COMMON_ROUTE_ID.equals(pinRouteId);
            boolean active = routeId.equals(pinRouteId);
            if (!common && !active && !graphs.containsKey(pinRouteId)) {
                AudioGraph inactive = routeGraph(config, pinRouteId);
                if (inactive != null) {
                    graphs.put(pinRouteId, inactive);
                    claims.put(pinRouteId, GnomeQuickSettingsPinDetails.claimSelectors(inactive, generatedNodes, savedPins));
                }
            }
            AudioGraph pinGraph = graphs.get(pinRouteId);
            if (pinGraph == null) continue;
            List<String> claimSelectors = claims.getOrDefault(pinRouteId, List.of());
            String nodeId = String.valueOf(pin.getOrDefault("node_id", ""));
            String instanceKey = String.valueOf(pin.getOrDefault("instance_key", ""));
            if (!instanceKey.isBlank()) {
                Map<String, Object> generated = GnomeQuickSettingsGeneratedPins.savedPin(pin, pinRouteId,
                        pinGraph, snapshot, config, applications, outputs, instances);
                if (generated != null) result.add(generated);
                continue;
            }
            GraphNode node = pinGraph.nodes().stream().filter(item -> nodeId.equals(item.id())).findFirst().orElse(null);
            if(node==null){
                List<GraphSubgraph> groups=GraphSubgraphLibrary.forRoute(config,pinRouteId);
                GraphSubgraph composite=groups.stream().filter(group->nodeId.equals(group.id())).findFirst().orElse(null);
                if(composite!=null){
                    Map<String,Object> state=compositeState(pin,composite,pinGraph,input,claimSelectors,groups,new LinkedHashSet<>());
                    if(visible(state))result.add(state);
                }
                continue;
            }
            if (!common && !active && !(node instanceof OutputNode) && !(node instanceof DeviceSlotNode)) continue;
            if(node instanceof GainNode gain&&GraphCommonVibro.COMMON_ROUTE_ID.equals(pinRouteId)
                    &&GraphCommonVibro.VIBRO_GAIN_ID.equals(gain.id())){
                Map<String,Object> shared=GnomeQuickSettingsHapticGain.targetState(config);
                if(shared!=null)deferredSharedGain.add(GnomeQuickSettingsHapticGain.savedPinState(pin,shared,false));
                continue;
            }
            Map<String,Object> state=GnomeQuickSettingsNodeState.describe(pin,node,pinGraph,input,claimSelectors);
            if(visible(state))result.add(state);
        }
        GnomeQuickSettingsGeneratedPins.appendAutoPins(result, config, routeId, activeGraph, snapshot,
                applications, outputs, instances);
        Set<String> individualApps = result.stream().filter(pin -> pin.containsKey("instanceKey"))
                .flatMap(pin -> maps(pin.get("applications")).stream()).map(app -> String.valueOf(app.get("id")))
                .collect(java.util.stream.Collectors.toSet());
        for (Map<String, Object> pin : result) {
            if (!pin.containsKey("instanceKey") && pin.containsKey("applications"))
                pin.put("applications", maps(pin.get("applications")).stream()
                        .filter(app -> !individualApps.contains(String.valueOf(app.get("id")))).toList());
        }
        boolean hapticOutput = GnomeQuickSettingsHapticGain.hasAssignedOutput(instances);
        if (result.stream().noneMatch(pin -> pin.containsKey("sharedGain")))
            for (Map<String, Object> pin : deferredSharedGain)
                if (Boolean.TRUE.equals(pin.get("showWhenOffline")) || !hapticOutput) result.add(pin);
        result.sort(java.util.Comparator.comparingInt(item -> number(item.get("order"), 0)));
        return List.copyOf(result);
    }

    private static AudioGraph routeGraph(Map<String, Object> config, String routeId) {
        if (routeId.isBlank()) return null;
        Map<String, Object> route = maps(config.get("route_library")).stream()
                .filter(item -> routeId.equals(item.get("id"))).findFirst().orElse(null);
        if (route == null) return null;
        try { return GraphCodec.fromMap(route); }
        catch (RuntimeException invalid) { return null; }
    }


    /** The nodes the registry materialized for a generator instance: they never claim an application. */
    private static Set<String> generatedNodeIds(Map<DynamicInstanceRegistry.InstanceAddress,
                                                 DynamicInstanceRegistry.RuntimeInstance> instances) {
        Set<String> result = new LinkedHashSet<>();
        if (instances == null) return result;
        for (DynamicInstanceRegistry.RuntimeInstance instance : instances.values())
            result.addAll(instance.nodeIds().values());
        return result;
    }

    private static boolean visible(Map<String,Object> state){
        return Boolean.TRUE.equals(state.get("available"))||Boolean.TRUE.equals(state.get("showWhenOffline"));
    }

    private static Map<String,Object> compositeState(Map<String,Object> pin,GraphSubgraph composite,AudioGraph graph,
            GnomeQuickSettingsNodeState.Inputs input,List<String> claims,List<GraphSubgraph> groups,Set<String> ancestors){
        Map<String,Object> state=GnomeQuickSettingsNodeState.presentation(pin,composite.id(),composite.name(),"composite");
        List<Map<String,Object>> children=new ArrayList<>();
        if(!ancestors.add(composite.id())){state.put("children",children);state.put("available",false);return state;}
        int order=0;
        for(String nodeId:composite.nodeIds()){
            GraphNode node=graph.nodes().stream().filter(candidate->nodeId.equals(candidate.id())).findFirst().orElse(null);
            if(node==null)continue;
            Map<String,Object> childPin=childPin(pin,nodeId,order++);
            Map<String,Object> individual=QuickSettingsConfig.findPin(input.config(),graph.id(),nodeId,"","");
            if(individual!=null){if(Boolean.FALSE.equals(individual.get("pinned")))continue;childPin.putAll(individual);}
            Map<String,Object> child=GnomeQuickSettingsNodeState.describe(childPin,node,graph,input,claims);
            if(visible(child))children.add(child);
        }
        for(GraphSubgraph nested:groups){
            if(!composite.id().equals(nested.parentId()))continue;
            Map<String,Object> child=compositeState(childPin(pin,nested.id(),order++),nested,graph,input,claims,groups,ancestors);
            if(visible(child))children.add(child);
        }
        ancestors.remove(composite.id());
        state.put("children",List.copyOf(children));
        state.put("available",input.snapshot().connected()&&children.stream().anyMatch(child->Boolean.TRUE.equals(child.get("available"))));
        return state;
    }

    private static Map<String,Object> childPin(Map<String,Object> parent,String nodeId,int order){
        Map<String,Object> pin=new LinkedHashMap<>();
        pin.put("id",parent.get("id")+":"+nodeId);pin.put("route_id",parent.get("route_id"));pin.put("node_id",nodeId);
        pin.put("group_id",parent.get("group_id"));pin.put("order",order);pin.put("view","DEFAULT");
        pin.put("show_when_offline",parent.getOrDefault("show_when_offline",false));
        return pin;
    }



    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }


    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
