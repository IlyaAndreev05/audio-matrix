package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.ilyaandreev05.audiomatrix.gnome.GnomeQuickSettingsGeneratedPins.browserTab;
import static io.github.ilyaandreev05.audiomatrix.gnome.GnomeQuickSettingsGeneratedPins.generatedGain;
import static io.github.ilyaandreev05.audiomatrix.gnome.GnomeQuickSettingsGeneratedPins.generator;
import static io.github.ilyaandreev05.audiomatrix.gnome.GnomeQuickSettingsGeneratedPins.state;

/** Resolves explicit saved instance and template-child pin identities. */
final class GnomeQuickSettingsSavedGeneratedPin {
    private GnomeQuickSettingsSavedGeneratedPin() {}
    static Map<String, Object> savedPin(Map<String, Object> pin, String routeId, AudioGraph graph,
                                        PipeWireBackend.Snapshot snapshot, Map<String, Object> config,
                                        List<Map<String, Object>> applications, List<Map<String, Object>> outputs,
                                        Map<DynamicInstanceRegistry.InstanceAddress, DynamicInstanceRegistry.RuntimeInstance> instances) {
        if(Boolean.FALSE.equals(pin.get("pinned")))return null;
        String generatorId = String.valueOf(pin.getOrDefault("node_id", ""));
        String instanceKey = String.valueOf(pin.getOrDefault("instance_key", ""));
        String childId=String.valueOf(pin.getOrDefault("child_id",""));
        DynamicGeneratorNode generator = generator(graph, generatorId);
        if(generator==null)return null;
        DynamicInstanceRegistry.RuntimeInstance instance = instances.get(
                new DynamicInstanceRegistry.InstanceAddress(generatorId, instanceKey));
        boolean showOffline=Boolean.TRUE.equals(pin.getOrDefault("show_when_offline",
                instance==null?false:instance.overrides().getOrDefault("show_when_offline",false)));
        if(instance==null){
            if(!showOffline)return null;
            String name=childId.isBlank()?generator.name():generator.templateNodes().stream()
                    .filter(child->childId.equals(child.get("id"))).map(child->String.valueOf(child.get("name"))).findFirst().orElse(generator.name());
            Map<String,Object> missing=GnomeQuickSettingsNodeState.presentation(pin,generator.id(),name,
                    childId.isBlank()?"dynamic_generator":generator.templateNodes().stream().filter(child->childId.equals(child.get("id")))
                            .map(child->String.valueOf(child.get("type"))).findFirst().orElse("unavailable"));
            missing.put("instanceKey",instanceKey);missing.put("childId",childId);missing.put("available",false);
            return missing;
        }
        if(!instance.available()&&!showOffline)return null;
        if(!childId.isBlank())return childState(pin,childId,generator,instance,routeId,graph,snapshot,config,applications,outputs,showOffline);
        // A real metadata-only tab remains visible, but it must not acquire a fictional gain.
        Map<String,Object> renderPin=new LinkedHashMap<>(pin);renderPin.put("show_when_offline",true);
        Map<String,Object> result=state(renderPin,generator,instance,routeId,graph,snapshot,config,applications,outputs);
        if(result==null)return null;
        result.put("showWhenOffline",showOffline);
        result.put("available",snapshot.connected()&&instance.available());
        if(!instance.gainControlAvailable())result.remove("gain");
        return result;
    }
    private static Map<String,Object> childState(Map<String,Object> pin,String childId,DynamicGeneratorNode generator,
            DynamicInstanceRegistry.RuntimeInstance instance,String routeId,AudioGraph graph,PipeWireBackend.Snapshot snapshot,
            Map<String,Object> config,List<Map<String,Object>> applications,List<Map<String,Object>> outputs,boolean showOffline){
        String runtimeId=instance.nodeIds().get(childId);
        GraphNode child=graph.nodes().stream().filter(node->node.id().equals(runtimeId)).findFirst().orElse(null);
        if(child==null)return null;
        Map<String,Object> result=GnomeQuickSettingsNodeState.describe(pin,child,graph,
                new GnomeQuickSettingsNodeState.Inputs(config,snapshot,List.of(),applications),List.of());
        result.put("nodeId",generator.id());result.put("runtimeNodeId",child.id());
        result.put("instanceKey",instance.instanceKey());result.put("childId",childId);result.put("registry",generator.registry());
        result.put("showWhenOffline",showOffline);result.put("available",snapshot.connected()&&instance.available());
        result.remove("sourceGain");result.remove("gain");
        result.remove("routeControl");result.remove("mixerInputs");result.put("controls",List.of());
        if(result.get("output") instanceof Map<?,?> endpoint)endpoint.remove("graphGainNodeId");
        GainNode gain=generatedGain(graph,instance);
        if(gain!=null&&instance.gainControlAvailable()&&List.of("source","gain","output").contains(childId))
            result.put("gain",Map.of("routeId",routeId,"nodeId",generator.id(),"instanceKey",instance.instanceKey(),
                    "volume",gain.gain(),"muted",gain.muted()));
        Map<String,Object> tab=browserTab(instance,applications);
        if(tab!=null)result.put("browserTabs",List.of(tab));
        return result;
    }
}
