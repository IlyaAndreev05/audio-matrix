package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
        List<String> problems=GraphValidator.validate(graph);if(!problems.isEmpty())throw new IllegalArgumentException(String.join("; ",problems));
        List<SourceNode> sources=graph.nodes().stream().filter(SourceNode.class::isInstance).map(SourceNode.class::cast).toList();
        if(sources.isEmpty()||graph.nodes().stream().noneMatch(OutputNode.class::isInstance))throw new IllegalArgumentException("The active route needs at least one source and one output");
        Map<String,GraphNode> nodes=new LinkedHashMap<>();graph.nodes().forEach(node->nodes.put(node.id(),node));
        for(GraphNode node:graph.nodes())if(node instanceof RouterNode&&node.type().equals("router"))
            throw new IllegalArgumentException("Legacy router node '"+node.name()+"' has no typed switching behavior; replace it with a Switch, MultiSwitch, Selector or Matrix node");
        List<Map<String,Object>> buses=new ArrayList<>(),routes=new ArrayList<>(),applications=new ArrayList<>();
        List<String> activeBusIds=new ArrayList<>(),activeRouteIds=new ArrayList<>();
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
            }else if(selector.startsWith("application:")&&!selector.substring("application:".length()).isBlank()){
                String appId=selector.substring("application:".length());Map<String,Object> rule=savedApplicationRule(baseConfig,"app_id",appId);rule.put("id","graph-rule-"+source.id());rule.put("bus",source.id());rule.put("match",map("app_id",appId));applications.add(rule);
            }else if(selector.startsWith("process:")&&!selector.substring("process:".length()).isBlank()){
                String process=selector.substring("process:".length());Map<String,Object> rule=savedApplicationRule(baseConfig,"process",process);rule.put("id","graph-rule-"+source.id());rule.put("bus",source.id());rule.put("match",map("process",process));applications.add(rule);
            }else throw new IllegalArgumentException("Unsupported source selector on '"+source.name()+"': "+selector+". Use application:<id>, process:<name>, or application:unassigned.");
        }
        for(SourceNode source:sources){
            List<GraphConnection> first=outgoing(graph,source.id());
            for(GraphConnection edge:first){PathState state=new PathState(source.id(),edge.gain(),false,true,new ArrayList<>(),new ArrayList<>());state.edgeIds.add(edge.id());walk(edge.toNode(),edge.toPort(),state,nodes,graph,new HashSet<>(),routes,activeRouteIds,routeChains,outputLimiters,sourceOutputs);}
        }
        if(routes.isEmpty())throw new IllegalArgumentException("The graph has no enabled path from a source to an output");
        Map<String,Object> result=deep(baseConfig);
        result.put("buses",buses);result.put("routes",routes);result.put("applications",applications);result.put("graph_default_bus",defaultBus);
        Map<String,Object> profile=activeProfile(result);profile.put("mode","multi-output");profile.put("active_buses",activeBusIds);profile.put("active_routes",activeRouteIds);
        Map<String,Object> dsp=object(result.get("dsp"));result.put("dsp",dsp);Map<String,Object> chains=object(dsp.get("route_chains"));dsp.put("route_chains",chains);chains.putAll(routeChains);
        Map<String,Object> encodedOutputLimiters=new LinkedHashMap<>();
        outputLimiters.forEach((deviceId,limiter)->{if(limiter!=null)encodedOutputLimiters.put(deviceId,new LinkedHashMap<>(limiter));});
        dsp.put("output_limiters",encodedOutputLimiters);
        return result;
    }

    private static void walk(String nodeId,String incomingPort,PathState path,Map<String,GraphNode> nodes,AudioGraph graph,Set<String> visiting,
                             List<Map<String,Object>> routes,List<String> activeRoutes,Map<String,Object> routeChains,
                             Map<String,Map<String,Object>> outputLimiters,Set<String> sourceOutputs){
        if(!visiting.add(nodeId))throw new IllegalArgumentException("Feedback cycle reached during graph compilation");
        GraphNode node=nodes.get(nodeId);if(node==null)throw new IllegalArgumentException("Connection references missing node "+nodeId);
        if(path.outputLimiter!=null&&!(node instanceof OutputNode)&&!(node instanceof RouterNode))
            throw new IllegalArgumentException("Peak limiter nodes must be the final processor before an output; move '"+node.name()+"' before the limiter");
        if(node instanceof RouterNode router){
            for(GraphConnection edge:outgoing(graph,nodeId)){
                PathState branch=path.copy();
                branch.selected&=router.activeLinks().contains(incomingPort+"->"+edge.fromPort());
                applyEdgeGain(branch,edge);
                walk(edge.toNode(),edge.toPort(),branch,nodes,graph,new HashSet<>(visiting),routes,activeRoutes,routeChains,outputLimiters,sourceOutputs);
            }
            visiting.remove(nodeId);return;
        }
        if(node instanceof OutputNode output){
            Map<String,Object> existingLimiter=outputLimiters.get(output.deviceId());
            if(path.outputLimiter!=null&&existingLimiter!=null&&!java.util.Objects.equals(existingLimiter,path.outputLimiter))
                throw new IllegalArgumentException("All paths to physical output '"+output.deviceId()+"' must share the same post-mix limiter");
            if(path.outputLimiter!=null)outputLimiters.put(output.deviceId(),path.outputLimiter);
            else outputLimiters.putIfAbsent(output.deviceId(),null);
            ChannelMatrix channelMatrix=output.channelMatrix();
            String targetChannels=channelMatrix==null?String.join(",",output.inputs().getFirst().channels()):String.join(",",channelMatrix.outputs());
            String targetKey=path.sourceId+"\u0000"+output.deviceId()+"\u0000"+targetChannels;
            if(path.selected&&!sourceOutputs.add(targetKey))throw new IllegalArgumentException("Multiple paths from source '"+path.sourceId+"' are selected for the same channels of output '"+output.deviceId()+"'; route alternatives through a Switch or Matrix node");
            String id=stableRouteId(path,output);boolean muted=path.muted||!path.selected;Map<String,Object> route=map("id",id,"name",path.sourceId+" → "+output.name(),"from",path.sourceId,"to",output.deviceId(),"volume",muted?0:Math.max(0,Math.min(1,path.gain)),"gain_db",0,"mute",muted,"channel_map","stereo","enabled",true);
            route.put("channel_map",layout(output.inputs().getFirst().channels()));
            if(channelMatrix!=null)route.put("channel_matrix",channelMatrix.toMap());
            if(!path.filters.isEmpty()){String chainId="graph-chain-"+id;route.put("filters_ref","dsp.route_chains."+chainId);routeChains.put(chainId,map("enabled",true,"filters",List.copyOf(path.filters)));}
            routes.add(route);activeRoutes.add(id);visiting.remove(nodeId);return;
        }
        if(node instanceof GainNode gain){path.gain*=gain.gain();path.muted|=gain.muted();}
        else if(node instanceof GateNode gate)path.muted|=!gate.open();
        else if(node instanceof FilterNode filter&&filter.enabled()){
            if("limiter".equals(filter.processor())){
                if(path.outputLimiter!=null)throw new IllegalArgumentException("A path can contain only one post-mix peak limiter");
                Map<String,Object> limiter=new LinkedHashMap<>(filter.parameters());limiter.remove("processor");limiter.remove("enabled");
                limiter.put("enabled",true);path.outputLimiter=Map.copyOf(limiter);
            }else{
                Map<String,Object> encoded=new LinkedHashMap<>(filter.parameters());encoded.remove("processor");encoded.remove("enabled");encoded.put("type",filter.processor());encoded.putIfAbsent("enabled",true);path.filters.add(encoded);
            }
        }else if(node instanceof MixerNode mixer){path.gain*=mixer.inputGains().getOrDefault(incomingPort,0.0);}
        else throw new IllegalArgumentException("Unsupported processing node "+node.type()+" '"+node.name()+"'");
        List<GraphConnection> next=outgoing(graph,nodeId);
        if(next.isEmpty())throw new IllegalArgumentException("Node '"+node.name()+"' has no connection to an output");
        for(GraphConnection edge:next){if(!edge.enabled())continue;PathState branch=path.copy();applyEdgeGain(branch,edge);walk(edge.toNode(),edge.toPort(),branch,nodes,graph,new HashSet<>(visiting),routes,activeRoutes,routeChains,outputLimiters,sourceOutputs);}
        visiting.remove(nodeId);
    }

    private static void applyEdgeGain(PathState path,GraphConnection edge){
        if(path.outputLimiter!=null&&Math.abs(edge.gain()-1.0)>1e-9)
            throw new IllegalArgumentException("A post-mix peak limiter must be the final signal processor before an output");
        path.gain*=edge.gain();
        path.edgeIds.add(edge.id());
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
    private static Object copy(Object value){if(value instanceof Map<?,?> map){Map<String,Object> result=new LinkedHashMap<>();map.forEach((key,item)->result.put(String.valueOf(key),copy(item)));return result;}if(value instanceof List<?> list)return list.stream().map(GraphPipeWireCompiler::copy).toList();return value;}
    private static final class PathState{final String sourceId;double gain;boolean muted;boolean selected;final List<Map<String,Object>> filters;final List<String> edgeIds;Map<String,Object> outputLimiter;PathState(String sourceId,double gain,boolean muted,boolean selected,List<Map<String,Object>> filters,List<String> edgeIds){this.sourceId=sourceId;this.gain=gain;this.muted=muted;this.selected=selected;this.filters=filters;this.edgeIds=edgeIds;}PathState copy(){PathState copy=new PathState(sourceId,gain,muted,selected,new ArrayList<>(filters),new ArrayList<>(edgeIds));copy.outputLimiter=outputLimiter;return copy;}}
}
