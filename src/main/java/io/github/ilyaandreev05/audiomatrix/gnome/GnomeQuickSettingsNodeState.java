package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.DeviceDisplayNames;
import io.github.ilyaandreev05.audiomatrix.config.UnifiedGraphConfig;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import java.util.*;

/** One capability-backed projection for saved nodes, composite children, and generated children. */
final class GnomeQuickSettingsNodeState {
    record Inputs(Map<String,Object> config,PipeWireBackend.Snapshot snapshot,List<Map<String,Object>> controls,
                  List<Map<String,Object>> applications,
                  Map<DynamicInstanceRegistry.InstanceAddress,DynamicInstanceRegistry.RuntimeInstance> instances) {
        Inputs(Map<String,Object> config,PipeWireBackend.Snapshot snapshot,List<Map<String,Object>> controls,
               List<Map<String,Object>> applications) {
            this(config,snapshot,controls,applications,Map.of());
        }
    }
    private GnomeQuickSettingsNodeState() {}

    static Map<String,Object> describe(Map<String,Object> pin,GraphNode node,AudioGraph graph,Inputs input,List<String> claims) {
        String routeId=String.valueOf(pin.get("route_id"));
        Map<String,Object> state=presentation(pin,node.id(),node.name(),node.type());
        state.put("available",input.snapshot().connected());
        Set<String> controlIds=strings(pin.get("control_ids"));
        state.put("controls",input.controls().stream().filter(control->controlIds.contains(String.valueOf(control.get("id"))))
                .filter(control->!(node instanceof GainNode&&Set.of("gain","muted").contains(String.valueOf(control.get("parameterId"))))).toList());
        if(node instanceof RouterNode router&&router.multiple())state.put("routeControl",routeControl(routeId,router,graph,input));
        if(node instanceof SourceNode source){
            sourceState(state,source,graph,input,claims);
        }else if(node instanceof DynamicGeneratorNode generator&&"applications".equals(generator.registry())){
            publishApplications(state,generator.selector(),input.applications(),claims);
        }else if(node instanceof OutputNode output){
            Map<String,Object> endpoint=outputState(output,graph,input);
            state.put("output",endpoint);state.put("available",endpoint.get("available"));
            if(!hasDisplayName(pin))state.put("name",endpoint.get("name"));
        }else if(node instanceof DeviceSlotNode slot){
            Map<String,Object> endpoint=GnomeDeviceSlotState.describe(slot,input.snapshot(),input.config());
            state.put("output",endpoint);state.put("available",endpoint.get("available"));
            if(!hasDisplayName(pin))state.put("name",endpoint.get("name"));
        }else if(node instanceof GainNode gain){
            state.put("gain",Map.of("routeId",routeId,"nodeId",gain.id(),"volume",gain.gain(),"muted",gain.muted()));
            GraphNode endpoint=node(graph,gain.endpointNodeId());
            if(endpoint instanceof OutputNode output)state.put("available",outputState(output,graph,input).get("available"));
            if(endpoint instanceof SourceNode source&&applicationSelector(source.selector()))
                state.put("available",input.snapshot().connected()&&!GnomeQuickSettingsPinDetails.applicationsFor(source.selector(),input.applications(),claims).isEmpty());
        }else if(node instanceof MixerNode mixer)state.put("mixerInputs",mixerInputs(graph,mixer,input));
        return state;
    }

    static Map<String,Object> presentation(Map<String,Object> pin,String nodeId,String name,String type){
        Map<String,Object> state=new LinkedHashMap<>();
        state.put("id",pin.get("id"));state.put("routeId",pin.get("route_id"));state.put("nodeId",nodeId);
        String nodeName=UnifiedGraphConfig.EVENT_GAIN_ID.equals(nodeId)
                ?"Event Sound Studio":DeviceDisplayNames.displayLabel(name,"Аудиоэлемент");
        Object configuredName=pin.get("display_name");
        state.put("name",configuredName instanceof String custom
                ?DeviceDisplayNames.displayLabel(custom,nodeName):nodeName);
        state.put("nodeType",type);state.put("groupId",pin.get("group_id"));state.put("order",pin.getOrDefault("order",0));
        state.put("view",pin.getOrDefault("view","DEFAULT"));state.put("pinned",true);
        state.put("expanded",pin.getOrDefault("expanded",!"COMPACT".equals(pin.get("view"))));
        state.put("showChildren",pin.getOrDefault("show_children",true));
        state.put("showWhenOffline",pin.getOrDefault("show_when_offline",false));
        if(pin.get("icon") instanceof String icon&&!icon.isBlank())state.put("icon",icon);
        return state;
    }

    private static boolean hasDisplayName(Map<String,Object> pin){
        return pin.get("display_name") instanceof String name
                &&!DeviceDisplayNames.displayLabel(name,"").isBlank();
    }

