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
import javafx.scene.input.MouseEvent;
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
import java.util.function.Consumer;

abstract class AppShellUiPart06 extends AppShellUiPart05 {
    protected AppShellUiPart06(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected VBox graphNodeCard(AudioGraph graph){
        VBox box=card(t("Ноды", "Nodes"));
        HBox add=new HBox(9);ComboBox<String> type=new ComboBox<>();type.getItems().setAll("source","output","gain","gate","switch","multi_switch","selector","multi_selector","matrix","mixer","filter");type.getSelectionModel().select("source");
        add.getChildren().addAll(type,actionButton(t("Добавить ноду", "Add node"),()->addGraphNode(graph,type.getValue())));box.getChildren().add(add);
        HBox subgraphAction=new HBox(10);subgraphAction.setAlignment(Pos.CENTER_LEFT);
        Label selectionCount=label(selectedSubgraphNodeIds.size()+t(" нод выбрано"," nodes selected"),"muted");
        Button createSubgraph=actionButton(t("Свернуть выбранные в подграф","Collapse selected into subgraph"),()->createGraphSubgraph(graph));createSubgraph.setDisable(selectedSubgraphNodeIds.size()<2);
        subgraphAction.getChildren().addAll(selectionCount,createSubgraph);box.getChildren().add(subgraphAction);
        List<GraphSubgraph> subgraphs=GraphSubgraphLibrary.forRoute(store.snapshot(),graph.id());
        if(!subgraphs.isEmpty()){
            VBox groups=new VBox(5);groups.getChildren().add(label(t("Подграфы","Subgraphs"),"card-title"));
            for(GraphSubgraph group:subgraphs){
                HBox row=new HBox(9);row.setAlignment(Pos.CENTER_LEFT);row.getChildren().add(label(group.name()+" · "+group.nodeIds().size()+t(" нод"," nodes"),"card-title"));
                row.getChildren().add(actionButton(group.collapsed()?t("Развернуть","Expand"):t("Свернуть","Collapse"),()->setSubgraphCollapsed(graph.id(),group.id(),!group.collapsed())));
                row.getChildren().add(actionButton(t("Удалить","Delete"),()->deleteGraphSubgraph(graph.id(),group.id())));groups.getChildren().add(row);
            }
            box.getChildren().add(groups);
        }
        if(graph.nodes().isEmpty()){box.getChildren().add(label(t("Граф пуст.","Graph is empty."),"muted"));return box;}
        for(GraphNode node:graph.nodes()){
            HBox row=new HBox(12);row.setAlignment(Pos.CENTER_LEFT);CheckBox subgraphSelection=new CheckBox();subgraphSelection.setAccessibleText(t("Выбрать ноду для подграфа: ","Select node for subgraph: ")+node.name());subgraphSelection.setSelected(selectedSubgraphNodeIds.contains(node.id()));subgraphSelection.setOnAction(event->{if(subgraphSelection.isSelected())selectedSubgraphNodeIds.add(node.id());else selectedSubgraphNodeIds.remove(node.id());selectionCount.setText(selectedSubgraphNodeIds.size()+t(" нод выбрано"," nodes selected"));createSubgraph.setDisable(selectedSubgraphNodeIds.size()<2);});row.getChildren().addAll(subgraphSelection,label(node.name()+" · "+node.type(),"card-title"));
            if(node instanceof GainNode gain){double effective=gain.muted()?0:gain.gain();Slider level=new Slider(0,1,effective);level.setPrefWidth(180);Label value=label(volumeLabel(effective),"muted");level.valueProperty().addListener((o,a,v)->value.setText(volumeLabel(v.doubleValue())));level.setOnMouseReleased(e->updateGraphNodeParameters(graph.id(),node.id(),parameters->{parameters.put("gain",Math.round(level.getValue()*1000)/1000.0);parameters.put("muted",false);}));level.setOnKeyReleased(e->updateGraphNodeParameters(graph.id(),node.id(),parameters->{parameters.put("gain",Math.round(level.getValue()*1000)/1000.0);parameters.put("muted",false);}));CheckBox mute=new CheckBox(t("Mute","Mute"));mute.setSelected(gain.muted());mute.setOnAction(e->updateGraphNodeParameters(graph.id(),node.id(),parameters->parameters.put("muted",mute.isSelected())));row.getChildren().addAll(level,value,mute);}
            else if(node instanceof MixerNode mixer){
                row.getChildren().add(GraphMixerControls.create(mixer,t("Уровень и mute входов","Mixer input levels and mute"),t("Mute","Mute"),
                        (port,gain)->updateGraphMixerInput(graph.id(),mixer.id(),port,gain,null),
                        (port,muted)->updateGraphMixerInput(graph.id(),mixer.id(),port,null,muted)));
            }
            else if(node instanceof OutputNode output){
                ComboBox<String> channelTarget=new ComboBox<>();channelTarget.getItems().setAll(
                        t("Передние каналы FL/FR","Front channels FL/FR"),
                        t("Тыловые каналы RL/RR · нужен профиль 4.0","Rear channels RL/RR · requires 4.0 profile"));
                channelTarget.setAccessibleText(t("Каналы физического выхода","Physical output channels"));
                channelTarget.setValue(output.channelMatrix()!=null&&output.channelMatrix().outputs().equals(List.of("RL","RR"))
                        ?channelTarget.getItems().get(1):channelTarget.getItems().getFirst());
                channelTarget.setOnAction(event->{boolean rear=channelTarget.getSelectionModel().getSelectedIndex()==1;
                    updateGraphNodeParameters(graph.id(),node.id(),parameters->{
                        if(rear){ChannelMatrix matrix=new ChannelMatrix(List.of("FL","FR"),List.of("RL","RR"),List.of(List.of(1.0,0.0),List.of(0.0,1.0)));parameters.put("channel_matrix",matrix.toMap());}
                        else parameters.remove("channel_matrix");
                    });
                });
                row.getChildren().add(channelTarget);
            }
            else if(node instanceof GateNode gate){CheckBox open=new CheckBox(t("Gate открыт","Gate open"));open.setSelected(gate.open());open.setOnAction(e->updateGraphNodeParameters(graph.id(),node.id(),parameters->parameters.put("open",open.isSelected())));row.getChildren().add(open);}
            else if(node instanceof FilterNode filter){CheckBox bypass=new CheckBox(filter.processor()+t(" · включён"," · enabled"));bypass.setSelected(filter.enabled());bypass.setOnAction(e->updateGraphNodeParameters(graph.id(),node.id(),parameters->parameters.put("enabled",bypass.isSelected())));row.getChildren().add(bypass);}
            else if(node instanceof RouterNode router){
                List<String> active=router.activeLinks();
                VBox routerControls=new VBox(5);
                if(node instanceof MatrixNode matrix){
                    routerControls.getChildren().add(GraphMatrixViews.create(matrix,
                            t("Матрица","Matrix"),t("Стрелки","Arrows"),t("Вход ↓ / выход →","Input ↓ / output →"),
                            (link,enabled)->setRouterLink(graph.id(),node.id(),link,enabled)));
                }else{
                    GridPane table=new GridPane();table.setHgap(8);table.setVgap(4);
                    table.add(label(t("Вход ↓ / выход →","Input ↓ / output →"),"muted"),0,0);
                    for(int col=0;col<router.outputs().size();col++)table.add(label(router.outputs().get(col).id(),"muted"),col+1,0);
                    for(int r=0;r<router.inputs().size();r++){
                        AudioPort input=router.inputs().get(r);table.add(label(input.id(),"muted"),0,r+1);
                        for(int c=0;c<router.outputs().size();c++){
                            AudioPort output=router.outputs().get(c);String link=input.id()+"->"+output.id();
                            CheckBox cell=new CheckBox();cell.setSelected(active.contains(link));cell.setAccessibleText(node.name()+" "+input.id()+" → "+output.id());
                            cell.setOnAction(event->setRouterLink(graph.id(),node.id(),link,cell.isSelected()));table.add(cell,c+1,r+1);
                        }
                    }
                    routerControls.getChildren().add(table);
                }
                routerControls.getChildren().add(label(active.size()+t(" активных связей"," active links"),"muted"));
                if(node instanceof MatrixNode||node instanceof SelectorNode||node instanceof MultiSelectorNode)routerControls.getChildren().add(actionButton(t("Добавить вход","Add input"),()->addRouterPort(graph.id(),node,"input")));
                if(node instanceof MatrixNode||node instanceof SwitchNode||node instanceof MultiSwitchNode)routerControls.getChildren().add(actionButton(t("Добавить выход","Add output"),()->addRouterPort(graph.id(),node,"output")));
                row.getChildren().add(routerControls);
            }
            if(node instanceof SourceNode||node instanceof OutputNode||node instanceof MixerNode){boolean pinned=graphNodePinned(graph.id(),node.id());row.getChildren().add(actionButton(pinned?t("Открепить от GNOME","Unpin from GNOME"):t("Закрепить в GNOME","Pin in GNOME"),()->toggleGraphNodePin(graph.id(),node,pinned)));}
            else{String quickParameter=quickControlParameter(node);if(!quickParameter.isBlank()){boolean pinned=quickControlPinned(graph.id(),node.id(),quickParameter);row.getChildren().add(actionButton(pinned?t("Открепить от GNOME","Unpin from GNOME"):t("Закрепить в GNOME","Pin in GNOME"),()->toggleGraphQuickControl(graph.id(),node,quickParameter,pinned)));}}
            Region space=new Region();HBox.setHgrow(space,Priority.ALWAYS);row.getChildren().add(space);row.getChildren().add(actionButton(t("Удалить","Delete"),()->removeGraphNode(graph.id(),node.id())));box.getChildren().add(row);
        }
        return box;
    }

    protected void createGraphSubgraph(AudioGraph graph){
        List<String> selected=graph.nodes().stream().map(GraphNode::id).filter(selectedSubgraphNodeIds::contains).toList();
        if(selected.size()<2){setNotice(t("Для подграфа выбери минимум две ноды.","Select at least two nodes for a subgraph."),true);return;}
        TextInputDialog dialog=new TextInputDialog(t("Новый подграф","New subgraph"));dialog.setTitle(t("Свернуть в подграф","Collapse into subgraph"));dialog.setHeaderText(t("Выбранные ноды останутся в графе, но будут показаны одним блоком.","Selected nodes stay in the graph but appear as one block."));styleDialog(dialog);
        dialog.showAndWait().map(String::trim).filter(name->!name.isBlank()).ifPresent(name->{updateRouteConfig(t("Создать подграф","Create subgraph"),config->GraphSubgraphLibrary.create(config,graph,name,selected));selected.forEach(selectedSubgraphNodeIds::remove);});
    }

    protected void setSubgraphCollapsed(String routeId,String subgraphId,boolean collapsed){
        updateRouteConfig(t(collapsed?"Свернуть подграф":"Развернуть подграф",collapsed?"Collapse subgraph":"Expand subgraph"),config->GraphSubgraphLibrary.setCollapsed(config,routeId,subgraphId,collapsed));
    }

    protected void deleteGraphSubgraph(String routeId,String subgraphId){
        updateRouteConfig(t("Удалить подграф","Delete subgraph"),config->GraphSubgraphLibrary.delete(config,routeId,subgraphId));
    }

    protected VBox graphConnectionCard(AudioGraph graph){
        VBox box=card(t("Связи", "Connections"));
        ComboBox<GraphNode> from=new ComboBox<>(),to=new ComboBox<>();ComboBox<AudioPort> fromPort=new ComboBox<>(),toPort=new ComboBox<>();from.getItems().setAll(graph.nodes().stream().filter(node->!node.outputs().isEmpty()).toList());to.getItems().setAll(graph.nodes().stream().filter(node->!node.inputs().isEmpty()).toList());
        StringConverter<GraphNode> converter=new StringConverter<>(){@Override public String toString(GraphNode node){return node==null?"":node.name();}@Override public GraphNode fromString(String value){return null;}};from.setConverter(converter);to.setConverter(converter);
        StringConverter<AudioPort> portConverter=new StringConverter<>(){@Override public String toString(AudioPort port){return port==null?"":port.id();}@Override public AudioPort fromString(String value){return null;}};fromPort.setConverter(portConverter);toPort.setConverter(portConverter);
        from.valueProperty().addListener((o,a,node)->{fromPort.getItems().setAll(node==null?List.of():node.outputs());if(!fromPort.getItems().isEmpty())fromPort.getSelectionModel().selectFirst();});
        to.valueProperty().addListener((o,a,node)->{toPort.getItems().setAll(node==null?List.of():node.inputs());if(node!=null){String free=node.inputs().stream().filter(port->graph.connections().stream().noneMatch(edge->edge.enabled()&&edge.toNode().equals(node.id())&&edge.toPort().equals(port.id()))).map(AudioPort::id).findFirst().orElse(node.inputs().getFirst().id());toPort.getSelectionModel().select(node.inputs().stream().filter(port->free.equals(port.id())).findFirst().orElse(null));}});
        if(!from.getItems().isEmpty())from.getSelectionModel().selectFirst();if(!to.getItems().isEmpty())to.getSelectionModel().selectLast();
        Button connect=actionButton(t("Соединить", "Connect"),()->connectGraphNodes(graph.id(),from.getValue(),fromPort.getValue(),to.getValue(),toPort.getValue()));connect.setDisable(from.getItems().isEmpty()||to.getItems().isEmpty());
        box.getChildren().addAll(new HBox(9,from,fromPort,label("→","muted"),to,toPort,connect));
        if(graph.connections().isEmpty())box.getChildren().add(label(t("Связей пока нет.","No connections yet."),"muted"));
        for(GraphConnection edge:graph.connections()){
            GraphNode source=graph.nodes().stream().filter(node->node.id().equals(edge.fromNode())).findFirst().orElse(null),target=graph.nodes().stream().filter(node->node.id().equals(edge.toNode())).findFirst().orElse(null);if(source==null||target==null)continue;
            CheckBox enabled=new CheckBox(source.name()+"."+edge.fromPort()+" → "+target.name()+"."+edge.toPort());enabled.setSelected(edge.enabled());enabled.setOnAction(e->setGraphConnectionEnabled(graph.id(),edge.id(),enabled.isSelected()));
            Slider gain=new Slider(0,1,edge.gain());gain.setPrefWidth(140);gain.setOnMouseReleased(e->setGraphConnectionGain(graph.id(),edge.id(),gain.getValue()));
            box.getChildren().add(new HBox(10,enabled,gain,actionButton(t("Удалить","Delete"),()->removeGraphConnection(graph.id(),edge.id()))));
        }
        return box;
    }

    protected VBox graphRouteGroupCard(AudioGraph graph,Map<String,Object> config){
        VBox box=card(t("Группа маршрутов", "Route group"));Map<String,Object> routeInfo=maps(config.get("route_library")).stream().filter(item->graph.id().equals(item.get("id"))).findFirst().orElse(Map.of());String profileId=string(routeInfo.get("profile_id"),GraphRouteLibrary.activeProfileId(config));List<Map<String,Object>> groups=GraphRouteLibrary.groupsForProfile(config,profileId);String groupId=string(routeInfo.get("group_id"),"");
        ComboBox<Map<String,Object>> picker=new ComboBox<>();picker.getItems().setAll(groups);picker.setConverter(new StringConverter<>(){@Override public String toString(Map<String,Object> group){return group==null?t("Без группы","No group"):string(group.get("name"),"");}@Override public Map<String,Object> fromString(String value){return null;}});if(!groupId.isBlank())groups.stream().filter(group->groupId.equals(group.get("id"))).findFirst().ifPresent(picker.getSelectionModel()::select);
        picker.setOnAction(event->{Map<String,Object> group=picker.getValue();updateRouteConfig(t("Переместить маршрут в группу","Move route to group"),candidate->maps(candidate.get("route_library")).stream().filter(item->graph.id().equals(item.get("id"))).findFirst().ifPresent(item->item.put("group_id",group==null?"":group.get("id"))));});
        box.getChildren().addAll(picker,actionButton(t("Создать группу","New group"),()->createGraphGroup()),actionButton(t("Переименовать группу","Rename group"),()->renameGraphGroup(groupId)),actionButton(t("Дублировать группу","Duplicate group"),()->duplicateSelectedGraphGroup(groupId)),actionButton(t("Удалить текущую группу","Delete current group"),()->deleteGraphGroup(groupId)));
        return box;
    }

    protected VBox graphSavedCopiesCard(Map<String,Object> config){
        VBox box=card(t("Сохранённые копии (не применяются)","Saved snapshots (inactive)"));String profileId=GraphRouteLibrary.activeProfileId(config);List<Map<String,Object>> copies=maps(config.get("saved_copies")).stream().filter(copy->!copy.containsKey("profile_id")||profileId.equals(copy.get("profile_id"))).toList();
        if(copies.isEmpty())box.getChildren().add(label(t("Копий ещё нет.","No snapshots yet."),"muted"));
        for(Map<String,Object> copy:copies){String id=string(copy.get("id"),"");HBox row=new HBox(10);row.setAlignment(Pos.CENTER_LEFT);row.getChildren().addAll(label(string(copy.get("name"),id)+" · "+t("неактивна","inactive"),""),actionButton(t("Восстановить как маршрут","Restore as route"),()->restoreGraphCopy(id)),actionButton(t("Удалить копию","Delete snapshot"),()->updateRouteConfig(t("Удалить копию","Delete snapshot"),candidate->GraphRouteLibrary.deleteSavedCopy(candidate,id))));box.getChildren().add(row);}
        return box;
    }

    protected void createGraphRoute(){TextInputDialog dialog=new TextInputDialog(t("Новый маршрут","New route"));dialog.setTitle(t("Создать маршрут","Create route"));dialog.setHeaderText(t("Новый граф маршрута","New route graph"));styleDialog(dialog);dialog.showAndWait().map(String::trim).filter(name->!name.isBlank()).ifPresent(name->{String id="route-"+UUID.randomUUID();AudioGraph graph=new AudioGraph(id,name,List.of(),List.of());updateRouteConfig(t("Создать маршрут ","Create route ")+name,config->{GraphRouteLibrary.saveRoute(config,graph,"");selectedGraphRouteId=id;});});}

    protected void duplicateSelectedGraphRoute(){String id=selectedGraphRouteId;if(id.isBlank())return;updateRouteConfig(t("Дублировать маршрут","Duplicate route"),config->{AudioGraph copy=GraphRouteLibrary.duplicateRoute(config,id,null);selectedGraphRouteId=copy.id();});}

    protected void renameSelectedGraphRoute(){String id=selectedGraphRouteId;if(id.isBlank())return;Map<String,Object> current=maps(store.snapshot().get("route_library")).stream().filter(route->id.equals(route.get("id"))).findFirst().orElse(null);if(current==null)return;TextInputDialog dialog=new TextInputDialog(string(current.get("name"),""));dialog.setTitle(t("Переименовать маршрут","Rename route"));dialog.setHeaderText(t("Имя маршрута","Route name"));styleDialog(dialog);dialog.showAndWait().map(String::trim).filter(name->!name.isBlank()).ifPresent(name->updateRouteConfig(t("Переименовать маршрут","Rename route"),config->GraphRouteLibrary.renameRoute(config,id,name)));}

    protected void deleteSelectedGraphRoute(){String id=selectedGraphRouteId;if(id.isBlank())return;Map<String,Object> config=store.snapshot();if(id.equals(config.get("active_route"))){setNotice(t("Сначала активируй другой маршрут; активный граф нельзя удалить, пока он выбран.","Activate another route first; the selected active graph cannot be deleted."),true);return;}updateRouteConfig(t("Удалить маршрут","Delete route"),candidate->GraphRouteLibrary.deleteRoute(candidate,id,null));selectedGraphRouteId="";}

    protected void activateSelectedGraphRoute(){String id=selectedGraphRouteId;if(id.isBlank())return;updateRouteConfig(t("Активировать маршрут","Activate route"),config->GraphRouteLibrary.activate(config,id));}

    protected void saveSelectedGraphCopy(){String id=selectedGraphRouteId;if(id.isBlank())return;updateRouteConfig(t("Сохранить копию","Save snapshot"),config->GraphRouteLibrary.saveInactiveCopy(config,id,null));}

    protected void addGraphNode(AudioGraph graph,String type){
        if(type==null)return;
        TextInputDialog nameDialog=new TextInputDialog(type);nameDialog.setTitle(t("Добавить ноду","Add node"));nameDialog.setHeaderText(t("Имя ноды","Node name"));styleDialog(nameDialog);
        nameDialog.showAndWait().map(String::trim).filter(name->!name.isBlank()).ifPresent(name->{
            String id=type+"-"+UUID.randomUUID();List<String> stereo=List.of("FL","FR");
            if("output".equals(type)){
                List<AudioNode> available=physicalSinks().stream().filter(node->node.channels()>=2).toList();if(available.isEmpty()){setNotice(t("Сейчас нет доступного стереовыхода.","No stereo output is currently available."),true);return;}
                List<String> outputLabels=available.stream().map(output->nodeDisplayName(output)+" · "+output.name()).toList();javafx.scene.control.ChoiceDialog<String> choice=new javafx.scene.control.ChoiceDialog<>(outputLabels.getFirst(),outputLabels);choice.setTitle(t("Выходная нода","Output node"));choice.setHeaderText(t("Выбери доступный выход","Choose an available output"));styleDialog(choice);
                choice.showAndWait().ifPresent(selectedLabel->{AudioNode output=available.get(outputLabels.indexOf(selectedLabel));updateRouteConfig(t("Добавить выходную ноду","Add output node"),config->{Map<String,Object> device=ensureDevice(config,output);GraphNode node=new OutputNode(id,name,string(device.get("id"),"system_default"),stereo);Map<String,Object> saved=maps(config.get("route_library")).stream().filter(route->graph.id().equals(route.get("id"))).findFirst().orElseThrow();AudioGraph old=GraphCodec.fromMap(saved);List<GraphNode> nodes=new ArrayList<>(old.nodes());nodes.add(node);GraphRouteLibrary.saveRoute(config,new AudioGraph(old.id(),old.name(),nodes,old.connections()),string(saved.get("group_id"),""));});});return;
            }
            if("source".equals(type)){
                TextInputDialog selector=new TextInputDialog("application:unassigned");selector.setTitle(t("Источник приложения","Application source"));selector.setHeaderText(t("application:<id>, process:<name> или application:unassigned","application:<id>, process:<name>, or application:unassigned"));styleDialog(selector);
                selector.showAndWait().map(String::trim).filter(value->!value.isBlank()).ifPresent(value->updateGraph(graph.id(),nodes->nodes.add(new SourceNode(id,name,value,stereo))));return;
            }
            GraphNode node=switch(type){case "gain"->new GainNode(id,name,stereo,1,false);case "gate"->new GateNode(id,name,stereo,true);case "switch"->new SwitchNode(id,name,stereo,List.of("out-1","out-2"),List.of());case "multi_switch"->new MultiSwitchNode(id,name,stereo,List.of("out-1","out-2"),List.of());case "selector"->new SelectorNode(id,name,stereo,List.of("in-1","in-2"),List.of());case "multi_selector"->new MultiSelectorNode(id,name,stereo,List.of("in-1","in-2"),List.of());case "matrix"->new MatrixNode(id,name,stereo,List.of("in-1","in-2"),List.of("out-1","out-2"),List.of());case "mixer"->new MixerNode(id,name,stereo,Map.of("in-a",1.0,"in-b",1.0));default->new FilterNode(id,name,stereo,"low_pass",true,Map.of("frequency_hz",100.0,"slope_db_oct",24.0));};
            updateGraph(graph.id(),nodes->nodes.add(node));
        });
    }

    protected void setRouterLink(String routeId,String nodeId,String link,boolean enabled){
        updateGraphNodeParameters(routeId,nodeId,parameters->{
            List<String> links=new ArrayList<>(stringList(parameters.get("active_links")));
            if(enabled){if(!Boolean.TRUE.equals(parameters.get("multiple")))links.clear();if(!links.contains(link))links.add(link);}
            else links.remove(link);
            parameters.put("active_links",links);
        });
    }

    protected void addRouterPort(String routeId,GraphNode node,String direction){
        String key="input".equals(direction)?"input_ports":"output_ports";
        updateGraphNodeParameters(routeId,node.id(),parameters->{
            List<String> ports=new ArrayList<>(stringList(parameters.get(key)));
            int number=ports.size()+1;String candidate;
            do{candidate=("input".equals(direction)?"in-":"out-")+number++;}while(ports.contains(candidate));
            ports.add(candidate);parameters.put(key,ports);
        });
    }

    protected String quickControlParameter(GraphNode node){if(node instanceof GainNode)return "gain";if(node instanceof GateNode)return "open";if(node instanceof FilterNode)return "enabled";if(node instanceof RouterNode&&Set.of("switch","multi_switch","selector","multi_selector","matrix").contains(node.type()))return "active_links";return "";}

    protected boolean quickControlPinned(String routeId,String nodeId,String parameter){return maps(store.snapshot().get("quick_controls")).stream().anyMatch(item->routeId.equals(item.get("route_id"))&&nodeId.equals(item.get("node_id"))&&parameter.equals(item.get("parameter_id")));}

    protected void toggleGraphQuickControl(String routeId,GraphNode node,String parameter,boolean pinned){
        if(pinned){updateRouteConfig(t("Открепить параметр от GNOME","Unpin graph parameter from GNOME"),config->{List<Map<String,Object>> matching=maps(config.get("quick_controls")).stream().filter(item->routeId.equals(item.get("route_id"))&&node.id().equals(item.get("node_id"))&&parameter.equals(item.get("parameter_id"))).toList();for(Map<String,Object> item:matching)QuickSettingsConfig.removeControl(config,routeId,node.id(),String.valueOf(item.get("id")),false);});return;}
        List<Map<String,Object>> existing=maps(store.snapshot().get("quick_controls"));
        TextField labelField=new TextField(node.name());TextField sectionField=new TextField("Audio Matrix");TextField orderField=new TextField(Integer.toString(existing.size()));
        Dialog<Map<String,Object>> dialog=new Dialog<>();if(getScene()!=null)dialog.initOwner(getScene().getWindow());dialog.setTitle(t("Закрепить быстрый параметр","Pin quick control"));dialog.setHeaderText(t("Настроить отображение в меню громкости GNOME","Configure how this control appears in GNOME's volume menu"));dialog.getDialogPane().setPrefWidth(520);
        VBox content=new VBox(8,label(t("Название","Label"),"muted"),labelField,label(t("Раздел","Section"),"muted"),sectionField,label(t("Порядок (меньше — выше)","Order (lower appears first)"),"muted"),orderField,label(t("Значение остаётся связано с параметром ноды; тип элемента определяется автоматически.","The value stays linked to the graph node; the control type is detected automatically."),"muted"));dialog.getDialogPane().setContent(content);dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);Button save=(Button)dialog.getDialogPane().lookupButton(ButtonType.OK);
        Runnable validate=()->{try{save.setDisable(labelField.getText().isBlank()||sectionField.getText().isBlank()||Integer.parseInt(orderField.getText())<0);}catch(NumberFormatException invalid){save.setDisable(true);}};labelField.textProperty().addListener((o,a,b)->validate.run());sectionField.textProperty().addListener((o,a,b)->validate.run());orderField.textProperty().addListener((o,a,b)->validate.run());validate.run();
        dialog.setResultConverter(button->{if(button!=ButtonType.OK)return null;try{return DefaultConfigMap.map("label",labelField.getText().trim(),"section",sectionField.getText().trim(),"order",Integer.parseInt(orderField.getText().trim()));}catch(NumberFormatException invalid){return null;}});
        dialog.showAndWait().ifPresentOrElse(settings->updateRouteConfig(t("Закрепить параметр в GNOME","Pin graph parameter in GNOME"),config->{List<Map<String,Object>> controls=maps(config.get("quick_controls"));Map<String,Object> group=QuickSettingsConfig.findOrCreateGroup(config,String.valueOf(settings.get("section")));int order=((Number)settings.get("order")).intValue();QuickSettingsConfig.pinNode(config,routeId,node.id(),String.valueOf(group.get("id")),order,"EXPANDED");String id="quick-"+UUID.randomUUID();controls.add(DefaultConfigMap.map("id",id,"route_id",routeId,"node_id",node.id(),"parameter_id",parameter,"label",settings.get("label"),"section",settings.get("section"),"group_id",group.get("id"),"order",order));QuickSettingsConfig.attachControl(config,routeId,node.id(),id);}),()->showPage(currentPage));
    }

