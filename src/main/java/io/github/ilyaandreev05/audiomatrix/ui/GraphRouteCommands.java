package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import javafx.scene.control.TextInputDialog;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lifecycle commands of saved route graphs and of their groups. */
final class GraphRouteCommands {
    private GraphRouteCommands() {}

    static void createRoute(AppShellUiPart06 shell){TextInputDialog dialog=new TextInputDialog(shell.t("Новый маршрут","New route"));dialog.setTitle(shell.t("Создать маршрут","Create route"));dialog.setHeaderText(shell.t("Новый граф маршрута","New route graph"));shell.styleDialog(dialog);dialog.showAndWait().map(String::trim).filter(name->!name.isBlank()).ifPresent(name->{String id="route-"+UUID.randomUUID();AudioGraph graph=new AudioGraph(id,name,List.of(),List.of());shell.updateRouteConfig(shell.t("Создать маршрут ","Create route ")+name,config->{GraphRouteLibrary.saveRoute(config,graph,"");shell.selectedGraphRouteId=id;});});}

    static void duplicateRoute(AppShellUiPart06 shell){String id=shell.selectedGraphRouteId;if(id.isBlank())return;shell.updateRouteConfig(shell.t("Дублировать маршрут","Duplicate route"),config->{AudioGraph copy=GraphRouteLibrary.duplicateRoute(config,id,null);shell.selectedGraphRouteId=copy.id();});}

    static void renameRoute(AppShellUiPart06 shell){String id=shell.selectedGraphRouteId;if(id.isBlank())return;Map<String,Object> current=AppShellBase.maps(shell.store.snapshot().get("route_library")).stream().filter(route->id.equals(route.get("id"))).findFirst().orElse(null);if(current==null)return;TextInputDialog dialog=new TextInputDialog(AppShellBase.string(current.get("name"),""));dialog.setTitle(shell.t("Переименовать маршрут","Rename route"));dialog.setHeaderText(shell.t("Имя маршрута","Route name"));shell.styleDialog(dialog);dialog.showAndWait().map(String::trim).filter(name->!name.isBlank()).ifPresent(name->shell.updateRouteConfig(shell.t("Переименовать маршрут","Rename route"),config->GraphRouteLibrary.renameRoute(config,id,name)));}

    static void deleteRoute(AppShellUiPart06 shell){String id=shell.selectedGraphRouteId;if(id.isBlank())return;Map<String,Object> config=shell.store.snapshot();if(id.equals(config.get("active_route"))){shell.setNotice(shell.t("Сначала активируй другой маршрут; активный граф нельзя удалить, пока он выбран.","Activate another route first; the selected active graph cannot be deleted."),true);return;}shell.updateRouteConfig(shell.t("Удалить маршрут","Delete route"),candidate->GraphRouteLibrary.deleteRoute(candidate,id,null));shell.selectedGraphRouteId="";}

    static void activateRoute(AppShellUiPart06 shell){String id=shell.selectedGraphRouteId;if(id.isBlank())return;shell.updateRouteConfig(shell.t("Активировать маршрут","Activate route"),config->GraphRouteLibrary.activate(config,id));}

    static void saveCopy(AppShellUiPart06 shell){String id=shell.selectedGraphRouteId;if(id.isBlank())return;shell.updateRouteConfig(shell.t("Сохранить копию","Save snapshot"),config->GraphRouteLibrary.saveInactiveCopy(config,id,null));}

    static void createGroup(AppShellUiPart06 shell){TextInputDialog dialog=new TextInputDialog();dialog.setTitle(shell.t("Создать группу","Create group"));dialog.setHeaderText(shell.t("Название группы","Group name"));shell.styleDialog(dialog);dialog.showAndWait().map(String::trim).filter(name->!name.isBlank()).ifPresent(name->shell.updateRouteConfig(shell.t("Создать группу","Create group"),config->GraphRouteLibrary.createGroup(config,name)));}

    static void renameGroup(AppShellUiPart06 shell,String groupId){if(groupId.isBlank())return;Map<String,Object> group=AppShellBase.maps(shell.store.snapshot().get("route_groups")).stream().filter(item->groupId.equals(item.get("id"))).findFirst().orElse(null);if(group==null)return;TextInputDialog dialog=new TextInputDialog(AppShellBase.string(group.get("name"),""));dialog.setTitle(shell.t("Переименовать группу","Rename group"));dialog.setHeaderText(shell.t("Название группы","Group name"));shell.styleDialog(dialog);dialog.showAndWait().map(String::trim).filter(name->!name.isBlank()).ifPresent(name->shell.updateRouteConfig(shell.t("Переименовать группу","Rename group"),config->GraphRouteLibrary.renameGroup(config,groupId,name)));}
}
