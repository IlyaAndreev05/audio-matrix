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

abstract class AppShellUiPart04GnomeEditor extends AppShellUiPart04GnomeControls {
    protected AppShellUiPart04GnomeEditor(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }
    protected VBox gnomePanelEditor(Map<String,Object> config,AudioGraph selectedGraph){
        VBox editor=uiDependencies.card().apply(uiDependencies.text().apply("Панель GNOME","GNOME panel"));
        var configSnapshot=store.snapshot();
        String activeRoute=string(configSnapshot.get("active_route"),"");
        DynamicInstanceRegistry.ReconcileResult activeRuntime=dynamicGraphState(activeRoute);
        Map<String,Object> projection=Map.of();
        String projectionError="";
        try{
            projection=GnomeControlBridge.stateProjection(snapshot,configSnapshot,configSnapshot.revision(),browserAdapters,activeRuntime);
        }catch(RuntimeException failure){
            projectionError=rootMessage(failure);
        }
        List<Map<String,Object>> previewGroups=maps(projection.get("quickGroups"));
        List<Map<String,Object>> previewPins=maps(projection.get("quickPins"));
        TreeView<GnomePanelItem> tree=gnomePanelTree(config,previewGroups,activeRuntime);
        tree.setPrefHeight(360);tree.setAccessibleText(t("Дерево закреплённых, сохранённых и созданных runtime узлов","Tree of pinned, saved and runtime-generated nodes"));
        VBox properties=new VBox(8);
        tree.getSelectionModel().selectedItemProperty().addListener((observable,oldValue,selected)->{
            GnomePanelItem item=selected==null?null:selected.getValue();
            if(item==null)return;
            selectedGnomePanelKey=item.key();
            properties.getChildren().setAll(gnomePanelProperties(item,config));
            if(item.node()!=null){
                if(!item.routeId().isBlank()&&!item.routeId().equals(selectedGraphRouteId)){
                    selectedGraphRouteId=item.routeId();selectedGraphNodeId=item.node().id();showPage(Page.GRAPH);
                }else if(!item.node().id().equals(selectedGraphNodeId)){
                    selectedGraphNodeId=item.node().id();refreshGraphPageRuntime();
                }
            }
        });
        List<TreeItem<GnomePanelItem>> matches=findPanelItems(tree.getRoot(),selectedGnomePanelKey);
        if(!matches.isEmpty())tree.getSelectionModel().select(matches.getFirst());
        else if(tree.getRoot()!=null&&!tree.getRoot().getChildren().isEmpty())tree.getSelectionModel().select(tree.getRoot().getChildren().getFirst());
        ScrollPane treeScroll=new ScrollPane(tree);treeScroll.setFitToWidth(true);treeScroll.setPrefViewportHeight(370);treeScroll.setMinWidth(360);
        VBox preview=gnomePanelPreview(previewGroups,previewPins,projectionError);
        ScrollPane previewScroll=new ScrollPane(preview);previewScroll.setFitToWidth(true);previewScroll.setPrefViewportHeight(370);previewScroll.setMinWidth(360);
        HBox panes=new HBox(10,treeScroll,previewScroll);HBox.setHgrow(treeScroll,Priority.ALWAYS);HBox.setHgrow(previewScroll,Priority.ALWAYS);
        editor.getChildren().addAll(label(t("Выбирай узел слева; порядок можно менять перетаскиванием или кнопками ↑/↓. Открепление и удаление группы не удаляют аудиограф.",
                "Select a node on the left; reorder by dragging or with the ↑/↓ buttons. Unpinning or deleting a group never deletes the audio graph."),"muted"),
                panes,properties);
        return editor;
    }
}