    protected void updateGraph(String routeId,Consumer<List<GraphNode>> nodeChange){updateRouteConfig(t("Изменить граф","Edit graph"),config->{Map<String,Object> route=maps(config.get("route_library")).stream().filter(item->routeId.equals(item.get("id"))).findFirst().orElseThrow();AudioGraph old=GraphCodec.fromMap(route);List<GraphNode> nodes=new ArrayList<>(old.nodes());nodeChange.accept(nodes);AudioGraph changed=new AudioGraph(old.id(),old.name(),nodes,old.connections());GraphRouteLibrary.saveRoute(config,changed,string(route.get("group_id"),""));});}

    protected void saveGraph(String routeId,AudioGraph graph){updateRouteConfig(t("Сохранить граф","Save graph"),config->{Map<String,Object> route=maps(config.get("route_library")).stream().filter(item->routeId.equals(item.get("id"))).findFirst().orElseThrow();GraphRouteLibrary.saveRoute(config,graph,string(route.get("group_id"),""));});}

    protected void updateGraphNodeParameters(String routeId,String nodeId,Consumer<Map<String,Object>> change){Map<String,Object> config=store.snapshot();Map<String,Object> route=maps(config.get("route_library")).stream().filter(item->routeId.equals(item.get("id"))).findFirst().orElse(null);if(route==null)return;Map<String,Object> node=maps(route.get("nodes")).stream().filter(item->nodeId.equals(item.get("id"))).findFirst().orElse(null);if(node==null)return;Map<String,Object> parameters=map(node.get("parameters"));node.put("parameters",parameters);try{change.accept(parameters);AudioGraph graph=GraphCodec.fromMap(route);List<String> errors=GraphValidator.validate(graph);if(!errors.isEmpty())throw new IllegalArgumentException(String.join("; ",errors));saveGraph(routeId,graph);}catch(RuntimeException invalid){setNotice(t("Параметр ноды не сохранён: ","Node parameter was not saved: ")+invalid.getMessage(),true);showPage(Page.GRAPH);}}

