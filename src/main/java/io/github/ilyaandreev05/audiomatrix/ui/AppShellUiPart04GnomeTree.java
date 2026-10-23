package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.ThemeManager;
import io.github.ilyaandreev05.audiomatrix.audio.*;
import io.github.ilyaandreev05.audiomatrix.config.*;
import io.github.ilyaandreev05.audiomatrix.gnome.*;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.transform.Scale;
import javafx.stage.*;
import javafx.util.Duration;
import javafx.util.StringConverter;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

abstract class AppShellUiPart04GnomeTree extends AppShellUiPart04GnomePreview {
    protected AppShellUiPart04GnomeTree(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }
    protected TreeView<GnomePanelItem> gnomePanelTree(Map<String,Object> config,List<Map<String,Object>> projectedGroups,
                                                    DynamicInstanceRegistry.ReconcileResult activeRuntime){
        GnomePanelItem rootValue=new GnomePanelItem("ROOT",t("Панель GNOME","GNOME panel"),"","","","",null,null,null,null);
        TreeItem<GnomePanelItem> root=new TreeItem<>(rootValue);root.setExpanded(true);
        List<Map<String,Object>> groups=new ArrayList<>(QuickSettingsConfig.groups(config));
        groups.sort(Comparator.comparingInt(group->intValue(group.get("order"),0)));
        List<Map<String,Object>> pins=QuickSettingsConfig.pins(config);
        Map<String,AudioGraph> graphByRoute=new LinkedHashMap<>();
        Map<String,Map<String,Object>> routeById=new LinkedHashMap<>();
        String profileId=GraphRouteLibrary.activeProfileId(config);
        for(Map<String,Object> route:GraphRouteLibrary.routesForProfile(config,profileId)){
            String routeId=string(route.get("id"),"");routeById.put(routeId,route);
            try{graphByRoute.put(routeId,GraphCodec.fromMap(route));}catch(RuntimeException ignored){}
        }
        if(config.get("common_vibro_graph") instanceof Map<?,?>){
            Map<String,Object> common=GraphUiRoutes.route(config,GraphCommonVibro.COMMON_ROUTE_ID);
            String routeId=GraphCommonVibro.COMMON_ROUTE_ID;routeById.put(routeId,common);
            try{graphByRoute.put(routeId,GraphCodec.fromMap(common));}catch(RuntimeException ignored){}
        }

        for(Map<String,Object> group:groups){
            String groupId=string(group.get("id"),"");
            TreeItem<GnomePanelItem> groupItem=new TreeItem<>(new GnomePanelItem("GROUP",
                    string(group.get("name"),groupId),"","","",groupId,null,null,null,null));
            List<Map<String,Object>> groupPins=pins.stream().filter(pin->groupId.equals(pin.get("group_id"))
                            &&!Boolean.FALSE.equals(pin.get("pinned")))
                    .sorted(Comparator.comparingInt(pin->intValue(pin.get("order"),0))).toList();
            for(Map<String,Object> pin:groupPins){
                String routeId=string(pin.get("route_id"),""),nodeId=string(pin.get("node_id"),"");
                String instanceKey=string(pin.get("instance_key"),"");
                AudioGraph graph=graphByRoute.get(routeId);
                GraphNode node=graph==null?null:graph.nodes().stream().filter(candidate->nodeId.equals(candidate.id())).findFirst().orElse(null);
                GraphSubgraph composite=routeById.containsKey(routeId)?GraphSubgraphLibrary.forRoute(config,routeId).stream()
                        .filter(candidate->nodeId.equals(candidate.id())).findFirst().orElse(null):null;
                DynamicInstanceRegistry.RuntimeInstance instance=findRuntimeInstance(activeRuntime,routeId,nodeId,instanceKey);
                String childId=string(pin.get("child_id"),"");
                if(!childId.isBlank()&&instance!=null&&activeRuntime!=null){
                    String runtimeId=instance.nodeIds().get(childId);
                    node=activeRuntime.effectiveGraph().nodes().stream()
                            .filter(candidate->candidate.id().equals(runtimeId)).findFirst().orElse(null);
                }
                if(node==null&&instance!=null&&activeRuntime!=null)
                    node=activeRuntime.effectiveGraph().nodes().stream().filter(candidate->nodeId.equals(candidate.id())).findFirst().orElse(null);
                String fallback=composite==null?(node==null?nodeId:node.name()):composite.name();
                String display=string(pin.get("display_name"),fallback);
                if(!childId.isBlank())display+=" · "+t("дочерний узел","child node");
                GnomePanelItem panelItem=new GnomePanelItem("PIN",display,routeId,nodeId,instanceKey,groupId,pin,node,composite,instance);
                groupItem.getChildren().add(new TreeItem<>(panelItem));
            }
            root.getChildren().add(groupItem);
        }

        TreeItem<GnomePanelItem> savedRoot=new TreeItem<>(new GnomePanelItem("CATEGORY",
                t("Сохранённые графы и экземпляры","Saved graphs and instances"),"","","","",null,null,null,null));
        savedRoot.setExpanded(true);
        for(Map.Entry<String,AudioGraph> entry:graphByRoute.entrySet()){
            String routeId=entry.getKey();AudioGraph configured=entry.getValue();
            DynamicInstanceRegistry.ReconcileResult runtime=activeRuntime!=null&&routeId.equals(activeRouteId(config))
                    ?activeRuntime:null;
            AudioGraph effective=runtime==null?configured:runtime.effectiveGraph();
            TreeItem<GnomePanelItem> routeItem=new TreeItem<>(new GnomePanelItem("ROUTE",
                    configured.name(),routeId,"","","",null,null,null,null));
            for(GraphNode savedNode:configured.nodes()){
                GraphNode displayNode=effective.nodes().stream().filter(candidate->savedNode.id().equals(candidate.id())).findFirst().orElse(savedNode);
                String kind=displayNode instanceof DynamicGeneratorNode?"GENERATOR":"NODE";
                boolean isPinned=pins.stream().anyMatch(pin->routeId.equals(pin.get("route_id"))
                        &&displayNode.id().equals(pin.get("node_id"))&&string(pin.get("instance_key"),"").isBlank());
                String label=displayNode.name()+" · "+GraphNodeLabels.typeName(displayNode,this::t)+(isPinned?" · ✓":"");
                TreeItem<GnomePanelItem> nodeItem=new TreeItem<>(new GnomePanelItem(kind,label,routeId,
                        displayNode.id(),"","",null,displayNode,null,null));
                if(displayNode instanceof DynamicGeneratorNode generator&&runtime!=null)
                    addGeneratedInstances(nodeItem,routeId,generator,runtime,effective,pins);
                routeItem.getChildren().add(nodeItem);
            }
            List<GraphSubgraph> savedGroups=GraphSubgraphLibrary.forRoute(config,routeId);
            Map<String,TreeItem<GnomePanelItem>> compositeItems=new LinkedHashMap<>();
            for(GraphSubgraph group:savedGroups){
                boolean generated=group.generatorId()!=null;
                String label=group.name()+" · "+group.nodeIds().size()+t(" нод"," nodes")+(generated?t(" · runtime"," · runtime"):"");
                compositeItems.put(group.id(),new TreeItem<>(new GnomePanelItem(generated?"GENERATED_COMPOSITE":"COMPOSITE",
                        label,routeId,group.id(),"","",null,null,group,null)));
            }
            for(GraphSubgraph group:savedGroups){
                TreeItem<GnomePanelItem> compositeItem=compositeItems.get(group.id());
                TreeItem<GnomePanelItem> parent=compositeItems.get(group.parentId());
                (parent==null?routeItem:parent).getChildren().add(compositeItem);
                for(String childId:group.nodeIds()){
                    GraphNode child=configured.nodes().stream().filter(candidate->childId.equals(candidate.id())).findFirst().orElse(null);
                    if(child!=null)compositeItem.getChildren().add(new TreeItem<>(new GnomePanelItem("NODE",
                            child.name()+" · "+GraphNodeLabels.typeName(child,this::t),routeId,child.id(),"","",null,child,null,null)));
                }
            }
            savedRoot.getChildren().add(routeItem);
        }
        root.getChildren().add(savedRoot);
        TreeView<GnomePanelItem> tree=new TreeView<>(root);tree.setShowRoot(false);
        tree.setCellFactory(view->new TreeCell<>(){
            @Override protected void updateItem(GnomePanelItem item,boolean empty){
                super.updateItem(item,empty);setText(empty||item==null?null:item.label());
                setOnDragDetected(event->{
                    GnomePanelItem source=getItem();
                    if(source==null)return;
                    String value;
                    if("GROUP".equals(source.kind()))value="group:"+source.groupId();
                    else if("PIN".equals(source.kind())&&source.pin()!=null)value="pin:"+string(source.pin().get("id"),"");
                    else return;
                    Dragboard dragboard=startDragAndDrop(TransferMode.MOVE);
                    ClipboardContent content=new ClipboardContent();content.putString(value);dragboard.setContent(content);event.consume();
                });
                setOnDragOver(event->{
                    GnomePanelItem target=getItem();
                    if(event.getDragboard().hasString()&&target!=null&&("GROUP".equals(target.kind())||"PIN".equals(target.kind())))
                        event.acceptTransferModes(TransferMode.MOVE);
                    event.consume();
                });
                setOnDragDropped(event->{
                    GnomePanelItem target=getItem();boolean completed=false;
                    if(target!=null&&event.getDragboard().hasString()){
                        String dragged=event.getDragboard().getString();
                        if(dragged.startsWith("group:")&&"GROUP".equals(target.kind())){
                            int destination=groups.stream().filter(group->target.groupId().equals(group.get("id")))
                                    .mapToInt(group->intValue(group.get("order"),0)).findFirst().orElse(0);
                            updateRouteConfig(t("Изменить порядок групп GNOME","Reorder GNOME groups"),
                                    candidate->QuickSettingsConfig.moveGroup(candidate,dragged.substring(6),destination));
                            completed=true;
                        }else if(dragged.startsWith("pin:")){
                            int order="PIN".equals(target.kind())?intValue(target.pin().get("order"),0):Integer.MAX_VALUE;
                            moveQuickPin(dragged.substring(4),target.groupId(),order);completed=true;
                        }
                    }
                    event.setDropCompleted(completed);event.consume();
                });
            }
        });
        installQuickPinDrag(tree);
        return tree;
    }

