package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeQuickControlParameter;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GateNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.RouterNode;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pins of graph node parameters as quick controls of the GNOME volume menu. */
final class GraphQuickControlPinning {
    private GraphQuickControlPinning() {}

    static String parameter(AppShellUiPart06 shell,GraphNode node){if(node instanceof GainNode)return "gain";if(node instanceof GateNode)return "open";if(node instanceof FilterNode)return "enabled";if(node instanceof RouterNode&&Set.of("switch","selector","multi_selector","matrix").contains(node.type()))return "active_links";return "";}

    private static String defaultSection(AppShellUiPart06 shell,GraphNode node){String id=node instanceof FilterNode?"effects":"routing";return QuickSettingsConfig.groups(shell.store.snapshot()).stream().filter(group->id.equals(group.get("id"))).map(group->AppShellBase.string(group.get("name"),"")).filter(name->!name.isBlank()).findFirst().orElse(shell.t(node instanceof FilterNode?"Обработка звука":"Коммутация и управление",node instanceof FilterNode?"Effects":"Routing and control"));}

    static boolean pinned(AppShellUiPart06 shell,String routeId,String nodeId,String parameter){return AppShellBase.maps(shell.store.snapshot().get("quick_controls")).stream().anyMatch(item->routeId.equals(item.get("route_id"))&&nodeId.equals(item.get("node_id"))&&parameter.equals(item.get("parameter_id")));}

    static void toggle(AppShellUiPart06 shell,String routeId,GraphNode node,String parameter,boolean pinned){
        if(pinned){shell.updateRouteConfig(shell.t("Открепить параметр от GNOME","Unpin graph parameter from GNOME"),config->{List<Map<String,Object>> matching=AppShellBase.maps(config.get("quick_controls")).stream().filter(item->routeId.equals(item.get("route_id"))&&node.id().equals(item.get("node_id"))&&parameter.equals(item.get("parameter_id"))).toList();for(Map<String,Object> item:matching)QuickSettingsConfig.removeControl(config,routeId,node.id(),String.valueOf(item.get("id")),false);});return;}
        List<Map<String,Object>> existing=AppShellBase.maps(shell.store.snapshot().get("quick_controls"));
        GnomeQuickControlParameter.Definition definition=GnomeQuickControlParameter.definition(node,parameter);String defaultLabel=node.name()+(definition==null?"":" · "+shell.t(definition.russianLabel(),definition.englishLabel()));TextField labelField=new TextField(defaultLabel);TextField sectionField=new TextField(defaultSection(shell,node));TextField orderField=new TextField(Integer.toString(existing.size()));
        Dialog<Map<String,Object>> dialog=new Dialog<>();if(shell.getScene()!=null)dialog.initOwner(shell.getScene().getWindow());dialog.setTitle(shell.t("Закрепить быстрый параметр","Pin quick control"));dialog.setHeaderText(shell.t("Настроить отображение в меню громкости GNOME","Configure how this control appears in GNOME's volume menu"));dialog.getDialogPane().setPrefWidth(520);
        VBox content=new VBox(8,shell.label(shell.t("Название","Label"),"muted"),labelField,shell.label(shell.t("Раздел","Section"),"muted"),sectionField,shell.label(shell.t("Порядок (меньше — выше)","Order (lower appears first)"),"muted"),orderField,shell.label(shell.t("Значение остаётся связано с параметром ноды; тип элемента определяется автоматически.","The value stays linked to the graph node; the control type is detected automatically."),"muted"));dialog.getDialogPane().setContent(content);dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);Button save=(Button)dialog.getDialogPane().lookupButton(ButtonType.OK);
        Runnable validate=()->{try{save.setDisable(labelField.getText().isBlank()||sectionField.getText().isBlank()||Integer.parseInt(orderField.getText())<0);}catch(NumberFormatException invalid){save.setDisable(true);}};labelField.textProperty().addListener((o,a,b)->validate.run());sectionField.textProperty().addListener((o,a,b)->validate.run());orderField.textProperty().addListener((o,a,b)->validate.run());validate.run();
        dialog.setResultConverter(button->{if(button!=ButtonType.OK)return null;try{return AppShellBase.DefaultConfigMap.map("label",labelField.getText().trim(),"section",sectionField.getText().trim(),"order",Integer.parseInt(orderField.getText().trim()));}catch(NumberFormatException invalid){return null;}});
        dialog.showAndWait().ifPresentOrElse(settings->shell.updateRouteConfig(shell.t("Закрепить параметр в GNOME","Pin graph parameter in GNOME"),config->{List<Map<String,Object>> controls=AppShellBase.maps(config.get("quick_controls"));Map<String,Object> group=QuickSettingsConfig.findOrCreateGroup(config,String.valueOf(settings.get("section")));int order=((Number)settings.get("order")).intValue();QuickSettingsConfig.pinNode(config,routeId,node.id(),String.valueOf(group.get("id")),order,"EXPANDED");String id="quick-"+UUID.randomUUID();controls.add(AppShellBase.DefaultConfigMap.map("id",id,"route_id",routeId,"node_id",node.id(),"parameter_id",parameter,"label",settings.get("label"),"section",settings.get("section"),"group_id",group.get("id"),"order",order));QuickSettingsConfig.attachControl(config,routeId,node.id(),id);}),()->shell.showPage(shell.currentPage));
    }
}
