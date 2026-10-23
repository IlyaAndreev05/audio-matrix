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
                HBox groupOrder=new HBox(6,
                        actionButton(t("↑ Выше","↑ Move up"),()->updateRouteConfig(t("Переместить группу GNOME","Move GNOME group"),
                                candidate->QuickSettingsConfig.moveGroup(candidate,item.groupId(),Math.max(0,order.getValue()-1)))),
                        actionButton(t("↓ Ниже","↓ Move down"),()->updateRouteConfig(t("Переместить группу GNOME","Move GNOME group"),
                                candidate->QuickSettingsConfig.moveGroup(candidate,item.groupId(),order.getValue()+1))));
                properties.getChildren().addAll(label(t("Название группы","Group name"),"muted"),name,
                        label(t("Позиция","Position"),"muted"),order,groupOrder,collapsed,
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
            if(composite.generatorId()==null&&item.instanceKey().isBlank()){
                properties.getChildren().add(actionButton(composite.collapsed()?t("Развернуть составной блок","Expand composite"):t("Свернуть составной блок","Collapse composite"),
                        ()->setSubgraphCollapsed(item.routeId(),composite.id(),!composite.collapsed())));
                properties.getChildren().add(actionButton(t("Удалить составной блок","Delete composite"),()->deleteGraphSubgraph(item.routeId(),composite.id())));
            }else{
                boolean collapsed=compositeCollapsed(composite);
                properties.getChildren().add(actionButton(collapsed?t("Развернуть составной блок","Expand composite"):t("Свернуть составной блок","Collapse composite"),
                        ()->setGeneratedCompositeCollapsed(composite,!collapsed)));
                properties.getChildren().add(label(t("Группой управляет генератор; удаление, перемещение и удаление дочерних узлов отключены.",
                        "This group is generator-owned; delete, move and child-removal operations are disabled."),"muted"));
            }
        }
        if(!isQuickPinTarget(item)){
            properties.getChildren().addAll(label(t("Выбери узел, составной блок или созданный экземпляр, чтобы закрепить его.",
                    "Select a node, composite, or generated instance to pin it."),"muted"),
                    actionButton(t("Создать группу","Create group"),this::createQuickSettingsGroup));
            return properties;
        }
        String instanceKey=item.instanceKey();
        String childId=item.childId();
        Map<String,Object> existing=QuickSettingsConfig.findPin(config,item.routeId(),item.nodeId(),instanceKey,childId);
        Map<String,Object> presentation=new LinkedHashMap<>();
        if(item.runtime()!=null&&childId.isBlank())presentation.putAll(item.runtime().overrides());
        if(existing!=null)presentation.putAll(existing);
        List<Map<String,Object>> groups=QuickSettingsConfig.groups(config);
        ComboBox<Map<String,Object>> groupPicker=new ComboBox<>(FXCollections.observableArrayList(groups));
        groupPicker.setAccessibleText(t("Группа GNOME для закрепления","GNOME pin group"));
        groupPicker.setConverter(new StringConverter<>(){
            @Override public String toString(Map<String,Object> group){return group==null?"":string(group.get("name"),"");}
            @Override public Map<String,Object> fromString(String value){return null;}
        });
        String currentGroup=string(presentation.get("group_id"),defaultQuickPanelGroup(item));
        groups.stream().filter(group->currentGroup.equals(group.get("id"))).findFirst()
                .or(()->groups.stream().findFirst()).ifPresent(groupPicker.getSelectionModel()::select);
        int orderValue=presentation.get("order") instanceof Number value?Math.max(0,value.intValue()):nextPinOrder(config,currentGroup);
        Spinner<Integer> order=new Spinner<>(0,9999,orderValue);order.setEditable(true);
        ComboBox<String> view=new ComboBox<>();view.getItems().setAll("DEFAULT","COMPACT","EXPANDED");
        view.setConverter(new StringConverter<>(){
            @Override public String toString(String value){return switch(value==null?"DEFAULT":value){
                case "COMPACT"->t("Компактный","Compact");case "EXPANDED"->t("Полный","Full");default->t("По умолчанию","Default");};}
            @Override public String fromString(String value){return value;}
        });
        view.setValue(string(presentation.get("view"),"EXPANDED"));
        String defaultName=item.composite()!=null?item.composite().name():item.node()!=null?item.node().name():item.label();
        if(!instanceKey.isBlank()&&childId.isBlank()&&item.runtime()!=null){
            DynamicInstanceRegistry.ReconcileResult runtime=dynamicGraphState(item.routeId());
            if(runtime!=null)defaultName=runtime.generatedSubgraphs().stream()
                    .filter(group->group.id().equals(item.runtime().compositeId())).map(GraphSubgraph::name).findFirst().orElse(defaultName);
        }
        TextField displayName=field(string(presentation.get("display_name"),""),
                t("Название (пусто — ","Name (blank uses ")+defaultName+")");
        TextField icon=field(string(presentation.get("icon"),""),t("Имя значка темы GNOME (необязательно)","GNOME theme icon name (optional)"));
        CheckBox showChildren=new CheckBox(t("Показывать вложенные элементы","Show children"));
        showChildren.setSelected(!Boolean.FALSE.equals(presentation.get("show_children")));
        CheckBox showOffline=new CheckBox(t("Показывать при отсутствии","Show when disconnected"));
        showOffline.setSelected(Boolean.TRUE.equals(presentation.get("show_when_offline")));
        CheckBox expanded=new CheckBox(t("Раскрыть по умолчанию","Expand by default"));
        expanded.setSelected(presentation.get("expanded") instanceof Boolean value?value:!"COMPACT".equals(view.getValue()));
        if(item.runtime()!=null){
            properties.getChildren().add(label(t("Привязка: ","Binding: ")+item.runtime().confidence()
                    +" · "+(item.runtime().available()?t("доступно","available"):t("не подключено","not connected")),"muted"));
        }
        boolean pinned=quickPanelPinActive(item,existing,config);
        Button save=actionButton(t("Сохранить свойства закрепления","Save pin properties"),()->{
            Map<String,Object> selectedGroup=groupPicker.getValue();
            if(selectedGroup==null){setNotice(t("Сначала создай группу GNOME.","Create a GNOME group first."),true);return;}
            String groupId=string(selectedGroup.get("id"),"");
            String name=displayName.getText().trim();
            updateRouteConfig(t("Изменить закрепление GNOME","Edit GNOME pin"),candidate->{
                QuickSettingsConfig.updatePin(candidate,item.routeId(),item.nodeId(),instanceKey,childId,groupId,order.getValue(),view.getValue(),
                        name.isBlank()?null:name,icon.getText().isBlank()?null:icon.getText().trim(),
                        showChildren.isSelected(),showOffline.isSelected(),expanded.isSelected());
            });
        });
        Button pin=actionButton(pinned?t("Открепить от GNOME","Unpin from GNOME"):t("Закрепить в GNOME","Pin in GNOME"),()->{
            if(pinned)updateRouteConfig(t("Открепить от GNOME","Unpin from GNOME"),
                    candidate->QuickSettingsConfig.unpinNode(candidate,item.routeId(),item.nodeId(),instanceKey,childId));
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
                label(t("Вид карточки","Card view"),"muted"),view,
                label(t("Название","Name"),"muted"),displayName,label(t("Иконка","Icon"),"muted"),icon,
                expanded,showChildren,showOffline,
                new HBox(8,pin,save),orderButtons,actionButton(t("Создать группу","Create group"),this::createQuickSettingsGroup));
        return properties;
    }

    private boolean isQuickPinTarget(GnomePanelItem item){
        return Set.of("PIN","NODE","GENERATOR","INSTANCE","GENERATED_NODE","COMPOSITE","GENERATED_COMPOSITE").contains(item.kind())
                &&!item.routeId().isBlank()&&!item.nodeId().isBlank();
    }

    private boolean quickPanelPinActive(GnomePanelItem item,Map<String,Object> existing,
                                        Map<String,Object> config){
        if(existing!=null)return !Boolean.FALSE.equals(existing.get("pinned"));
        if(item.instanceKey().isBlank()||item.runtime()==null)return false;
        if(!item.childId().isBlank())return false;
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
        if(item.node() instanceof DynamicGeneratorNode generator)
            return generator.groupId().isBlank()?("outputs".equals(generator.registry())?"outputs":"applications"):generator.groupId();
        if(item.runtime()!=null&&item.runtime().runtimeBinding().containsKey("deviceId"))return "outputs";
        if(item.node() instanceof SourceNode)return "applications";
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
        updateRouteConfig(t("Изменить порядок закреплений GNOME","Reorder GNOME pins"),
                config->GnomeQuickPinOrder.move(config,pinId,destinationGroup,destinationOrder));
    }

}