    private static void sourceState(Map<String,Object> state,SourceNode source,AudioGraph graph,Inputs input,List<String> claims){
        publishApplications(state,source.selector(),input.applications(),claims);
        GainNode gain=GnomeGraphSourceGain.forSource(graph,source.id());
        if(gain!=null)state.put("sourceGain",Map.of("routeId",state.get("routeId"),"sourceNodeId",source.id(),
                "gainNodeId",gain.id(),"volume",gain.gain(),"muted",gain.muted()));
        if(applicationSelector(source.selector()))state.put("available",input.snapshot().connected()&&!maps(state.get("applications")).isEmpty());
    }

    private static boolean applicationSelector(String selector){
        return !EventSoundStudioSource.SELECTOR.equals(selector)
                &&(selector.startsWith("application:")||selector.startsWith("process:"));
    }
    private static void publishApplications(Map<String,Object> state,String selector,List<Map<String,Object>> apps,List<String> claims){
        List<Map<String,Object>> served=GnomeQuickSettingsPinDetails.applicationsFor(selector,apps,claims);
        state.put("applications",served);
        state.put("tabAdapterAvailable",served.stream().anyMatch(app->Boolean.TRUE.equals(app.get("tabAdapterAvailable"))));
    }

    static Map<String,Object> outputState(OutputNode output,AudioGraph graph,Inputs input){
        return GnomeQuickSettingsPinDetails.outputFor(output,graph,input.snapshot(),input.config());
    }

    private static List<Map<String,Object>> mixerInputs(AudioGraph graph,MixerNode mixer,Inputs input){
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map.Entry<String,Double> entry:mixer.inputGains().entrySet()){
            boolean generated=DynamicInstanceRegistry.isGeneratedMixerInputPort(entry.getKey());
            var presentation=GraphMixerInputPresentation.describe(graph,mixer,entry.getKey(),input.config(),
                    input.instances(),input.snapshot().connected());
            // A retained generated input keeps its saved level for a later reconnect but must never
            // appear as a usable current row while its owner is gone.
            if(generated&&!presentation.available())continue;
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("id",entry.getKey());row.put("name",presentation.name());
            row.put("available",presentation.available());row.put("generated",generated);
            row.put("volume",entry.getValue());row.put("muted",mixer.inputMuted(entry.getKey()));
            result.add(row);
        }
        return List.copyOf(result);
    }

    private static Map<String,Object> routeControl(String routeId,RouterNode router,AudioGraph graph,Inputs input){
        List<GraphConnection> inputs=graph.connections().stream().filter(edge->router.id().equals(edge.toNode())).toList();
        List<GraphConnection> outputs=graph.connections().stream().filter(edge->router.id().equals(edge.fromNode())).toList();
        List<Map<String,Object>> options=new ArrayList<>();
        for(GraphConnection from:inputs)for(GraphConnection to:outputs){
            List<Map<String,Object>> endpoints=endpointStates(to.toNode(),graph,input);
            String label=endpoints.stream().map(endpoint->String.valueOf(endpoint.get("name"))).distinct().collect(java.util.stream.Collectors.joining(", "));
            boolean available=from.enabled()&&to.enabled()&&endpoints.stream().anyMatch(endpoint->Boolean.TRUE.equals(endpoint.get("available")));
            options.add(Map.of("id",from.toPort()+"->"+to.fromPort(),"label",label.isBlank()?"Выходное устройство":label,"available",available));
        }
        Set<String> available=options.stream().filter(option->Boolean.TRUE.equals(option.get("available")))
                .map(option->String.valueOf(option.get("id"))).collect(java.util.stream.Collectors.toSet());
        return Map.of("id","route:"+routeId+":"+router.id(),"routeId",routeId,"nodeId",router.id(),"switchNodeId",router.id(),
                "nodeType","switch","kind","choice","label","MultiSwitch","multiple",true,
                "value",router.activeLinks().stream().filter(available::contains).toList(),"options",options);
    }

    private static List<Map<String,Object>> endpointStates(String first,AudioGraph graph,Inputs input){
        List<Map<String,Object>> result=new ArrayList<>();Set<String> seen=new HashSet<>();Deque<String> pending=new ArrayDeque<>();pending.add(first);
        while(!pending.isEmpty()){
            String id=pending.removeFirst();if(!seen.add(id))continue;
            GraphNode node=node(graph,id);
            if(node instanceof OutputNode output){result.add(outputState(output,graph,input));continue;}
            if(node instanceof DeviceSlotNode slot){result.add(GnomeDeviceSlotState.describe(slot,input.snapshot(),input.config()));continue;}
            if(node instanceof GainNode gain&&!gain.endpointNodeId().isBlank()){
                GraphNode endpoint=node(graph,gain.endpointNodeId());
                if(endpoint instanceof OutputNode||endpoint instanceof DeviceSlotNode)pending.addLast(endpoint.id());
            }
            graph.connections().stream().filter(edge->id.equals(edge.fromNode())).map(GraphConnection::toNode).forEach(pending::addLast);
        }
        return result;
    }

    private static GraphNode node(AudioGraph graph,String id){
        for(GraphNode candidate:graph.nodes())if(candidate.id().equals(id))return candidate;
        return null;
    }

    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value){return value instanceof List<?> list?(List<Map<String,Object>>)list:List.of();}
    private static Set<String> strings(Object value){return value instanceof List<?> list?list.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet()):Set.of();}
}
