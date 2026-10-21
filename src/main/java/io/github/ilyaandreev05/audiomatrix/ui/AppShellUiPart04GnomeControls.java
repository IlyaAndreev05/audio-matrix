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

abstract class AppShellUiPart04GnomeControls extends AppShellUiPart04GnomeTree {
    protected AppShellUiPart04GnomeControls(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }
    protected VBox gnomePanelProperties(GnomePanelItem item,Map<String,Object> config){
        VBox properties=new VBox(8);
        properties.getChildren().add(label(item.label(),"card-title"));
        if("GROUP".equals(item.kind())){
            Map<String,Object> group=QuickSettingsConfig.groups(config).stream()
                    .filter(candidate->item.groupId().equals(candidate.get("id"))).findFirst().orElse(null);
            if(group!=null){
                TextField name=field(string(group.get("name"),""),t("Имя группы GNOME","GNOME group name"));
                Spinner<Integer> order=new Spinner<>(0,9999,intValue(group.get("order"),0));order.setEditable(true);
                CheckBox collapsed=new CheckBox(t("Сворачивать группу при открытии","Collapse group on open"));
                collapsed.setSelected(Boolean.TRUE.equals(group.get("collapsed")));
                properties.getChildren().addAll(name,order,collapsed,
                        actionButton(t("Сохранить группу","Save group"),()->updateRouteConfig(t("Изменить группу GNOME","Edit GNOME group"),
                                candidate->QuickSettingsConfig.updateGroup(candidate,item.groupId(),name.getText().trim(),order.getValue(),collapsed.isSelected()))),
                        actionButton(t("Удалить группу","Delete group"),()->confirmRemoveQuickGroup(item.groupId(),string(group.get("name"),""))));
            }
            properties.getChildren().add(actionButton(t("Создать группу","Create group"),this::createQuickSettingsGroup));
            return properties;
        }
        if(item.composite()!=null){
            GraphSubgraph composite=item.composite();
            properties.getChildren().add(label(composite.nodeIds().size()+t(" дочерних узлов"," child nodes"),"muted"));
            if(composite.generatorId().isBlank()&&item.instanceKey().isBlank()){
                properties.getChildren().add(actionButton(composite.collapsed()?t("Развернуть Composite","Expand Composite"):t("Свернуть Composite","Collapse Composite"),
                        ()->setSubgraphCollapsed(item.routeId(),composite.id(),!composite.collapsed())));
                properties.getChildren().add(actionButton(t("Удалить Composite","Delete Composite"),()->deleteGraphSubgraph(item.routeId(),composite.id())));
            }else{
                boolean collapsed=compositeCollapsed(composite);
                properties.getChildren().add(actionButton(collapsed?t("Развернуть Composite","Expand Composite"):t("Свернуть Composite","Collapse Composite"),
                        ()->setGeneratedCompositeCollapsed(composite,!collapsed)));
                properties.getChildren().add(label(t("Группой управляет генератор; удаление, перемещение и удаление дочерних узлов отключены.",
                        "This group is generator-owned; delete, move and child-removal operations are disabled."),"muted"));
            }
        }
        if(!isQuickPinTarget(item)){
            properties.getChildren().addAll(label(t("Выбери сохранённый узел, Composite или runtime экземпляр, чтобы закрепить его.",
                    "Select a saved node, Composite, or runtime instance to pin it."),"muted"),
                    actionButton(t("Создать группу","Create group"),this::createQuickSettingsGroup));
            return properties;
        }
        String instanceKey=item.instanceKey();
        Map<String,Object> existing=findQuickPin(config,item.routeId(),item.nodeId(),instanceKey);
        List<Map<String,Object>> groups=QuickSettingsConfig.groups(config);
        ComboBox<Map<String,Object>> groupPicker=new ComboBox<>(FXCollections.observableArrayList(groups));
        groupPicker.setAccessibleText(t("Группа GNOME для закрепления","GNOME pin group"));
        groupPicker.setConverter(new StringConverter<>(){
            @Override public String toString(Map<String,Object> group){return group==null?"":string(group.get("name"),"");}
            @Override public Map<String,Object> fromString(String value){return null;}
        });
        String currentGroup=string(existing==null?null:existing.get("group_id"),defaultQuickPanelGroup(item));
        groups.stream().filter(group->currentGroup.equals(group.get("id"))).findFirst()
                .or(()->groups.stream().findFirst()).ifPresent(groupPicker.getSelectionModel()::select);
        int orderValue=existing==null?nextPinOrder(config,currentGroup):intValue(existing.get("order"),0);
        Spinner<Integer> order=new Spinner<>(0,9999,orderValue);order.setEditable(true);
        ComboBox<String> view=new ComboBox<>();view.getItems().setAll("DEFAULT","COMPACT","EXPANDED");
        view.setValue(string(existing==null?null:existing.get("view"),"EXPANDED"));
        TextField displayName=field(string(existing==null?null:existing.get("display_name"),item.label()),
                t("Переопределение имени","Display name override"));
        TextField icon=field(string(existing==null?null:existing.get("icon"),""),t("Иконка","Icon"));
        CheckBox showChildren=new CheckBox(t("Показывать дочерние узлы","Show children"));
        showChildren.setSelected(Boolean.TRUE.equals(existing==null?null:existing.get("show_children")));
        CheckBox showOffline=new CheckBox(t("Показывать, если экземпляр offline","Show while instance is offline"));
        showOffline.setSelected(Boolean.TRUE.equals(existing==null?null:existing.get("show_when_offline")));
        if(item.runtime()!=null){
            properties.getChildren().add(label(t("Runtime binding: ","Runtime binding: ")+item.runtime().confidence()
                    +" · "+(item.runtime().available()?t("доступен","available"):t("offline","offline")),"muted"));
        }
        boolean pinned=quickPanelPinActive(item,existing,config);
        Button save=actionButton(t("Сохранить свойства закрепления","Save pin properties"),()->{
            Map<String,Object> selectedGroup=groupPicker.getValue();
            if(selectedGroup==null){setNotice(t("Сначала создай группу GNOME.","Create a GNOME group first."),true);return;}
            String groupId=string(selectedGroup.get("id"),"");
            String name=displayName.getText().trim();
            updateRouteConfig(t("Изменить закрепление GNOME","Edit GNOME pin"),candidate->{
                QuickSettingsConfig.pinNode(candidate,item.routeId(),item.nodeId(),instanceKey,groupId,order.getValue(),view.getValue());
                QuickSettingsConfig.updatePin(candidate,item.routeId(),item.nodeId(),instanceKey,groupId,order.getValue(),view.getValue(),
                        name.isBlank()?null:name,icon.getText().isBlank()?null:icon.getText().trim(),showChildren.isSelected(),showOffline.isSelected());
            });
        });
        Button pin=actionButton(pinned?t("Открепить от GNOME","Unpin from GNOME"):t("Закрепить в GNOME","Pin in GNOME"),()->{
            if(pinned)updateRouteConfig(t("Открепить от GNOME","Unpin from GNOME"),
                    candidate->QuickSettingsConfig.unpinNode(candidate,item.routeId(),item.nodeId(),instanceKey));
            else save.fire();
        });
        HBox orderButtons=new HBox(6,
                actionButton(t("↑ Выше","↑ Move up"),()->moveQuickPin(string(existing==null?null:existing.get("id"),""),
                        string(groupPicker.getValue()==null?null:groupPicker.getValue().get("id"),""),Math.max(0,order.getValue()-1))),
                actionButton(t("↓ Ниже","↓ Move down"),()->moveQuickPin(string(existing==null?null:existing.get("id"),""),
                        string(groupPicker.getValue()==null?null:groupPicker.getValue().get("id"),""),order.getValue()+1)));
        orderButtons.getChildren().forEach(node->node.setDisable(existing==null));
        properties.getChildren().addAll(
                label(t("Группа","Group"),"muted"),groupPicker,label(t("Порядок","Order"),"muted"),order,
                label(t("Вид карточки","Card view"),"muted"),view,displayName,icon,showChildren,showOffline,
                new HBox(8,pin,save),orderButtons,actionButton(t("Создать группу","Create group"),this::createQuickSettingsGroup));
        return properties;
    }

