package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.github.ilyaandreev05.audiomatrix.ui.AppShellBase.string;

/** Reorders active presentation pins without reviving excluded generated children. */
final class GnomeQuickPinOrder {
    private GnomeQuickPinOrder() {}

    static void move(Map<String,Object> config,String pinId,String destinationGroup,int destinationOrder) {
        List<Map<String,Object>> pins=QuickSettingsConfig.pins(config);
        Map<String,Object> moving=pins.stream().filter(pin->pinId.equals(pin.get("id"))).findFirst().orElse(null);
        if(moving==null)return;
        String sourceGroup=string(moving.get("group_id"),"");
        List<Map<String,Object>> sourcePins=groupPins(pins,sourceGroup,moving);
        List<Map<String,Object>> destinationPins=sourceGroup.equals(destinationGroup)
                ?sourcePins:groupPins(pins,destinationGroup,moving);
        int index=Math.max(0,Math.min(destinationOrder,destinationPins.size()));
        destinationPins.add(index,moving);
        if(!sourceGroup.equals(destinationGroup)) {
            for(int i=0;i<sourcePins.size();i++)persist(config,sourcePins.get(i),sourceGroup,i);
        }
        for(int i=0;i<destinationPins.size();i++)persist(config,destinationPins.get(i),destinationGroup,i);
    }

    private static List<Map<String,Object>> groupPins(List<Map<String,Object>> pins,String groupId,Map<String,Object> moving) {
        return pins.stream().filter(pin->groupId.equals(pin.get("group_id"))&&pin!=moving
                        &&!Boolean.FALSE.equals(pin.get("pinned")))
                .sorted(Comparator.comparingInt(pin->order(pin.get("order"))))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static int order(Object value) {
        return value instanceof Number number?number.intValue():0;
    }

    private static void persist(Map<String,Object> config,Map<String,Object> pin,String groupId,int order) {
        QuickSettingsConfig.updatePin(config,string(pin.get("route_id"),""),string(pin.get("node_id"),""),
                string(pin.get("instance_key"),""),string(pin.get("child_id"),""),groupId,order,string(pin.get("view"),"DEFAULT"),
                string(pin.get("display_name"),""),string(pin.get("icon"),""),
                pin.get("show_children") instanceof Boolean children?children:null,
                pin.get("show_when_offline") instanceof Boolean offline?offline:null,
                pin.get("expanded") instanceof Boolean expanded?expanded:null);
    }
}
