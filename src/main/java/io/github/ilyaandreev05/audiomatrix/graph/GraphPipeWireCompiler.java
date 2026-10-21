package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compiles the safe, supported graph subset to Audio Matrix's existing PipeWire route plan. */
public final class GraphPipeWireCompiler {
    private GraphPipeWireCompiler() {}

    public static Map<String,Object> compile(AudioGraph graph,Map<String,Object> baseConfig){
        return compileGraph(graph,baseConfig,null);
    }

    public static Map<String,Object> compile(DynamicInstanceRegistry.ReconcileResult reconciled,
                                            Map<String,Object> baseConfig){
        if(reconciled==null)throw new IllegalArgumentException("A reconcile result is required");
        return compileGraph(reconciled.effectiveGraph(),baseConfig,reconciled);
    }

    private static Map<String,Object> compileGraph(AudioGraph graph,Map<String,Object> baseConfig,
                                                    DynamicInstanceRegistry.ReconcileResult reconciled){
        graph=GraphCommonVibro.effectiveGraph(graph,baseConfig);
        List<String> problems=GraphValidator.validate(graph,reconciled==null?Map.<DynamicInstanceRegistry.InstanceAddress,DynamicInstanceRegistry.RuntimeInstance>of():reconciled.instances());if(!problems.isEmpty())throw new IllegalArgumentException(String.join("; ",problems));
        if(reconciled==null&&!GraphPipeWireCaptureSupport.generatedSourceIds(graph).isEmpty())
            throw new IllegalArgumentException("Generated graph sources require a reconcile result");
        if(reconciled==null&&graph.nodes().stream().filter(SourceNode.class::isInstance).map(SourceNode.class::cast)
                .anyMatch(source->source.selector().startsWith("capture:")))
            throw new IllegalArgumentException("Captured graph sources require a reconcile result");
        GraphPipeWireCaptureSupport.CaptureSources captures=reconciled==null
                ?new GraphPipeWireCaptureSupport.CaptureSources(Map.of(),Set.of(),Set.of(),List.of())
                :GraphPipeWireCaptureSupport.captureSources(reconciled,graph);
        // Generators are control-plane nodes; only reconciled SourceNode children with live bindings enter PCM compilation.
        List<SourceNode> sources=graph.nodes().stream().filter(SourceNode.class::isInstance).map(SourceNode.class::cast)
                .filter(source->!captures.nonPcmSourceIds().contains(source.id()))
                .filter(source->!source.selector().startsWith("browser-tab:"))
                .filter(source->!source.selector().startsWith("capture:")||captures.bySourceId().containsKey(source.id())).toList();
        boolean hasDynamicOutputGenerator=graph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).anyMatch(generator->"outputs".equals(generator.registry()));
        boolean hasOutputNode=graph.nodes().stream().anyMatch(node->node instanceof OutputNode||node instanceof DeviceSlotNode);
        // A generator is control-plane: it never provides PCM. Its instances do, once the registry materializes them.
        boolean hasControlPlane=graph.nodes().stream().anyMatch(DynamicGeneratorNode.class::isInstance);
        boolean generatorProvidesSource=reconciled!=null?!reconciled.instances().isEmpty()
                :graph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                        .map(DynamicGeneratorNode.class::cast).anyMatch(generator->!generator.instances().isEmpty());
        boolean offlineControlGraph=sources.isEmpty()&&!hasOutputNode&&(hasControlPlane||generatorProvidesSource);
        if(!offlineControlGraph&&(sources.isEmpty()||!hasOutputNode&&!hasDynamicOutputGenerator))
            throw new IllegalArgumentException("The active route needs at least one source and one output or device slot");
        Map<String,GraphNode> nodes=new LinkedHashMap<>();graph.nodes().forEach(node->nodes.put(node.id(),node));
        rejectSharedVibroOutput(graph);
        for(GraphNode node:graph.nodes())if(node instanceof RouterNode&&node.type().equals("router"))
            throw new IllegalArgumentException("Legacy router node '"+node.name()+"' has no typed switching behavior; replace it with a Switch, Selector or Matrix node");
        List<Map<String,Object>> buses=new ArrayList<>(),routes=new ArrayList<>(),
                applications=GraphApplicationRuleMerger.copyConfiguredRules(baseConfig);
        List<String> activeBusIds=new ArrayList<>(),activeRouteIds=new ArrayList<>(),eventInputBuses=new ArrayList<>();
        Map<String,Object> routeChains=new LinkedHashMap<>();Map<String,Map<String,Object>> outputLimiters=new LinkedHashMap<>();
        Set<String> sourceSelectors=new HashSet<>(),sourceOutputs=new HashSet<>();String defaultBus="";
        for(SourceNode source:sources){
            String layout=layout(source.outputs().getFirst().channels());activeBusIds.add(source.id());
            String selector=source.selector();
            String role=DynamicRegistrySystemInput.SELECTOR.equals(selector)
                    ?DynamicRegistrySystemInput.BUS_ROLE:"general";
            buses.add(map("id",source.id(),"name",source.name(),"role",role,"channels",layout,"volume",1.0,"mute",false));
            if(!selector.equals("application:unassigned")&&!selector.equals("application:*")&&!sourceSelectors.add(selector))
                throw new IllegalArgumentException("More than one graph source matches the same application or process: "+selector);
            if(selector.equals("application:unassigned")||selector.equals("application:*")){
                if(!defaultBus.isBlank())throw new IllegalArgumentException("Only one catch-all application source is allowed");
                defaultBus=source.id();
            }else if(selector.startsWith("capture:")){
                // Captured tabs are bound by exact runtime node name, never a broad app rule.
                continue;
            }else if(EventSoundStudioSource.SELECTOR.equals(selector)){
                eventInputBuses.add(source.id());
                Map<String,Object> rule=savedApplicationRule(baseConfig,"app_id",EventSoundStudioSource.APPLICATION_ID);
                GraphApplicationRuleMerger.attach(applications,rule,"graph-rule-"+source.id(),source.id(),
                        map("app_id",EventSoundStudioSource.APPLICATION_ID));
            }else if(selector.startsWith("application:")&&!selector.substring("application:".length()).isBlank()){
                String appId=selector.substring("application:".length());
                Map<String,Object> rule=savedApplicationRule(baseConfig,"app_id",appId);
                GraphApplicationRuleMerger.attach(applications,rule,"graph-rule-"+source.id(),source.id(),map("app_id",appId));
            }else if(selector.startsWith("process:")&&!selector.substring("process:".length()).isBlank()){
                String process=selector.substring("process:".length());
                Map<String,Object> rule=savedApplicationRule(baseConfig,"process",process);
                GraphApplicationRuleMerger.attach(applications,rule,"graph-rule-"+source.id(),source.id(),map("process",process));
            }else if(DynamicRegistrySystemInput.SELECTOR.equals(selector)){
                // The session system input is routed like an application, but it is not one:
                // it owns no application rule and never appears as an application stream.
                continue;
            }else throw new IllegalArgumentException("Unsupported source selector on '"+source.name()+"': "+selector+". Use application:<id>, process:<name>, or application:unassigned.");
        }
        GraphPipeWireTraversal traversal=new GraphPipeWireTraversal(graph,nodes,routes,activeRouteIds,
                routeChains,outputLimiters,sourceOutputs,captures.gainNodeIds());
        for(SourceNode source:sources)traversal.walkFrom(source.id());
        if(routes.isEmpty()&&graph.nodes().stream().noneMatch(DeviceSlotNode.class::isInstance)
                &&!offlineControlGraph&&!hasDynamicOutputGenerator)
            throw new IllegalArgumentException("The graph has no enabled path from a source to an output");
        GraphApplicationRuleMerger.preserveReferencedBuses(baseConfig,applications,buses);
        Map<String,Object> result=deep(baseConfig);
        result.remove("graph_capture_sources");
        result.put("buses",buses);result.put("routes",routes);result.put("applications",applications);result.put("event_input_buses",eventInputBuses);result.put("graph_default_bus",defaultBus);
        if(reconciled!=null&&!captures.metadata().isEmpty())result.put("graph_capture_sources",captures.metadata());
        Map<String,Object> profile=activeProfile(result);
        if(profile!=null){profile.put("mode","multi-output");profile.put("active_buses",activeBusIds);profile.put("active_routes",activeRouteIds);}
        Map<String,Object> dsp=object(result.get("dsp"));result.put("dsp",dsp);Map<String,Object> chains=object(dsp.get("route_chains"));dsp.put("route_chains",chains);chains.putAll(routeChains);
        Map<String,Object> encodedOutputLimiters=new LinkedHashMap<>();
        outputLimiters.forEach((deviceId,limiter)->{if(limiter!=null)encodedOutputLimiters.put(deviceId,new LinkedHashMap<>(limiter));});
        dsp.put("output_limiters",encodedOutputLimiters);
        return result;
    }

    static String deviceReference(String deviceId){
        return deviceId!=null&&deviceId.startsWith("device:")?deviceId.substring("device:".length()):deviceId;
    }

    private static void rejectSharedVibroOutput(AudioGraph graph){
        String vibro="";
        for(GraphNode node:graph.nodes())
            if(node instanceof DeviceSlotNode slot&&GraphCommonVibro.VIBRO_OUTPUT_ID.equals(slot.id()))
                vibro=deviceReference(slot.deviceId());
        if(vibro.isBlank())return;
        for(GraphNode node:graph.nodes()){
            if(GraphCommonVibro.VIBRO_OUTPUT_ID.equals(node.id()))continue;
            String device=deviceReference(node instanceof DeviceSlotNode slot?slot.deviceId():node instanceof OutputNode output?output.deviceId():"");
            if(vibro.equals(device))throw new IllegalArgumentException("Physical output '"+device
                    +"' is assigned to both the Vibro slot and '"+node.name()+"'; choose separate outputs");
        }
    }

    private static Map<String,Object> savedApplicationRule(Map<String,Object> config,String key,String value){for(Map<String,Object> rule:maps(config.get("applications"))){Map<String,Object> match=object(rule.get("match"));if(value.equals(match.get(key)))return new LinkedHashMap<>(rule);}return map("volume",1.0,"mute",false);}
    static String layout(List<String> channels){if(channels.equals(List.of("FL","FR")))return "stereo";if(channels.equals(List.of("MONO")))return "mono";throw new IllegalArgumentException("PipeWire graph application currently supports stereo FL/FR or mono MONO channels; got "+channels);}
    private static Map<String,Object> activeProfile(Map<String,Object> config){List<Map<String,Object>> profiles=maps(config.get("profiles"));if(profiles.isEmpty())return null;String id=String.valueOf(config.get("active_profile"));for(Map<String,Object> profile:profiles){if(id.equals(profile.get("id")))return profile;}return profiles.getFirst();}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return value instanceof Map<?,?> map?(Map<String,Object>)map:new LinkedHashMap<>();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value){return value instanceof List<?> list?(List<Map<String,Object>>)list:List.of();}
    static Map<String,Object> map(Object...pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    @SuppressWarnings("unchecked") private static Map<String,Object> deep(Map<String,Object> value){Map<String,Object> result=new LinkedHashMap<>();value.forEach((key,item)->result.put(key,copy(item)));return result;}
    private static Object copy(Object value){if(value instanceof Map<?,?> map){Map<String,Object> result=new LinkedHashMap<>();map.forEach((key,item)->result.put(String.valueOf(key),copy(item)));return result;}if(value instanceof List<?> list){List<Object> result=new ArrayList<>(list.size());list.forEach(item->result.add(copy(item)));return result;}return value;}
}
