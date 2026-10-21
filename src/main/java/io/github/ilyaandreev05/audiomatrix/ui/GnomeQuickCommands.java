package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeQuickControlParameter;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.GraphValidator;
import io.github.ilyaandreev05.audiomatrix.graph.RouterNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Commands of the GNOME quick menu that change groups, controls and the channel map. */
final class GnomeQuickCommands {
    private GnomeQuickCommands() {}

    static CompletableFuture<Void> setQuickGroupCollapsed(AppShellUiPart29 shell, String value) {
        String[] fields = value.split("\\t", 2);
        if (fields.length != 2 || !("true".equals(fields[1]) || "false".equals(fields[1])))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Quick settings group state is malformed"));
        return shell.updateRouteConfigCommand(shell.t("Изменено раскрытие группы GNOME", "GNOME group collapsed state changed"), config -> {
            Map<String, Object> group = QuickSettingsConfig.groups(config).stream()
                    .filter(candidate -> fields[0].equals(candidate.get("id"))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Quick settings group no longer exists"));
            group.put("collapsed", Boolean.parseBoolean(fields[1]));
        });
    }

    static CompletableFuture<Void> setQuickControl(AppShellUiPart29 shell, String value, boolean toggle) {
        String[] fields=value.split("\\t",2);if(fields.length<1||fields[0].isBlank())return CompletableFuture.failedFuture(new IllegalArgumentException("Quick control id is missing"));
        return shell.updateRouteConfigCommand(shell.t("Изменён параметр Audio Matrix", "Audio Matrix control changed"),config->{
            Map<String,Object> control=AppShellBase.maps(config.get("quick_controls")).stream().filter(item->fields[0].equals(item.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("Quick control no longer exists"));
            String routeId=AppShellBase.string(control.get("route_id"),AppShellBase.string(config.get("active_route"),""));if(!GraphUiRoutes.available(config,routeId))throw new IllegalArgumentException("Quick control is not on the active route");
            String nodeId=AppShellBase.string(control.get("node_id"),""),parameter=AppShellBase.string(control.get("parameter_id"),"");Map<String,Object> route=GraphUiRoutes.route(config,routeId);
            Map<String,Object> encodedNode=AppShellBase.maps(route.get("nodes")).stream().filter(item->nodeId.equals(item.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("Quick control node no longer exists"));
            if(!toggle&&fields.length<2)throw new IllegalArgumentException("Control value is missing");GnomeQuickControlParameter.apply(encodedNode,parameter,fields.length<2?"":fields[1],toggle);AudioGraph graph=GraphCodec.fromMap(route);List<String> issues=GraphValidator.validate(graph);if(!issues.isEmpty())throw new IllegalArgumentException(String.join("; ",issues));GraphRouteLibrary.saveRoute(config,graph,AppShellBase.string(route.get("group_id"),""));
        });
    }

    static CompletableFuture<Void> setQuickControlChoice(AppShellUiPart29 shell, String value) {
        String[] fields=value.split("\\t",2);if(fields.length!=2)return CompletableFuture.failedFuture(new IllegalArgumentException("Control choice is malformed"));
        return shell.updateRouteConfigCommand(shell.t("Изменён выбор маршрута в Audio Matrix", "Audio Matrix route choice changed"),config->{
            Map<String,Object> control=AppShellBase.maps(config.get("quick_controls")).stream().filter(item->fields[0].equals(item.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("Quick control no longer exists"));
            String routeId=AppShellBase.string(control.get("route_id"),AppShellBase.string(config.get("active_route"),""));if(!GraphUiRoutes.available(config,routeId))throw new IllegalArgumentException("Quick control is not on the active route");Map<String,Object> route=GraphUiRoutes.route(config,routeId);
            String parameter=AppShellBase.string(control.get("parameter_id"),"");if(!"active_links".equals(parameter)){Map<String,Object> encodedNode=AppShellBase.maps(route.get("nodes")).stream().filter(item->String.valueOf(control.get("node_id")).equals(item.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("Quick control node no longer exists"));GnomeQuickControlParameter.choose(encodedNode,parameter,fields[1]);AudioGraph changed=GraphCodec.fromMap(route);List<String> issues=GraphValidator.validate(changed);if(!issues.isEmpty())throw new IllegalArgumentException(String.join("; ",issues));GraphRouteLibrary.saveRoute(config,changed,AppShellBase.string(route.get("group_id"),""));return;}
            AudioGraph graph=GraphCodec.fromMap(route);RouterNode router=graph.nodes().stream().filter(node->node.id().equals(control.get("node_id"))&&node instanceof RouterNode).map(RouterNode.class::cast).findFirst().orElseThrow(()->new IllegalArgumentException("Route selector no longer exists"));String[] endpoints=fields[1].split("->",-1);boolean wiredInput=endpoints.length==2&&graph.connections().stream().anyMatch(edge->edge.enabled()&&edge.toNode().equals(router.id())&&edge.toPort().equals(endpoints[0]));boolean wiredOutput=endpoints.length==2&&graph.connections().stream().anyMatch(edge->edge.enabled()&&edge.fromNode().equals(router.id())&&edge.fromPort().equals(endpoints[1]));if(endpoints.length!=2||router.inputs().stream().noneMatch(port->port.id().equals(endpoints[0]))||router.outputs().stream().noneMatch(port->port.id().equals(endpoints[1]))||!wiredInput||!wiredOutput)throw new IllegalArgumentException("Route choice is invalid or disconnected");
            List<String> links=new ArrayList<>(router.activeLinks());if(router.multiple()){if(links.contains(fields[1]))links.remove(fields[1]);else links.add(fields[1]);}else{links.clear();links.add(fields[1]);}Map<String,Object> encodedNode=AppShellBase.maps(route.get("nodes")).stream().filter(item->router.id().equals(item.get("id"))).findFirst().orElseThrow();AppShellBase.map(encodedNode.get("parameters")).put("active_links",links);GraphRouteLibrary.saveRoute(config,GraphCodec.fromMap(route),AppShellBase.string(route.get("group_id"),""));
        });
    }

    static CompletableFuture<Void> setChannelMap(AppShellUiPart29 shell, String value) {
        String[] fields = value.split("\\t", 2);
        if (fields.length != 2 || !Set.of("stereo", "mono", "mono_sum", "left", "right", "swap_lr",
                "center", "lfe", "rear_left", "rear_right", "rear_sum", "side_left", "side_right", "side_sum", "surround_sum", "all_sum").contains(fields[1])) return CompletableFuture.failedFuture(new IllegalArgumentException("Channel map is invalid"));
        String routeId = fields[0];
        Map<String, Object> current = shell.store.snapshot();
        Map<String, Object> profile = AppShellBase.activeProfile(current);
        if (!AppShellBase.stringList(profile.get("active_routes")).contains(routeId)) return CompletableFuture.failedFuture(new IllegalArgumentException("Channel map route is not active"));
        Map<String, Object> route = AppShellBase.maps(current.get("routes")).stream()
                .filter(candidate -> routeId.equals(candidate.get("id")) && !Boolean.FALSE.equals(candidate.get("enabled")))
                .findFirst().orElse(null);
        AudioNode selected = shell.activeOutputNode();
        AudioNode target = route == null ? null : shell.routeOutputNode(current, route);
        if (selected == null || target == null || !selected.name().equals(target.name())) return CompletableFuture.failedFuture(new IllegalArgumentException("Channel map route is not on the selected output"));
        return shell.updateRouteConfigCommand(shell.t("Изменена карта каналов маршрута ", "Changed channel map for route ") + routeId, config -> AppShellBase.maps(config.get("routes")).stream()
                .filter(candidate -> routeId.equals(candidate.get("id")) && !Boolean.FALSE.equals(candidate.get("enabled")))
                .findFirst().ifPresent(candidate -> candidate.put("channel_map", fields[1])));
    }
}
