package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Physical output assignment rules for the saved route library. */
final class ConfigGraphPhysicalRules {
    private ConfigGraphPhysicalRules() {}

    /**
     * Two slots in one graph must not share a physical output, and the active route must not
     * double the always-applied Vibro output. Inactive routes may pre-bind shared hardware so
     * their outputs stay visible in the controls without becoming part of the applied graph.
     */
    static void validate(Map<String,AudioGraph> graphs,String activeRoute,List<String> errors){
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
}