    private void addGeneratedInstances(TreeItem<GnomePanelItem> generatorItem,String routeId,DynamicGeneratorNode generator,
                                       DynamicInstanceRegistry.ReconcileResult runtime,AudioGraph effective,
                                       List<Map<String,Object>> pins){
        for(DynamicInstanceRegistry.RuntimeInstance instance:runtime.instances().values().stream()
                .filter(candidate->generator.id().equals(candidate.generatorId())).toList()){
            String instanceKey=instance.instanceKey();
            GraphSubgraph generated=runtime.generatedSubgraphs().stream()
                    .filter(group->instance.compositeId().equals(group.id())).findFirst().orElse(null);
            String name=generated==null?instanceKey:generated.name();
            String state=instance.available()?t("доступен","available"):t("не подключено","not connected");
            TreeItem<GnomePanelItem> instanceItem=new TreeItem<>(new GnomePanelItem("INSTANCE",
                    name+" · "+state,routeId,generator.id(),instanceKey,"",null,generator,null,instance));
            boolean pinned=pins.stream().anyMatch(pin->routeId.equals(pin.get("route_id"))
                    &&generator.id().equals(pin.get("node_id"))&&instanceKey.equals(pin.get("instance_key"))
                    &&string(pin.get("child_id"),"").isBlank()&&!Boolean.FALSE.equals(pin.get("pinned")));
            if(pinned)instanceItem.setValue(new GnomePanelItem("INSTANCE",instanceItem.getValue().label()+" · ✓",
                    routeId,generator.id(),instanceKey,"",null,generator,null,instance));
            for(String childId:instance.nodeIds().values()){
                GraphNode child=effective.nodes().stream().filter(candidate->childId.equals(candidate.id())).findFirst().orElse(null);
                if(child==null)continue;
                instanceItem.getChildren().add(new TreeItem<>(new GnomePanelItem("GENERATED_NODE",
                        child.name()+" · "+GraphNodeLabels.typeName(child,this::t),routeId,generator.id(),instanceKey,"",null,child,null,instance)));
            }
            if(generated!=null)instanceItem.getChildren().add(new TreeItem<>(new GnomePanelItem("GENERATED_COMPOSITE",
                    generated.name()+" · "+generated.nodeIds().size()+t(" дочерних узлов"," child nodes"),
                    routeId,generator.id(),instanceKey,"",null,generator,generated,instance)));
            generatorItem.getChildren().add(instanceItem);
        }
    }