    protected void removeGraphNode(String routeId,String nodeId){updateRouteConfig(t("Удалить ноду","Delete node"),config->{Map<String,Object> route=maps(config.get("route_library")).stream().filter(item->routeId.equals(item.get("id"))).findFirst().orElseThrow();maps(route.get("nodes")).removeIf(node->nodeId.equals(node.get("id")));maps(route.get("connections")).removeIf(edge->nodeId.equals(edge.get("from_node"))||nodeId.equals(edge.get("to_node")));QuickSettingsConfig.removeNode(config,routeId,nodeId);GraphSubgraphLibrary.removeNode(config,routeId,nodeId);});}

    protected void connectGraphNodes(String routeId,GraphNode source,AudioPort sourcePort,GraphNode target,AudioPort targetPort){if(source==null||target==null||sourcePort==null||targetPort==null)return;Map<String,Object> config=store.snapshot();Map<String,Object> route=maps(config.get("route_library")).stream().filter(item->routeId.equals(item.get("id"))).findFirst().orElse(null);if(route==null)return;AudioGraph old=GraphCodec.fromMap(route);GraphConnection edge=new GraphConnection("link-"+UUID.randomUUID(),source.id(),sourcePort.id(),target.id(),targetPort.id(),true,1);List<GraphConnection> edges=new ArrayList<>(old.connections());edges.add(edge);AudioGraph next=new AudioGraph(old.id(),old.name(),old.nodes(),edges);List<String> errors=GraphValidator.validate(next);if(!errors.isEmpty()){setNotice(String.join("; ",errors),true);showPage(Page.GRAPH);return;}saveGraph(routeId,next);}

