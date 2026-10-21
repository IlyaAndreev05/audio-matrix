package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.config.GraphMatrixViewSettings;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.AudioPort;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GateNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.GraphSubgraphLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.GraphValidator;
import io.github.ilyaandreev05.audiomatrix.graph.MatrixNode;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.MultiSelectorNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SelectorNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import io.github.ilyaandreev05.audiomatrix.graph.SwitchNode;
import io.github.ilyaandreev05.audiomatrix.ui.AppShellTypes.Page;
import javafx.application.Platform;
import javafx.scene.control.TextInputDialog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Commands that mutate the nodes, ports and connections of a saved route graph. */
final class GraphNodeCommands {
    private GraphNodeCommands() {}

    static void addNode(AppShellUiPart06 shell,AudioGraph graph,String type){
        if(type==null)return;
        TextInputDialog nameDialog=new TextInputDialog(type);nameDialog.setTitle(shell.t("Добавить ноду","Add node"));nameDialog.setHeaderText(shell.t("Имя ноды","Node name"));shell.styleDialog(nameDialog);
        nameDialog.showAndWait().map(String::trim).filter(name->!name.isBlank()).ifPresent(name->{
            String id=type+"-"+UUID.randomUUID();List<String> stereo=List.of("FL","FR");
            if("output".equals(type)){
                List<AudioNode> available=shell.physicalSinks().stream().filter(node->node.channels()>=2).toList();if(available.isEmpty()){shell.setNotice(shell.t("Сейчас нет доступного стереовыхода.","No stereo output is currently available."),true);return;}
                List<String> outputLabels=available.stream().map(output->shell.nodeDisplayName(output)+" · "+output.name()).toList();javafx.scene.control.ChoiceDialog<String> choice=new javafx.scene.control.ChoiceDialog<>(outputLabels.getFirst(),outputLabels);choice.setTitle(shell.t("Выходная нода","Output node"));choice.setHeaderText(shell.t("Выбери доступный выход","Choose an available output"));shell.styleDialog(choice);
                choice.showAndWait().ifPresent(selectedLabel->{AudioNode output=available.get(outputLabels.indexOf(selectedLabel));shell.updateRouteConfig(shell.t("Добавить выходную ноду","Add output node"),config->{Map<String,Object> device=shell.ensureDevice(config,output);GraphNode node=new OutputNode(id,name,AppShellBase.string(device.get("id"),"system_default"),stereo);Map<String,Object> saved=GraphUiRoutes.route(config,graph.id());AudioGraph old=GraphCodec.fromMap(saved);List<GraphNode> nodes=new ArrayList<>(old.nodes());nodes.add(node);GraphRouteLibrary.saveRoute(config,new AudioGraph(old.id(),old.name(),nodes,old.connections()),AppShellBase.string(saved.get("group_id"),""));});});return;
            }
            if("device_slot".equals(type)){shell.updateGraph(graph.id(),nodes->nodes.add(new DeviceSlotNode(id,name,"",stereo)));return;}
            if("source".equals(type)){
                TextInputDialog selector=new TextInputDialog("application:unassigned");selector.setTitle(shell.t("Источник приложения","Application source"));selector.setHeaderText(shell.t("application:<id>, process:<name> или application:unassigned","application:<id>, process:<name>, or application:unassigned"));shell.styleDialog(selector);
                selector.showAndWait().map(String::trim).filter(value->!value.isBlank()).ifPresent(value->shell.updateGraph(graph.id(),nodes->nodes.add(new SourceNode(id,name,value,stereo))));return;
            }
            if("dynamic_generator".equals(type)){
                TextInputDialog selector=new TextInputDialog("application:*");selector.setTitle(shell.t("Генератор экземпляров","Instance generator"));selector.setHeaderText(shell.t("Selector сопоставления. Registry, template, привязку и GNOME-параметры можно настроить в инспекторе.","Matching selector. Configure registry, template, anchor and GNOME settings in the inspector."));shell.styleDialog(selector);
                selector.showAndWait().map(String::trim).filter(value->!value.isBlank()).ifPresent(value->shell.updateGraph(graph.id(),nodes->nodes.add(new DynamicGeneratorNode(id,name,value,stereo))));return;
            }
            GraphNode node=switch(type){case "gain"->new GainNode(id,name,stereo,1,false);case "gate"->new GateNode(id,name,stereo,true);case "switch"->new SwitchNode(id,name,stereo,List.of("out-1","out-2"),List.of());case "selector"->new SelectorNode(id,name,stereo,List.of("in-1","in-2"),List.of());case "multi_selector"->new MultiSelectorNode(id,name,stereo,List.of("in-1","in-2"),List.of());case "matrix"->new MatrixNode(id,name,stereo,List.of("in-1","in-2"),List.of("out-1","out-2"),List.of());case "mixer"->new MixerNode(id,name,stereo,Map.of("in-a",1.0,"in-b",1.0));default->new FilterNode(id,name,stereo,"low_pass",true,Map.of("frequency_hz",120.0,"slope_db_oct",24.0));};
            shell.updateGraph(graph.id(),nodes->nodes.add(node));
        });
    }