    private boolean isQuickPinTarget(GnomePanelItem item){
        return Set.of("PIN","NODE","GENERATOR","INSTANCE","GENERATED_NODE","COMPOSITE","GENERATED_COMPOSITE").contains(item.kind())
                &&!item.routeId().isBlank()&&!item.nodeId().isBlank();
    }

    private Map<String,Object> findQuickPin(Map<String,Object> config,String routeId,String nodeId,String instanceKey){
        return QuickSettingsConfig.pins(config).stream().filter(pin->routeId.equals(pin.get("route_id"))
                &&nodeId.equals(pin.get("node_id"))&&instanceKey.equals(string(pin.get("instance_key"),"")))
                .findFirst().orElse(null);
    }
    private boolean quickPanelPinActive(GnomePanelItem item,Map<String,Object> existing,
                                        Map<String,Object> config){
        if(existing!=null)return true;
        if(item.instanceKey().isBlank()||item.runtime()==null)return false;
        Optional<Object> pinOverride=DynamicInstanceRegistry.instanceOverride(
                config,item.routeId(),item.nodeId(),item.instanceKey(),"pinned");
        if(pinOverride.isPresent())return Boolean.TRUE.equals(pinOverride.get());
        AudioGraph configured=GraphCodec.fromMap(GraphUiRoutes.route(config,item.routeId()));
        return configured.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).filter(node->item.nodeId().equals(node.id()))
                .findFirst().map(DynamicGeneratorNode::autoPin).orElse(false);
    }


    private String defaultQuickPanelGroup(GnomePanelItem item){
        if(item.node() instanceof OutputNode||item.node() instanceof DeviceSlotNode)return "outputs";
        if(item.node() instanceof SourceNode source&&EventSoundStudioSource.SELECTOR.equals(source.selector()))return "events";
        if(item.node() instanceof SourceNode||item.node() instanceof DynamicGeneratorNode)return "applications";
        return "routing";
    }

    private int nextPinOrder(Map<String,Object> config,String groupId){
        return QuickSettingsConfig.pins(config).stream().filter(pin->groupId.equals(pin.get("group_id")))
                .mapToInt(pin->intValue(pin.get("order"),-1)).max().orElse(-1)+1;
    }

    private void createQuickSettingsGroup(){
        TextInputDialog dialog=new TextInputDialog();dialog.setTitle(t("Создать группу GNOME","Create GNOME group"));
        dialog.setHeaderText(t("Имя новой группы меню громкости","Name the new volume-menu group"));styleDialog(dialog);
        dialog.showAndWait().map(String::trim).filter(name->!name.isBlank()).ifPresent(name->
                updateRouteConfig(t("Создать группу GNOME","Create GNOME group"),candidate->
                        QuickSettingsConfig.createGroup(candidate,name,QuickSettingsConfig.groups(candidate).size())));
    }

    private void confirmRemoveQuickGroup(String groupId,String name){
        Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,
                t("Закрепления и контролы будут перенесены в другую группу. Аудиограф не изменится. Удалить «","Pins and controls will move to another group. The audio graph will not change. Delete “")+name+"”?",
                ButtonType.OK,ButtonType.CANCEL);
        if(getScene()!=null)confirm.initOwner(getScene().getWindow());
        confirm.setHeaderText(t("Удалить группу GNOME","Delete GNOME group"));
        if(confirm.showAndWait().filter(ButtonType.OK::equals).isPresent())
            updateRouteConfig(t("Удалить группу GNOME","Delete GNOME group"),candidate->QuickSettingsConfig.removeGroup(candidate,groupId));
    }

    protected void moveQuickPin(String pinId,String destinationGroup,int destinationOrder){
        if(pinId==null||pinId.isBlank()||destinationGroup==null||destinationGroup.isBlank())return;
        updateRouteConfig(t("Изменить порядок закреплений GNOME","Reorder GNOME pins"),config->{
            List<Map<String,Object>> pins=QuickSettingsConfig.pins(config);
            Map<String,Object> moving=pins.stream().filter(pin->pinId.equals(pin.get("id"))).findFirst().orElse(null);
            if(moving==null)return;
            String sourceGroup=string(moving.get("group_id"),"");
            List<Map<String,Object>> sourcePins=pins.stream().filter(pin->sourceGroup.equals(pin.get("group_id"))&&pin!=moving)
                    .sorted(Comparator.comparingInt(pin->intValue(pin.get("order"),0))).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            List<Map<String,Object>> destinationPins=sourceGroup.equals(destinationGroup)?sourcePins:
                    pins.stream().filter(pin->destinationGroup.equals(pin.get("group_id"))&&pin!=moving)
                            .sorted(Comparator.comparingInt(pin->intValue(pin.get("order"),0))).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            int index=Math.max(0,Math.min(destinationOrder,destinationPins.size()));
            destinationPins.add(index,moving);
            if(!sourceGroup.equals(destinationGroup))
                for(int i=0;i<sourcePins.size();i++)persistQuickPinOrder(config,sourcePins.get(i),sourceGroup,i);
            for(int i=0;i<destinationPins.size();i++)persistQuickPinOrder(config,destinationPins.get(i),destinationGroup,i);
        });
    }

    private void persistQuickPinOrder(Map<String,Object> config,Map<String,Object> pin,String groupId,int order){
        QuickSettingsConfig.updatePin(config,string(pin.get("route_id"),""),string(pin.get("node_id"),""),
                string(pin.get("instance_key"),""),groupId,order,string(pin.get("view"),"DEFAULT"),
                string(pin.get("display_name"),""),string(pin.get("icon"),""),
                pin.get("show_children") instanceof Boolean children?children:null,
                pin.get("show_when_offline") instanceof Boolean offline?offline:null);
    }
}