    protected void setGraphConnectionEnabled(String routeId,String connectionId,boolean enabled){mutateGraphConnection(routeId,connectionId,edge->new GraphConnection(edge.id(),edge.fromNode(),edge.fromPort(),edge.toNode(),edge.toPort(),enabled,edge.gain()));}

    protected void setGraphConnectionGain(String routeId,String connectionId,double gain){mutateGraphConnection(routeId,connectionId,edge->new GraphConnection(edge.id(),edge.fromNode(),edge.fromPort(),edge.toNode(),edge.toPort(),edge.enabled(),gain));}

    protected void removeGraphConnection(String routeId,String connectionId){Map<String,Object> route=maps(store.snapshot().get("route_library")).stream().filter(item->routeId.equals(item.get("id"))).findFirst().orElse(null);if(route==null)return;AudioGraph old=GraphCodec.fromMap(route);saveGraph(routeId,new AudioGraph(old.id(),old.name(),old.nodes(),old.connections().stream().filter(edge->!connectionId.equals(edge.id())).toList()));}

    protected void mutateGraphConnection(String routeId,String connectionId,java.util.function.Function<GraphConnection,GraphConnection> mutate){Map<String,Object> route=maps(store.snapshot().get("route_library")).stream().filter(item->routeId.equals(item.get("id"))).findFirst().orElse(null);if(route==null)return;AudioGraph old=GraphCodec.fromMap(route);List<GraphConnection> edges=old.connections().stream().map(edge->connectionId.equals(edge.id())?mutate.apply(edge):edge).toList();AudioGraph next=new AudioGraph(old.id(),old.name(),old.nodes(),edges);List<String> errors=GraphValidator.validate(next);if(!errors.isEmpty()){setNotice(String.join("; ",errors),true);showPage(Page.GRAPH);return;}saveGraph(routeId,next);}

    protected void createGraphGroup(){TextInputDialog dialog=new TextInputDialog();dialog.setTitle(t("Создать группу","Create group"));dialog.setHeaderText(t("Название группы","Group name"));styleDialog(dialog);dialog.showAndWait().map(String::trim).filter(name->!name.isBlank()).ifPresent(name->updateRouteConfig(t("Создать группу","Create group"),config->GraphRouteLibrary.createGroup(config,name)));}

    protected void renameGraphGroup(String groupId){if(groupId.isBlank())return;Map<String,Object> group=maps(store.snapshot().get("route_groups")).stream().filter(item->groupId.equals(item.get("id"))).findFirst().orElse(null);if(group==null)return;TextInputDialog dialog=new TextInputDialog(string(group.get("name"),""));dialog.setTitle(t("Переименовать группу","Rename group"));dialog.setHeaderText(t("Название группы","Group name"));styleDialog(dialog);dialog.showAndWait().map(String::trim).filter(name->!name.isBlank()).ifPresent(name->updateRouteConfig(t("Переименовать группу","Rename group"),config->GraphRouteLibrary.renameGroup(config,groupId,name)));}
}