    static void setRouterLink(AppShellUiPart06 shell,String routeId,String nodeId,String link,boolean enabled,boolean multiple){
        shell.updateGraphNodeParameters(routeId,nodeId,parameters->{
            List<String> links=new ArrayList<>(AppShellBase.stringList(parameters.get("active_links")));
            if(enabled){if(!multiple)links.clear();if(!links.contains(link))links.add(link);}
            else links.remove(link);
            parameters.put("active_links",links);
        });
    }

    static void saveMatrixView(AppShellUiPart06 shell,String routeId, String nodeId, String view) {
        shell.store.updateAsync(config -> GraphMatrixViewSettings.set(config, routeId, nodeId, view))
                .whenComplete((ignored, error) -> {
                    if (error != null) Platform.runLater(() -> shell.setNotice(
                            shell.t("Вид Matrix не сохранён: ", "Matrix view was not saved: ") + AppShellBase.rootMessage(error), true));
                });
    }

    static void addRouterPort(AppShellUiPart06 shell,String routeId,GraphNode node,String direction){
        String key="input".equals(direction)?"input_ports":"output_ports";
        shell.updateGraphNodeParameters(routeId,node.id(),parameters->{
            List<String> ports=new ArrayList<>(AppShellBase.stringList(parameters.get(key)));
            int number=ports.size()+1;String candidate;
            do{candidate=("input".equals(direction)?"in-":"out-")+number++;}while(ports.contains(candidate));
            ports.add(candidate);parameters.put(key,ports);
        });
    }

    static void updateGraph(AppShellUiPart06 shell,String routeId,Consumer<List<GraphNode>> nodeChange){shell.updateRouteConfig(shell.t("Изменить граф","Edit graph"),config->{Map<String,Object> route=GraphUiRoutes.route(config,routeId);AudioGraph old=GraphCodec.fromMap(route);List<GraphNode> nodes=new ArrayList<>(old.nodes());nodeChange.accept(nodes);AudioGraph changed=new AudioGraph(old.id(),old.name(),nodes,old.connections());GraphRouteLibrary.saveRoute(config,changed,AppShellBase.string(route.get("group_id"),""));});}

    static void saveGraph(AppShellUiPart06 shell,String routeId,AudioGraph graph){shell.updateRouteConfig(shell.t("Сохранить граф","Save graph"),config->{Map<String,Object> route=GraphUiRoutes.route(config,routeId);GraphRouteLibrary.saveRoute(config,graph,AppShellBase.string(route.get("group_id"),""));});}

