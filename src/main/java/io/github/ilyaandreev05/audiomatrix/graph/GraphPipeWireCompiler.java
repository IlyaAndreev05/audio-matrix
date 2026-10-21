package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
        List<String> problems=GraphValidator.validate(graph);if(!problems.isEmpty())throw new IllegalArgumentException(String.join("; ",problems));
        if(reconciled==null&&!generatedSourceIds(graph).isEmpty())
            throw new IllegalArgumentException("Generated graph sources require a reconcile result");
        if(reconciled==null&&graph.nodes().stream().filter(SourceNode.class::isInstance).map(SourceNode.class::cast)
                .anyMatch(source->source.selector().startsWith("capture:")))
            throw new IllegalArgumentException("Captured graph sources require a reconcile result");
        CaptureSources captures=reconciled==null?new CaptureSources(Map.of(),Set.of(),Set.of(),List.of())
                :captureSources(reconciled,graph);
        // Generators are control-plane nodes; only reconciled SourceNode children with live bindings enter PCM compilation.
        List<SourceNode> sources=graph.nodes().stream().filter(SourceNode.class::isInstance).map(SourceNode.class::cast)
                .filter(source->!captures.nonPcmSourceIds().contains(source.id()))
                .filter(source->!source.selector().startsWith("browser-tab:"))
                .filter(source->!source.selector().startsWith("capture:")||captures.bySourceId().containsKey(source.id())).toList();
        boolean hasDynamicGenerator=graph.nodes().stream().anyMatch(DynamicGeneratorNode.class::isInstance);
        boolean hasDynamicOutputGenerator=graph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).anyMatch(generator->"outputs".equals(generator.registry()));
        boolean offlineControlGraph=sources.isEmpty()&&hasDynamicGenerator;
        boolean hasOutputNode=graph.nodes().stream().anyMatch(node->node instanceof OutputNode||node instanceof DeviceSlotNode);
        if(!offlineControlGraph&&(sources.isEmpty()||!hasOutputNode&&!hasDynamicOutputGenerator))
            throw new IllegalArgumentException("The active route needs at least one source and one output or device slot");
        Map<String,GraphNode> nodes=new LinkedHashMap<>();graph.nodes().forEach(node->nodes.put(node.id(),node));
        rejectSharedVibroOutput(graph);
        for(GraphNode node:graph.nodes())if(node instanceof RouterNode&&node.type().equals("router"))
            throw new IllegalArgumentException("Legacy router node '"+node.name()+"' has no typed switching behavior; replace it with a Switch, Selector or Matrix node");
        List<Map<String,Object>> buses=new ArrayList<>(),routes=new ArrayList<>(),applications=new ArrayList<>();
        List<String> activeBusIds=new ArrayList<>(),activeRouteIds=new ArrayList<>(),eventInputBuses=new ArrayList<>();
        Map<String,Object> routeChains=new LinkedHashMap<>();Map<String,Map<String,Object>> outputLimiters=new LinkedHashMap<>();
        Set<String> sourceSelectors=new HashSet<>(),sourceOutputs=new HashSet<>();String defaultBus="";
        for(SourceNode source:sources){
            String layout=layout(source.outputs().getFirst().channels());activeBusIds.add(source.id());
            buses.add(map("id",source.id(),"name",source.name(),"role","general","channels",layout,"volume",1.0,"mute",false));
            String selector=source.selector();
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
                rule.put("id","graph-rule-"+source.id());rule.put("bus",source.id());rule.put("match",map("app_id",EventSoundStudioSource.APPLICATION_ID));applications.add(rule);
            }else if(selector.startsWith("application:")&&!selector.substring("application:".length()).isBlank()){
                String appId=selector.substring("application:".length());Map<String,Object> rule=savedApplicationRule(baseConfig,"app_id",appId);rule.put("id","graph-rule-"+source.id());rule.put("bus",source.id());rule.put("match",map("app_id",appId));applications.add(rule);
            }else if(selector.startsWith("process:")&&!selector.substring("process:".length()).isBlank()){
                String process=selector.substring("process:".length());Map<String,Object> rule=savedApplicationRule(baseConfig,"process",process);rule.put("id","graph-rule-"+source.id());rule.put("bus",source.id());rule.put("match",map("process",process));applications.add(rule);
            }else throw new IllegalArgumentException("Unsupported source selector on '"+source.name()+"': "+selector+". Use application:<id>, process:<name>, or application:unassigned.");
        }
        for(SourceNode source:sources){
            List<GraphConnection> first=outgoing(graph,source.id());
            for(GraphConnection edge:first){PathState state=new PathState(source.id(),edge.gain(),false,true,new ArrayList<>(),new ArrayList<>());state.edgeIds.add(edge.id());walk(edge.toNode(),edge.toPort(),state,nodes,graph,new HashSet<>(),routes,activeRouteIds,routeChains,outputLimiters,sourceOutputs,captures.gainNodeIds());}
        }
        if(routes.isEmpty()&&graph.nodes().stream().noneMatch(DeviceSlotNode.class::isInstance)
                &&!offlineControlGraph&&!hasDynamicOutputGenerator)
            throw new IllegalArgumentException("The graph has no enabled path from a source to an output");
        Map<String,Object> result=deep(baseConfig);
        result.remove("graph_capture_sources");
        result.put("buses",buses);result.put("routes",routes);result.put("applications",applications);result.put("event_input_buses",eventInputBuses);result.put("graph_default_bus",defaultBus);
        if(reconciled!=null)result.put("graph_capture_sources",captures.metadata());
        Map<String,Object> profile=activeProfile(result);profile.put("mode","multi-output");profile.put("active_buses",activeBusIds);profile.put("active_routes",activeRouteIds);
        Map<String,Object> dsp=object(result.get("dsp"));result.put("dsp",dsp);Map<String,Object> chains=object(dsp.get("route_chains"));dsp.put("route_chains",chains);chains.putAll(routeChains);
        Map<String,Object> encodedOutputLimiters=new LinkedHashMap<>();
        outputLimiters.forEach((deviceId,limiter)->{if(limiter!=null)encodedOutputLimiters.put(deviceId,new LinkedHashMap<>(limiter));});
        dsp.put("output_limiters",encodedOutputLimiters);
        return result;
    }

    private static CaptureSources captureSources(DynamicInstanceRegistry.ReconcileResult reconciled,AudioGraph graph){
        Map<String,GraphNode> nodes=new LinkedHashMap<>();graph.nodes().forEach(node->nodes.put(node.id(),node));
        Set<String> generatedSourceIds=generatedSourceIds(graph),pcmSourceIds=new HashSet<>(),gainNodeIds=new HashSet<>();
        Map<String,Map<String,Object>> bySourceId=new LinkedHashMap<>();List<Map<String,Object>> metadata=new ArrayList<>();
        List<DynamicInstanceRegistry.RuntimeInstance> runtimeInstances =
                new ArrayList<>(reconciled.instances().values());
        runtimeInstances.sort(Comparator.comparing(DynamicInstanceRegistry.RuntimeInstance::generatorId)
                .thenComparing(DynamicInstanceRegistry.RuntimeInstance::instanceKey));
        for(DynamicInstanceRegistry.RuntimeInstance runtime:runtimeInstances){
            Map<String,Object> binding=runtime.runtimeBinding();
            boolean browserParent=runtime.instanceKey().startsWith("browser-parent:")
                    ||Boolean.TRUE.equals(binding.get("browserParent"));
            if(runtime.available()&&runtime.confidence()==DynamicInstanceRegistry.Confidence.HIGH
                    &&Boolean.TRUE.equals(binding.get("hasPcmBinding"))&&!browserParent)
                for(String childId:runtime.nodeIds().values())
                    if(nodes.get(childId) instanceof SourceNode)pcmSourceIds.add(childId);
            if(!runtime.available()||runtime.confidence()!=DynamicInstanceRegistry.Confidence.HIGH||browserParent)continue;
            String captureKey=bindingText(binding,"captureStreamKey");
            String nodeName=bindingText(binding,"nodeName");
            String applicationRuleId=bindingText(binding,"applicationRuleId");
            if(captureKey.isBlank()||!nodeName.equals("audio-matrix-chrome-"+captureKey)||applicationRuleId.isBlank())continue;
            SourceNode source=null;GainNode gain=null;
            for(String childId:runtime.nodeIds().values()){
                GraphNode child=nodes.get(childId);
                if(child instanceof SourceNode candidate&&candidate.selector().equals("capture:"+captureKey))source=candidate;
                if(child instanceof GainNode candidate)gain=candidate;
            }
            if(source==null||gain==null)continue;
            Map<String,Object> item=map("source_id",source.id(),"instance_key",runtime.instanceKey(),
                    "capture_stream_key",captureKey,"node_name",nodeName,"application_rule_id",applicationRuleId,
                    "parent_application_id",bindingText(binding,"parentApplicationId"),
                    "parent_process_name",bindingText(binding,"parentProcessName"),
                    "tab_gain",gain.gain(),"tab_muted",gain.muted());
            bySourceId.put(source.id(),item);gainNodeIds.add(gain.id());pcmSourceIds.add(source.id());metadata.add(item);
        }
        Set<String> nonPcmSourceIds=new HashSet<>(generatedSourceIds);nonPcmSourceIds.removeAll(pcmSourceIds);
        return new CaptureSources(Map.copyOf(bySourceId),Set.copyOf(gainNodeIds),
                Set.copyOf(nonPcmSourceIds),List.copyOf(metadata));
    }

    private static Set<String> generatedSourceIds(AudioGraph graph){
        Map<String,GraphNode> nodes=new LinkedHashMap<>();graph.nodes().forEach(node->nodes.put(node.id(),node));
        Set<String> generated=new HashSet<>();
        for(GraphNode node:graph.nodes())if(node instanceof DynamicGeneratorNode generator){
            for(Object rawInstance:generator.instances().values()){
                if(!(rawInstance instanceof Map<?,?> instance)||!(instance.get("node_ids") instanceof Map<?,?> ids))continue;
                for(Object rawId:ids.values())if(rawId instanceof String id&&nodes.get(id) instanceof SourceNode)generated.add(id);
            }
        }
        return generated;
    }

    private static String bindingText(Map<String,Object> binding,String key){
        Object value=binding.get(key);return value instanceof String text?text:"";
    }

    private static String deviceReference(String deviceId){
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

    private static void walk(String nodeId,String incomingPort,PathState path,Map<String,GraphNode> nodes,AudioGraph graph,Set<String> visiting,
                             List<Map<String,Object>> routes,List<String> activeRoutes,Map<String,Object> routeChains,
                             Map<String,Map<String,Object>> outputLimiters,Set<String> sourceOutputs,
                             Set<String> externallyControlledGains){
        if(!visiting.add(nodeId))throw new IllegalArgumentException("Feedback cycle reached during graph compilation");
        GraphNode node=nodes.get(nodeId);if(node==null)throw new IllegalArgumentException("Connection references missing node "+nodeId);
        if(node instanceof DeviceSlotNode slot){
            String deviceId=deviceReference(slot.deviceId());
            if(deviceId.isBlank()){visiting.remove(nodeId);return;}
            List<GraphConnection> next=outgoing(graph,nodeId);
            if(next.isEmpty())node=new OutputNode(slot.id(),slot.name(),deviceId,slot.inputs().getFirst().channels());
            else for(GraphConnection edge:next){
                GraphNode target=nodes.get(edge.toNode());
                if(!(target instanceof OutputNode output)||!deviceId.equals(deviceReference(output.deviceId())))
                    throw new IllegalArgumentException("A bound device slot may only feed its assigned physical output");
            }
        }
        boolean endpointGain=node instanceof GainNode gain&&!gain.endpointNodeId().isBlank();
        if(path.outputLimiter!=null&&!(node instanceof OutputNode)&&!(node instanceof RouterNode)&&!endpointGain)
            throw new IllegalArgumentException("Peak limiter nodes must be the final processor before an output; move '"+node.name()+"' before the limiter");
        if(node instanceof RouterNode router){
            boolean protectMix = node instanceof MultiSelectorNode && activeInputCount(router, graph) > 1;
            for(GraphConnection edge:outgoing(graph,nodeId)){
                PathState branch=path.copy();
                branch.selected&=router.activeLinks().contains(incomingPort+"->"+edge.fromPort());
                branch.protectMixedOutput |= protectMix && branch.selected;
                applyEdgeGain(branch,edge);
                walk(edge.toNode(),edge.toPort(),branch,nodes,graph,new HashSet<>(visiting),routes,activeRoutes,routeChains,outputLimiters,sourceOutputs,externallyControlledGains);
            }
            visiting.remove(nodeId);return;
        }
        if(node instanceof OutputNode output){
            if(path.protectMixedOutput && path.outputLimiter == null)
                path.outputLimiter = Map.of("enabled", true, "ceiling_db", -1.0, "lookahead_ms", 5.0, "release_ms", 80.0);
            Map<String,Object> existingLimiter=outputLimiters.get(output.deviceId());
            if(path.outputLimiter!=null&&existingLimiter!=null&&!java.util.Objects.equals(existingLimiter,path.outputLimiter))
                throw new IllegalArgumentException("All paths to physical output '"+output.deviceId()+"' must share the same post-mix limiter");
            if(path.outputLimiter!=null)outputLimiters.put(output.deviceId(),path.outputLimiter);
            else outputLimiters.putIfAbsent(output.deviceId(),null);
            ChannelMatrix channelMatrix=output.channelMatrix();
            String targetChannels=channelMatrix==null?String.join(",",output.inputs().getFirst().channels()):String.join(",",channelMatrix.outputs());
            String targetKey=path.sourceId+"\u0000"+output.deviceId()+"\u0000"+targetChannels;
            if(path.selected&&!sourceOutputs.add(targetKey))throw new IllegalArgumentException("Multiple paths from source '"+path.sourceId+"' are selected for the same channels of output '"+output.deviceId()+"'; route alternatives through a Switch or Matrix node");
            String id=stableRouteId(path,output);boolean muted=path.muted||!path.selected;Map<String,Object> route=map("id",id,"name",path.sourceId+" → "+output.name(),"from",path.sourceId,"to",output.deviceId(),"volume",muted?0:Math.max(0,Math.min(1,path.gain)),"gain_db",0,"delay_ms",path.delayMs,"mute",muted,"channel_map","stereo","enabled",true);
            if(GraphCommonVibro.VIBRO_OUTPUT_ID.equals(output.id()))route.put("role","vibro");
            if(GraphCommonVibro.isBuiltinRoute(graph.id())&&"octine-event-source".equals(path.sourceId)
                    &&!GraphCommonVibro.VIBRO_OUTPUT_ID.equals(output.id()))
                throw new IllegalArgumentException("EVENT must not reach ordinary audio outputs");
            route.put("channel_map",layout(output.inputs().getFirst().channels()));
            if(channelMatrix!=null)route.put("channel_matrix",channelMatrix.toMap());
            if(!path.filters.isEmpty()){String chainId="graph-chain-"+id;route.put("filters_ref","dsp.route_chains."+chainId);routeChains.put(chainId,map("enabled",true,"filters",List.copyOf(path.filters)));}
            routes.add(route);activeRoutes.add(id);visiting.remove(nodeId);return;
        }
        if(node instanceof GainNode gain){if(!externallyControlledGains.contains(gain.id())){path.gain*=gain.gain();path.muted|=gain.muted();}}
        else if(node instanceof GateNode gate)path.muted|=!gate.open();
        else if(node instanceof FilterNode filter&&filter.enabled()){
            if("limiter".equals(filter.processor())){
                if(path.outputLimiter!=null)throw new IllegalArgumentException("A path can contain only one post-mix peak limiter");
                Map<String,Object> limiter=new LinkedHashMap<>(filter.parameters());limiter.remove("processor");limiter.remove("enabled");
                limiter.put("enabled",true);path.outputLimiter=Map.copyOf(limiter);
            }else if("delay".equals(filter.processor())){
                path.delayMs+=GraphFilterCompiler.delayMs(filter);
                if(path.delayMs>5000.0)throw new IllegalArgumentException("Combined graph delay must not exceed 5000 ms");
            }else{
                path.filters.addAll(GraphFilterCompiler.routeFilters(filter));
            }
        }else if(node instanceof MixerNode mixer){path.gain*=mixer.inputGains().getOrDefault(incomingPort,0.0);path.muted|=mixer.inputMuted(incomingPort);
            path.protectMixedOutput|=graph.connections().stream().filter(GraphConnection::enabled).filter(edge->edge.toNode().equals(mixer.id()))
                    .filter(edge->!mixer.inputMuted(edge.toPort())).map(GraphConnection::toPort).distinct().count()>1;}
        else if(!(node instanceof DeviceSlotNode))throw new IllegalArgumentException("Unsupported processing node "+node.type()+" '"+node.name()+"'");
        List<GraphConnection> next=outgoing(graph,nodeId);
        if(next.isEmpty())throw new IllegalArgumentException("Node '"+node.name()+"' has no connection to an output");
        for(GraphConnection edge:next){if(!edge.enabled())continue;PathState branch=path.copy();applyEdgeGain(branch,edge);walk(edge.toNode(),edge.toPort(),branch,nodes,graph,new HashSet<>(visiting),routes,activeRoutes,routeChains,outputLimiters,sourceOutputs,externallyControlledGains);}
        visiting.remove(nodeId);
    }

    private static void applyEdgeGain(PathState path,GraphConnection edge){
        if(path.outputLimiter!=null&&Math.abs(edge.gain()-1.0)>1e-9)
            throw new IllegalArgumentException("A post-mix peak limiter must be the final signal processor before an output");
        path.gain*=edge.gain();
        path.edgeIds.add(edge.id());
    }

    private static long activeInputCount(RouterNode router, AudioGraph graph) {
        Set<String> connectedInputs = graph.connections().stream().filter(GraphConnection::enabled)
                .filter(edge -> edge.toNode().equals(router.id())).map(GraphConnection::toPort)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> connectedOutputs = outgoing(graph, router.id()).stream().map(GraphConnection::fromPort)
                .collect(java.util.stream.Collectors.toSet());
        return router.activeLinks().stream().map(link -> link.split("->", 2))
                .filter(parts -> parts.length == 2 && connectedInputs.contains(parts[0]) && connectedOutputs.contains(parts[1]))
                .map(parts -> parts[0]).distinct().count();
    }

    private static String stableRouteId(PathState path,OutputNode output){
        String identity=path.sourceId+"\u0000"+output.id()+"\u0000"+String.join("\u0000",path.edgeIds);
        return "graph-"+UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private static List<GraphConnection> outgoing(AudioGraph graph,String id){return graph.connections().stream().filter(GraphConnection::enabled).filter(edge->edge.fromNode().equals(id)).toList();}
    private static Map<String,Object> savedApplicationRule(Map<String,Object> config,String key,String value){for(Map<String,Object> rule:maps(config.get("applications"))){Map<String,Object> match=object(rule.get("match"));if(value.equals(match.get(key)))return new LinkedHashMap<>(rule);}return map("volume",1.0,"mute",false);}
    private static String layout(List<String> channels){if(channels.equals(List.of("FL","FR")))return "stereo";if(channels.equals(List.of("MONO")))return "mono";throw new IllegalArgumentException("PipeWire graph application currently supports stereo FL/FR or mono MONO channels; got "+channels);}
    private static Map<String,Object> activeProfile(Map<String,Object> config){String id=String.valueOf(config.get("active_profile"));for(Map<String,Object> profile:maps(config.get("profiles")))if(id.equals(profile.get("id")))return profile;List<Map<String,Object>> profiles=maps(config.get("profiles"));if(profiles.isEmpty())throw new IllegalArgumentException("Configuration has no active profile");return profiles.getFirst();}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return value instanceof Map<?,?> map?(Map<String,Object>)map:new LinkedHashMap<>();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value){return value instanceof List<?> list?(List<Map<String,Object>>)list:List.of();}
    private static Map<String,Object> map(Object...pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    @SuppressWarnings("unchecked") private static Map<String,Object> deep(Map<String,Object> value){Map<String,Object> result=new LinkedHashMap<>();value.forEach((key,item)->result.put(key,copy(item)));return result;}
    private static Object copy(Object value){if(value instanceof Map<?,?> map){Map<String,Object> result=new LinkedHashMap<>();map.forEach((key,item)->result.put(String.valueOf(key),copy(item)));return result;}if(value instanceof List<?> list){List<Object> result=new ArrayList<>(list.size());list.forEach(item->result.add(copy(item)));return result;}return value;}
    private static final class PathState{final String sourceId;double gain;double delayMs;boolean muted;boolean selected;boolean protectMixedOutput;final List<Map<String,Object>> filters;final List<String> edgeIds;Map<String,Object> outputLimiter;PathState(String sourceId,double gain,boolean muted,boolean selected,List<Map<String,Object>> filters,List<String> edgeIds){this.sourceId=sourceId;this.gain=gain;this.muted=muted;this.selected=selected;this.filters=filters;this.edgeIds=edgeIds;}PathState copy(){PathState copy=new PathState(sourceId,gain,muted,selected,new ArrayList<>(filters),new ArrayList<>(edgeIds));copy.delayMs=delayMs;copy.outputLimiter=outputLimiter;copy.protectMixedOutput=protectMixedOutput;return copy;}}
    private record CaptureSources(Map<String,Map<String,Object>> bySourceId,Set<String> gainNodeIds,
                                  List<Map<String,Object>> metadata) {}
}
