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

abstract class AppShellUiPart04GraphPage extends AppShellUiPart03 {
    protected String selectedGraphNodeId = "";
    protected final Map<String, GraphCanvasUiState> graphCanvasStates = new LinkedHashMap<>();
    protected VBox activeGraphPage;
    protected SplitPane activeGraphWorkspace;
    protected Node activeGnomePanelEditor;
    protected String selectedGnomePanelKey = "";
    protected final ConcurrentMap<String, Boolean> generatedCompositeCollapseOverrides = new ConcurrentHashMap<>();
    protected final UiDependencies uiDependencies;

    protected AppShellUiPart04GraphPage(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
        uiDependencies = new UiDependencies(this::t, this::label, this::card, this::actionButton, this::updateRouteConfig);
    }

    protected record UiDependencies(BiFunction<String, String, String> text,
                                    BiFunction<String, String, Label> label,
                                    Function<String, VBox> card,
                                    BiFunction<String, Runnable, Button> actionButton,
                                    BiConsumer<String, Consumer<Map<String, Object>>> updateRouteConfig) {}

    protected abstract VBox graphDiagnosticsCard(AudioGraph graph, Map<String, Object> config);
    protected abstract Node gnomePanelEditor(Map<String, Object> config, AudioGraph selectedGraph);
    protected abstract VBox gnomePanelProperties(GnomePanelItem item, Map<String, Object> config);
    protected abstract void moveQuickPin(String pinId, String destinationGroup, int destinationOrder);
    protected VBox graphPage() {
        Map<String,Object> config=store.snapshot();
        String profileId=GraphRouteLibrary.activeProfileId(config);
        List<Map<String,Object>> routes=new ArrayList<>(GraphRouteLibrary.routesForProfile(config,profileId));
        if(config.get("common_vibro_graph") instanceof Map<?,?>)routes.addFirst(GraphUiRoutes.route(config,GraphCommonVibro.COMMON_ROUTE_ID));
        VBox page=page(t("Профили и маршруты звука. Координаты и масштаб графа хранятся отдельно от аудиосхемы.",
                "Audio profiles and routes. Graph positions and zoom are stored separately from the audio graph."));
        activeGraphPage=page;
        activeGraphWorkspace=null;
        activeGnomePanelEditor=null;
        page.getChildren().add(graphProfileSelector(config));
        String chosen=routes.stream().anyMatch(route->selectedGraphRouteId.equals(route.get("id")))?selectedGraphRouteId:string(config.get("active_route"),"");
        Map<String,Object> selected=routes.stream().filter(route->chosen.equals(route.get("id"))).findFirst().orElse(routes.isEmpty()?null:routes.getFirst());
        if(selected!=null)selectedGraphRouteId=string(selected.get("id"),"");
        VBox routesSidebar=graphRouteSidebar(routes,selected,config);
        page.getChildren().add(routesSidebar);
        if(selected==null){
            VBox empty=card(t("Граф маршрута", "Route graph"));
            empty.getChildren().add(label(t("Создай маршрут, затем добавь источники, обработку и выходы.","Create a route, then add sources, processing and outputs."),"muted"));
            VBox inspector=card(t("Инспектор узла","Node inspector"));
            inspector.getChildren().add(label(t("Выбери маршрут, чтобы редактировать его узлы и привязки.","Choose a route to edit its nodes and bindings."),"muted"));
            SplitPane workspace=new SplitPane(graphNodePaletteCard(null),empty,inspector);workspace.setDividerPositions(.2,.7);workspace.setMinHeight(560);page.getChildren().add(workspace);
        }else{
            AudioGraph configuredGraph;
            AudioGraph graph;
            try{
                configuredGraph=GraphCodec.fromMap(selected);
                DynamicInstanceRegistry.ReconcileResult runtime=dynamicGraphState(configuredGraph.id());
                graph=runtime==null?configuredGraph:runtime.effectiveGraph();
            }catch(RuntimeException invalid){
                VBox error=card(t("Некорректный граф", "Invalid graph"));error.getChildren().add(label(invalid.getMessage(),"danger"));
                SplitPane workspace=new SplitPane(graphNodePaletteCard(null),error,card(t("Инспектор узла","Node inspector")));workspace.setDividerPositions(.2,.7);workspace.setMinHeight(560);page.getChildren().add(workspace);return page;
            }
            if(graph.nodes().stream().noneMatch(node->node.id().equals(selectedGraphNodeId)))selectedGraphNodeId=graph.nodes().isEmpty()?"":graph.nodes().getFirst().id();
            List<String> issues=GraphValidator.validate(graph);
            VBox status=graphStatusCard(graph,config,issues);
            VBox center=new VBox(10,status,graphCanvasCard(graph),graphConnectionCard(configuredGraph),graphMatrixCard(configuredGraph));
            VBox inspector=new VBox(10,graphNodeCard(graph),graphDiagnosticsCard(graph,config));
            ScrollPane inspectorScroll=new ScrollPane(inspector);inspectorScroll.setFitToWidth(true);inspectorScroll.setPrefViewportHeight(620);inspectorScroll.setMinWidth(280);
            SplitPane workspace=new SplitPane(graphNodePaletteCard(graph),center,inspectorScroll);workspace.setDividerPositions(.2,.69);workspace.setMinHeight(640);
            activeGraphWorkspace=workspace;
            page.getChildren().add(workspace);
            activeGnomePanelEditor=gnomePanelEditor(config,graph);
            page.getChildren().add(activeGnomePanelEditor);
            if(!GraphUiRoutes.common(graph.id()))page.getChildren().add(graphRouteGroupCard(configuredGraph,config));
        }
        if(!GraphUiRoutes.common(selectedGraphRouteId))page.getChildren().add(graphSavedCopiesCard(config));
        page.getChildren().add(routeHistoryCard());
        return page;
    }
    protected VBox graphStatusCard(AudioGraph graph,Map<String,Object> config,List<String> issues){
        VBox status=card(t("Состояние графа", "Graph status"));
        boolean common=GraphUiRoutes.common(graph.id());
        boolean active=common||graph.id().equals(config.get("active_route"));
        status.getChildren().addAll(label((common?t("Общий закреплённый Vibro · общий для рабочих пространств","Pinned common Vibro · shared across workspaces"):active?t("Активен в конфигурации", "Active in configuration"):t("Сохранённый маршрут", "Saved route"))+" · "+graph.nodes().size()+t(" нод · "," nodes · ")+graph.connections().size()+t(" связей"," links"),active?"accent":"muted"),
                label(t("Применение графа перенастроит PipeWire. Неподдерживаемые ноды будут отклонены до внесения изменений.",
                        "Applying this graph will reconfigure PipeWire. Unsupported nodes are rejected before any changes."),"warning"),
                actionButton(t("Применить активный граф к PipeWire","Apply active graph to PipeWire"),this::applyRoutes));
        issues.forEach(problem->status.getChildren().add(label(problem,"danger")));
        return status;
    }