    static void updateNodeParameters(AppShellUiPart06 shell,String routeId,String nodeId,Consumer<Map<String,Object>> change){shell.updateRouteConfig(shell.t("Изменить параметр ноды","Edit node parameter"),config->{Map<String,Object> route=GraphUiRoutes.route(config,routeId);Map<String,Object> node=AppShellBase.maps(route.get("nodes")).stream().filter(item->nodeId.equals(item.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("Node no longer exists"));Map<String,Object> parameters=AppShellBase.map(node.get("parameters"));node.put("parameters",parameters);change.accept(parameters);AudioGraph graph=GraphCodec.fromMap(route);List<String> errors=GraphValidator.validate(graph);if(!errors.isEmpty())throw new IllegalArgumentException(String.join("; ",errors));GraphRouteLibrary.saveRoute(config,graph,AppShellBase.string(route.get("group_id"),""));});}

    static void removeNode(AppShellUiPart06 shell,String routeId,String nodeId){shell.updateRouteConfig(shell.t("Удалить ноду","Delete node"),config->{Map<String,Object> route=GraphUiRoutes.route(config,routeId);AudioGraph old=GraphCodec.fromMap(route);AudioGraph changed=new AudioGraph(old.id(),old.name(),old.nodes().stream().filter(node->!nodeId.equals(node.id())).toList(),old.connections().stream().filter(edge->!nodeId.equals(edge.fromNode())&&!nodeId.equals(edge.toNode())).toList());GraphRouteLibrary.saveRoute(config,changed,AppShellBase.string(route.get("group_id"),""));QuickSettingsConfig.removeNode(config,routeId,nodeId);GraphSubgraphLibrary.removeNode(config,routeId,nodeId);});}

    static void connectNodes(AppShellUiPart06 shell,String routeId,GraphNode source,AudioPort sourcePort,GraphNode target,AudioPort targetPort){if(source==null||target==null||sourcePort==null||targetPort==null)return;Map<String,Object> config=shell.store.snapshot();Map<String,Object> route=GraphUiRoutes.route(config,routeId);AudioGraph old=GraphCodec.fromMap(route);GraphConnection edge=new GraphConnection("link-"+UUID.randomUUID(),source.id(),sourcePort.id(),target.id(),targetPort.id(),true,1);List<GraphConnection> edges=new ArrayList<>(old.connections());edges.add(edge);AudioGraph next=new AudioGraph(old.id(),old.name(),old.nodes(),edges);List<String> errors=GraphValidator.validate(next);if(!errors.isEmpty()){shell.setNotice(String.join("; ",errors),true);shell.showPage(Page.GRAPH);return;}shell.saveGraph(routeId,next);}

    static void setConnectionEnabled(AppShellUiPart06 shell,String routeId,String connectionId,boolean enabled){mutateConnection(shell,routeId,connectionId,edge->new GraphConnection(edge.id(),edge.fromNode(),edge.fromPort(),edge.toNode(),edge.toPort(),enabled,edge.gain()));}

    static void removeConnection(AppShellUiPart06 shell,String routeId,String connectionId){Map<String,Object> route=GraphUiRoutes.route(shell.store.snapshot(),routeId);AudioGraph old=GraphCodec.fromMap(route);shell.saveGraph(routeId,new AudioGraph(old.id(),old.name(),old.nodes(),old.connections().stream().filter(edge->!connectionId.equals(edge.id())).toList()));}

    static void mutateConnection(AppShellUiPart06 shell,String routeId,String connectionId,java.util.function.Function<GraphConnection,GraphConnection> mutate){Map<String,Object> route=GraphUiRoutes.route(shell.store.snapshot(),routeId);AudioGraph old=GraphCodec.fromMap(route);List<GraphConnection> edges=old.connections().stream().map(edge->connectionId.equals(edge.id())?mutate.apply(edge):edge).toList();AudioGraph next=new AudioGraph(old.id(),old.name(),old.nodes(),edges);List<String> errors=GraphValidator.validate(next);if(!errors.isEmpty()){shell.setNotice(String.join("; ",errors),true);shell.showPage(Page.GRAPH);return;}shell.saveGraph(routeId,next);}
}
