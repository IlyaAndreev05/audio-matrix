package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.AudioPort;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import java.util.List;
import java.util.Map;

/** Cards of the graph page that present connections, route groups and saved copies. */
final class GraphRoutePanels {
    private GraphRoutePanels() {}

    static VBox connectionCard(AppShellUiPart06 shell,AudioGraph graph){
        VBox box=shell.card(shell.t("Связи", "Connections"));
        ComboBox<GraphNode> from=new ComboBox<>(),to=new ComboBox<>();ComboBox<AudioPort> fromPort=new ComboBox<>(),toPort=new ComboBox<>();from.getItems().setAll(graph.nodes().stream().filter(node->!node.outputs().isEmpty()).toList());to.getItems().setAll(graph.nodes().stream().filter(node->!node.inputs().isEmpty()).toList());
        StringConverter<GraphNode> converter=new StringConverter<>(){@Override public String toString(GraphNode node){return node==null?"":node.name();}@Override public GraphNode fromString(String value){return null;}};from.setConverter(converter);to.setConverter(converter);
        StringConverter<AudioPort> portConverter=new StringConverter<>(){@Override public String toString(AudioPort port){return port==null?"":port.id();}@Override public AudioPort fromString(String value){return null;}};fromPort.setConverter(portConverter);toPort.setConverter(portConverter);
        from.valueProperty().addListener((o,a,node)->{fromPort.getItems().setAll(node==null?List.of():node.outputs());if(!fromPort.getItems().isEmpty())fromPort.getSelectionModel().selectFirst();});
        to.valueProperty().addListener((o,a,node)->{toPort.getItems().setAll(node==null?List.of():node.inputs());if(node!=null){String free=node.inputs().stream().filter(port->graph.connections().stream().noneMatch(edge->edge.enabled()&&edge.toNode().equals(node.id())&&edge.toPort().equals(port.id()))).map(AudioPort::id).findFirst().orElse(node.inputs().getFirst().id());toPort.getSelectionModel().select(node.inputs().stream().filter(port->free.equals(port.id())).findFirst().orElse(null));}});
        if(!from.getItems().isEmpty())from.getSelectionModel().selectFirst();if(!to.getItems().isEmpty())to.getSelectionModel().selectLast();
        Button connect=shell.actionButton(shell.t("Соединить", "Connect"),()->shell.connectGraphNodes(graph.id(),from.getValue(),fromPort.getValue(),to.getValue(),toPort.getValue()));connect.setDisable(from.getItems().isEmpty()||to.getItems().isEmpty());
        box.getChildren().addAll(new HBox(9,from,fromPort,shell.label("→","muted"),to,toPort,connect));
        if(graph.connections().isEmpty())box.getChildren().add(shell.label(shell.t("Связей пока нет.","No connections yet."),"muted"));
        for(GraphConnection edge:graph.connections()){
            GraphNode source=graph.nodes().stream().filter(node->node.id().equals(edge.fromNode())).findFirst().orElse(null),target=graph.nodes().stream().filter(node->node.id().equals(edge.toNode())).findFirst().orElse(null);if(source==null||target==null)continue;
            CheckBox enabled=new CheckBox(source.name()+"."+edge.fromPort()+" → "+target.name()+"."+edge.toPort());enabled.setSelected(edge.enabled());enabled.setOnAction(e->shell.setGraphConnectionEnabled(graph.id(),edge.id(),enabled.isSelected()));
            box.getChildren().add(new HBox(10,enabled,shell.actionButton(shell.t("Удалить","Delete"),()->shell.removeGraphConnection(graph.id(),edge.id()))));
        }
        return box;
    }

    static VBox routeGroupCard(AppShellUiPart06 shell,AudioGraph graph,Map<String,Object> config){
        VBox box=shell.card(shell.t("Группа маршрутов", "Route group"));Map<String,Object> routeInfo=AppShellBase.maps(config.get("route_library")).stream().filter(item->graph.id().equals(item.get("id"))).findFirst().orElse(Map.of());String profileId=AppShellBase.string(routeInfo.get("profile_id"),GraphRouteLibrary.activeProfileId(config));List<Map<String,Object>> groups=GraphRouteLibrary.groupsForProfile(config,profileId);String groupId=AppShellBase.string(routeInfo.get("group_id"),"");
        ComboBox<Map<String,Object>> picker=new ComboBox<>();picker.getItems().setAll(groups);picker.setConverter(new StringConverter<>(){@Override public String toString(Map<String,Object> group){return group==null?shell.t("Без группы","No group"):AppShellBase.string(group.get("name"),"");}@Override public Map<String,Object> fromString(String value){return null;}});if(!groupId.isBlank())groups.stream().filter(group->groupId.equals(group.get("id"))).findFirst().ifPresent(picker.getSelectionModel()::select);
        picker.setOnAction(event->{Map<String,Object> group=picker.getValue();shell.updateRouteConfig(shell.t("Переместить маршрут в группу","Move route to group"),candidate->AppShellBase.maps(candidate.get("route_library")).stream().filter(item->graph.id().equals(item.get("id"))).findFirst().ifPresent(item->item.put("group_id",group==null?"":group.get("id"))));});
        box.getChildren().addAll(picker,shell.actionButton(shell.t("Создать группу","New group"),()->shell.createGraphGroup()),shell.actionButton(shell.t("Переименовать группу","Rename group"),()->shell.renameGraphGroup(groupId)),shell.actionButton(shell.t("Дублировать группу","Duplicate group"),()->shell.duplicateSelectedGraphGroup(groupId)),shell.actionButton(shell.t("Удалить текущую группу","Delete current group"),()->shell.deleteGraphGroup(groupId)));
        return box;
    }

    static VBox savedCopiesCard(AppShellUiPart06 shell,Map<String,Object> config){
        VBox box=shell.card(shell.t("Сохранённые копии (не применяются)","Saved snapshots (inactive)"));String profileId=GraphRouteLibrary.activeProfileId(config);List<Map<String,Object>> copies=AppShellBase.maps(config.get("saved_copies")).stream().filter(copy->!copy.containsKey("profile_id")||profileId.equals(copy.get("profile_id"))).toList();
        if(copies.isEmpty())box.getChildren().add(shell.label(shell.t("Копий ещё нет.","No snapshots yet."),"muted"));
        for(Map<String,Object> copy:copies){String id=AppShellBase.string(copy.get("id"),"");HBox row=new HBox(10);row.setAlignment(Pos.CENTER_LEFT);row.getChildren().addAll(shell.label(AppShellBase.string(copy.get("name"),id)+" · "+shell.t("неактивна","inactive"),""),shell.actionButton(shell.t("Восстановить как маршрут","Restore as route"),()->shell.restoreGraphCopy(id)),shell.actionButton(shell.t("Удалить копию","Delete snapshot"),()->shell.updateRouteConfig(shell.t("Удалить копию","Delete snapshot"),candidate->GraphRouteLibrary.deleteSavedCopy(candidate,id))));box.getChildren().add(row);}
        return box;
    }
}