    protected void refreshGraphPageRuntime(){
        if(currentPage!=Page.GRAPH||activeGraphWorkspace==null||activeGraphPage==null)return;
        Map<String,Object> config=store.snapshot();
        AudioGraph configured;
        AudioGraph graph;
        try{
            configured=GraphCodec.fromMap(GraphUiRoutes.route(config,selectedGraphRouteId));
            DynamicInstanceRegistry.ReconcileResult runtime=dynamicGraphState(configured.id());
            graph=runtime==null?configured:runtime.effectiveGraph();
        }catch(RuntimeException missing){
            return;
        }
        if(graph.nodes().stream().noneMatch(node->node.id().equals(selectedGraphNodeId)))
            selectedGraphNodeId=graph.nodes().isEmpty()?"":graph.nodes().getFirst().id();
        double[] dividers=activeGraphWorkspace.getDividerPositions();
        double inspectorH=0,inspectorV=0;
        if(activeGraphWorkspace.getItems().size()>2&&activeGraphWorkspace.getItems().get(2) instanceof ScrollPane oldInspector){
            inspectorH=oldInspector.getHvalue();inspectorV=oldInspector.getVvalue();
        }
        final double restoreH=inspectorH,restoreV=inspectorV;
        VBox center=new VBox(10,graphStatusCard(graph,config,GraphValidator.validate(graph)),graphCanvasCard(graph),
                graphConnectionCard(configured),graphMatrixCard(configured));
        VBox inspector=new VBox(10,graphNodeCard(graph),graphDiagnosticsCard(graph,config));
        ScrollPane inspectorScroll=new ScrollPane(inspector);inspectorScroll.setFitToWidth(true);inspectorScroll.setPrefViewportHeight(620);inspectorScroll.setMinWidth(280);
        activeGraphWorkspace.getItems().setAll(graphNodePaletteCard(graph),center,inspectorScroll);
        if(dividers.length==2)Platform.runLater(()->activeGraphWorkspace.setDividerPositions(dividers));
        Platform.runLater(()->{inspectorScroll.setHvalue(restoreH);inspectorScroll.setVvalue(restoreV);});
        if(activeGnomePanelEditor!=null){
            int index=activeGraphPage.getChildren().indexOf(activeGnomePanelEditor);
            if(index>=0){Node updated=gnomePanelEditor(config,graph);activeGraphPage.getChildren().set(index,updated);activeGnomePanelEditor=updated;}
        }
    }