    private DynamicInstanceRegistry.RuntimeInstance findRuntimeInstance(DynamicInstanceRegistry.ReconcileResult runtime,
                                                                        String routeId,String generatorId,String instanceKey){
        if(runtime==null||instanceKey.isBlank()||!runtime.configuredGraph().id().equals(routeId))return null;
        return runtime.instances().values().stream().filter(instance->generatorId.equals(instance.generatorId())
                &&instanceKey.equals(instance.instanceKey())).findFirst().orElse(null);
    }

    private String activeRouteId(Map<String,Object> config){
        return string(config.get("active_route"),"");
    }

    protected List<TreeItem<GnomePanelItem>> findPanelItems(TreeItem<GnomePanelItem> root,String key){
        List<TreeItem<GnomePanelItem>> result=new ArrayList<>();
        if(root==null)return result;
        if(root.getValue()!=null&&root.getValue().key().equals(key))result.add(root);
        for(TreeItem<GnomePanelItem> child:root.getChildren())result.addAll(findPanelItems(child,key));
        return result;
    }

    private void installQuickPinDrag(TreeView<GnomePanelItem> tree){
        tree.setAccessibleHelp(t("Закрепления можно перемещать мышью; используйте также кнопки перемещения вверх и вниз.",
                "Pins can be moved with the mouse; Up and Down buttons are also available."));
    }
}