    private HBox graphProfileSelector(Map<String,Object> config){
        HBox row=new HBox(10);row.setAlignment(Pos.CENTER_LEFT);row.getStyleClass().add("card");
        ComboBox<Map<String,Object>> profiles=new ComboBox<>(FXCollections.observableArrayList(maps(config.get("profiles"))));
        profiles.setAccessibleText(t("Активный профиль графа","Active graph profile"));
        profiles.setConverter(new StringConverter<>(){
            @Override public String toString(Map<String,Object> profile){return profile==null?"":string(profile.get("name"),string(profile.get("id"),""));}
            @Override public Map<String,Object> fromString(String value){return null;}
        });
        String active=GraphRouteLibrary.activeProfileId(config);
        profiles.getItems().stream().filter(profile->active.equals(profile.get("id"))).findFirst().ifPresent(profiles.getSelectionModel()::select);
        profiles.setOnAction(event->{Map<String,Object> profile=profiles.getValue();if(profile==null||active.equals(profile.get("id")))return;
            selectedGraphRouteId="";selectedGraphNodeId="";
            uiDependencies.updateRouteConfig().accept(uiDependencies.text().apply("Активировать профиль","Activate profile"),candidate->GraphProfileLibrary.activateProfile(candidate,string(profile.get("id"),"")));
        });
        row.getChildren().addAll(uiDependencies.label().apply(uiDependencies.text().apply("Профиль","Profile"),"card-title"),profiles);
        return row;
    }

    protected VBox graphRouteSidebar(List<Map<String,Object>> routes,Map<String,Object> selected,Map<String,Object> config){
        VBox box=card(t("Маршрут графа","Graph route"));
        ComboBox<Map<String,Object>> routePicker=new ComboBox<>(FXCollections.observableArrayList(routes));routePicker.setMaxWidth(Double.MAX_VALUE);
        routePicker.setAccessibleText(t("Выбранный маршрут графа","Selected graph route"));
        routePicker.setConverter(new StringConverter<>(){
            @Override public String toString(Map<String,Object> route){if(route==null)return "";String id=string(route.get("id"),"");String marker=GraphUiRoutes.common(id)?t(" · общий"," · common"):id.equals(config.get("active_route"))?" · ●":"";return string(route.get("name"),id)+marker;}
            @Override public Map<String,Object> fromString(String value){return null;}
        });
        if(selected!=null)routePicker.getSelectionModel().select(selected);
        routePicker.setOnAction(event->{Map<String,Object> route=routePicker.getValue();if(route!=null&&!route.get("id").equals(selectedGraphRouteId)){selectedGraphRouteId=string(route.get("id"),"");selectedGraphNodeId="";showPage(Page.GRAPH);}});
        FlowPane actions=new FlowPane(6,6);actions.setPrefWrapLength(700);
        actions.getChildren().add(actionButton(t("Создать маршрут","New route"),this::createGraphRoute));
        if(selected!=null&&!GraphUiRoutes.common(string(selected.get("id"),"")))actions.getChildren().addAll(
                actionButton(t("Дублировать","Duplicate"),this::duplicateSelectedGraphRoute),
                actionButton(t("Переименовать","Rename"),this::renameSelectedGraphRoute),actionButton(t("Активировать","Activate"),this::activateSelectedGraphRoute),
                actionButton(t("Сохранить копию","Save snapshot"),this::saveSelectedGraphCopy),actionButton(t("Удалить","Delete"),this::deleteSelectedGraphRoute));
        box.getChildren().addAll(new HBox(9,label(t("Маршрут","Route"),"muted"),routePicker),actions);
        return box;
    }

    protected VBox graphNodePaletteCard(AudioGraph graph){
        VBox palette=card(t("Узлы","Node palette"));
        if(graph==null){palette.getChildren().add(label(t("Сначала выбери маршрут.","Choose a route first."),"muted"));return palette;}
        if(!GraphUiRoutes.common(graph.id())){
            try{
                Set<String> composable=GraphCodec.fromMap(GraphUiRoutes.route(store.snapshot(),graph.id())).nodes().stream()
                        .filter(node->!(node instanceof DynamicGeneratorNode)).map(GraphNode::id).collect(java.util.stream.Collectors.toSet());
                selectedSubgraphNodeIds.retainAll(composable);
            }catch(RuntimeException invalid){selectedSubgraphNodeIds.clear();}
        }else selectedSubgraphNodeIds.clear();
        FlowPane buttons=new FlowPane(6,6);
        buttons.getChildren().addAll(
                actionButton(t("Источник","Source"),()->addGraphNode(graph,"source")),
                actionButton("Volume",()->addGraphNode(graph,"gain")),
                actionButton(t("Merge","Merge"),()->addGraphNode(graph,"mixer")),
                actionButton("MultiSwitch",()->addGraphNode(graph,"switch")),
                actionButton(t("Выход","Output"),()->addGraphNode(graph,"output")),
                actionButton(t("Генератор","Generator"),()->addGraphNode(graph,"dynamic_generator")));
        Button composite=actionButton(t("Создать Composite","Create Composite"),()->createGraphSubgraph(graph));
        composite.setDisable(GraphUiRoutes.common(graph.id())||selectedSubgraphNodeIds.size()<2);
        buttons.getChildren().add(composite);
        palette.getChildren().addAll(label(t("Перетаскивай элементы на схему или выбери действие; операции маршрута находятся отдельно.","Drag elements onto the graph or choose an action; route operations are separate."),"muted"),buttons);
        TreeItem<GraphNode> root=new TreeItem<>(null);root.setExpanded(true);
        graph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .forEach(node->root.getChildren().add(new TreeItem<>(node)));
        TreeView<GraphNode> generators=new TreeView<>(root);generators.setShowRoot(false);generators.setAccessibleText(t("Дерево генераторов и экземпляров","Generator and instance tree"));generators.setPrefHeight(130);
        generators.setCellFactory(tree->new TreeCell<>(){
                @Override protected void updateItem(GraphNode node,boolean empty){super.updateItem(node,empty);setText(empty||node==null?null:node.name()+" · "+GraphNodeLabels.typeName(node,AppShellUiPart04GraphPage.this::t));}
        });
        generators.getSelectionModel().selectedItemProperty().addListener((obs,old,item)->{GraphNode node=item==null?null:item.getValue();if(node!=null){selectedGraphNodeId=node.id();showPage(Page.GRAPH);}});
        palette.getChildren().addAll(label(t("Генераторы","Generators"),"card-title"),generators);
        return palette;
    }
}
