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
import java.util.function.Consumer;

abstract class AppShellUiPart04 extends AppShellUiPart03 {
    protected String selectedGraphNodeId = "";
    private final Map<String,GraphCanvasUiState> graphCanvasStates=new LinkedHashMap<>();
    private VBox activeGraphPage;
    private SplitPane activeGraphWorkspace;
    private Node activeGnomePanelEditor;
    private String selectedGnomePanelKey="";
    private final ConcurrentMap<String,Boolean> generatedCompositeCollapseOverrides=new ConcurrentHashMap<>();

    protected AppShellUiPart04(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

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
            if(!common)page.getChildren().add(graphRouteGroupCard(configuredGraph,config));
        }
        if(!GraphUiRoutes.common(selectedGraphRouteId))page.getChildren().add(graphSavedCopiesCard(config));
        page.getChildren().add(routeHistoryCard());
        return page;
    }
    private VBox graphStatusCard(AudioGraph graph,Map<String,Object> config,List<String> issues){
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
        VBox center=new VBox(10,graphStatusCard(graph,config,GraphValidator.validate(graph)),graphCanvasCard(graph),
                graphConnectionCard(configured),graphMatrixCard(configured));
        VBox inspector=new VBox(10,graphNodeCard(graph),graphDiagnosticsCard(graph,config));
        ScrollPane inspectorScroll=new ScrollPane(inspector);inspectorScroll.setFitToWidth(true);inspectorScroll.setPrefViewportHeight(620);inspectorScroll.setMinWidth(280);
        activeGraphWorkspace.getItems().setAll(graphNodePaletteCard(graph),center,inspectorScroll);
        if(dividers.length==2)Platform.runLater(()->activeGraphWorkspace.setDividerPositions(dividers));
        Platform.runLater(()->{inspectorScroll.setHvalue(inspectorH);inspectorScroll.setVvalue(inspectorV);});
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
            updateRouteConfig(t("Активировать профиль","Activate profile"),candidate->GraphProfileLibrary.activateProfile(candidate,string(profile.get("id"),"")));
        });
        row.getChildren().addAll(label(t("Профиль","Profile"),"card-title"),profiles);
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

    private VBox graphNodePaletteCard(AudioGraph graph){
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
            @Override protected void updateItem(GraphNode node,boolean empty){super.updateItem(node,empty);setText(empty||node==null?null:node.name()+" · "+GraphNodeLabels.typeName(node,AppShellUiPart04.this::t));}
        });
        generators.getSelectionModel().selectedItemProperty().addListener((obs,old,item)->{GraphNode node=item==null?null:item.getValue();if(node!=null){selectedGraphNodeId=node.id();showPage(Page.GRAPH);}});
        palette.getChildren().addAll(label(t("Генераторы","Generators"),"card-title"),generators);
        return palette;
    }
    private VBox graphDiagnosticsCard(AudioGraph graph,Map<String,Object> config){
        VBox diagnosticsCard=card(t("Диагностика выбранного узла","Selected node diagnostics"));
        GraphNode selected=graph.nodes().stream().filter(node->selectedGraphNodeId.equals(node.id())).findFirst().orElse(null);
        diagnosticsCard.getChildren().add(label(t("Логический узел","Logical node"),"card-title"));
        diagnosticsCard.getChildren().add(label(selected==null?t("Узел не выбран или отсутствует в графе.","No node is selected or the node is absent from the graph.")
                :selected.name()+" · "+GraphNodeLabels.typeName(selected,this::t)+" · "+selected.id(),selected==null?"warning":"muted"));
        diagnosticsCard.getChildren().add(label(t("Стабильная идентичность / runtime binding","Stable identity / runtime binding"),"card-title"));
        diagnosticsCard.getChildren().add(label(selected==null?t("Недоступно: логический узел отсутствует.","Unavailable: logical node is absent.")
                :graphBindingDiagnostic(selected,config),"muted"));

        boolean selectedInConfig=GraphUiRoutes.common(graph.id())||graph.id().equals(config.get("active_route"));
        diagnosticsCard.getChildren().add(label(t("Выбранный маршрут в конфигурации","Configured selected route"),"card-title"));
        diagnosticsCard.getChildren().add(label(graph.name()+" · "+graph.id()
                +" · "+(selectedInConfig?t("выбран в конфигурации","selected in configuration"):t("сохранён, но не выбран","saved but not selected")),"muted"));
        diagnosticsCard.getChildren().add(label(t("Фактически наблюдаемые связи по stable owner ID","Actually observed links by stable owner ID"),"card-title"));
        List<PipeWireRouteTypes.BranchDiagnostic> routeBranches=routeBranchDiagnostics(graph);
        if(routeBranches.isEmpty())diagnosticsCard.getChildren().add(label(t("Недоступно: runtime не опубликовал диагностику для ID этого маршрута или его связей.",
                "Unavailable: runtime has not published diagnostics for this route ID or its edge IDs."),"warning"));
        for(PipeWireRouteTypes.BranchDiagnostic branch:routeBranches)
            diagnosticsCard.getChildren().add(branchDiagnosticRow(branch));
        List<PipeWireRouteTypes.BranchDiagnostic> selectedBranches=selected==null?List.of():nodeBranchDiagnostics(graph,selected);
        if(selected!=null){
            diagnosticsCard.getChildren().add(label(t("Связи выбранного узла","Selected node links"),"card-title"));
            if(selectedBranches.isEmpty())diagnosticsCard.getChildren().add(label(t("Для стабильного ID этого узла и его рёбер нет runtime отчёта; linked status недоступен.",
                    "No runtime report exists for this node’s stable ID or its edges; linked status is unavailable."),"warning"));
            selectedBranches.forEach(branch->diagnosticsCard.getChildren().add(branchDiagnosticRow(branch)));
        }

        diagnosticsCard.getChildren().add(label(t("Измерение сигнала","Signal measurement"),"card-title"));
        diagnosticsCard.getChildren().add(label(t("Не измерено: для этого узла отдельный meter не подключён. Доступность, binding и наличие link не считаются активностью сигнала.",
                "Not measured: no node-specific meter is attached. Availability, binding, and link presence are not signal activity."),"muted"));
        diagnosticsCard.getChildren().add(label(t("Причина сбоя backend","Backend failure cause"),"card-title"));
        List<String> branchErrors=selectedBranches.stream().map(PipeWireRouteTypes.BranchDiagnostic::error)
                .filter(error->error!=null&&!error.isBlank()).toList();
        if(branchErrors.isEmpty())diagnosticsCard.getChildren().add(label(backend.backendMessage(),"muted"));
        else branchErrors.forEach(error->diagnosticsCard.getChildren().add(label(error,"danger")));
        var report=routeManager.lastReport();
        if(report!=null&&!report.warnings().isEmpty()){
            diagnosticsCard.getChildren().add(label(t("Предупреждения route manager (глобальные):","Route-manager warnings (global):"),"warning"));
            report.warnings().forEach(warning->diagnosticsCard.getChildren().add(label(warning,"danger")));
        }

        return diagnosticsCard;
    }
    private List<PipeWireRouteTypes.BranchDiagnostic> routeBranchDiagnostics(AudioGraph graph){
        Set<String> owners=new LinkedHashSet<>();owners.add(graph.id());
        graph.connections().forEach(edge->owners.add(edge.id()));
        for(GraphNode node:graph.nodes()){
            owners.add(node.id());
            if(node instanceof SourceNode source)owners.add(source.selector());
            if(node instanceof DeviceSlotNode slot)stableDeviceFingerprint(slot.deviceId()).ifPresent(owners::add);
            if(node instanceof OutputNode output)stableDeviceFingerprint(output.deviceId()).ifPresent(owners::add);
        }
        return routeManager.branchDiagnostics().stream().filter(branch->owners.contains(branch.ownerId())).toList();
    }

    private List<PipeWireRouteTypes.BranchDiagnostic> nodeBranchDiagnostics(AudioGraph graph,GraphNode node){
        Set<String> owners=new LinkedHashSet<>();owners.add(node.id());
        graph.connections().stream().filter(edge->node.id().equals(edge.fromNode())||node.id().equals(edge.toNode()))
                .map(GraphConnection::id).forEach(owners::add);
        if(node instanceof SourceNode source)owners.add(source.selector());
        if(node instanceof DeviceSlotNode slot)stableDeviceFingerprint(slot.deviceId()).ifPresent(owners::add);
        if(node instanceof OutputNode output)stableDeviceFingerprint(output.deviceId()).ifPresent(owners::add);
        return routeManager.branchDiagnostics().stream().filter(branch->owners.contains(branch.ownerId())).toList();
    }

    private java.util.Optional<String> stableDeviceFingerprint(String deviceId){
        if(deviceId==null||deviceId.isBlank())return java.util.Optional.empty();
        Map<String,Object> device=maps(store.snapshot().get("devices")).stream()
                .filter(candidate->deviceId.equals(candidate.get("id"))).findFirst().orElse(null);
        if(device==null)return java.util.Optional.empty();
        Map<String,Object> identity=map(device.get("identity"));
        String fingerprint=string(identity.get("fingerprint"),"");
        return fingerprint.isBlank()?java.util.Optional.empty():java.util.Optional.of(fingerprint);
    }

    private Node branchDiagnosticRow(PipeWireRouteTypes.BranchDiagnostic branch){
        long desired=branch.desiredLinks().size();
        long matched=branch.desiredLinks().stream().filter(branch.observedLinks()::contains).count();
        VBox row=new VBox(3);row.getStyleClass().add("node-card");
        row.getChildren().add(label(branch.ownerId()+" · "+branch.kind()
                +" · "+(branch.selected()?t("selected","selected"):t("not selected","not selected"))
                +" · "+(branch.available()?t("available","available"):t("unavailable","unavailable"))
                +" · "+(branch.processAlive()?t("process alive","process alive"):t("process stopped","process stopped")),"muted"));
        row.getChildren().add(label(t("Наблюдаемые связи ","Observed links ")+matched+"/"+desired
                +" · "+branch.observedLinks().size()+t(" owned links seen"," owned links seen"),"muted"));
        if(branch.error()!=null&&!branch.error().isBlank())row.getChildren().add(label(branch.error(),"danger"));
        return row;
    }
    protected boolean isSavedGraphNode(String routeId,String nodeId){
        try{
            return maps(GraphUiRoutes.route(store.snapshot(),routeId).get("nodes")).stream()
                    .anyMatch(node->nodeId.equals(node.get("id")));
        }catch(RuntimeException missing){return false;}
    }

    protected DynamicChildAddress dynamicChildAddress(String routeId,String childNodeId){
        DynamicInstanceRegistry.ReconcileResult runtime=dynamicGraphState(routeId);
        if(runtime==null)return null;
        return runtime.instances().values().stream()
                .filter(instance->instance.nodeIds().containsValue(childNodeId))
                .findFirst().map(instance->new DynamicChildAddress(instance.generatorId(),instance.instanceKey(),instance)).orElse(null);
    }
    protected boolean browserTabInstance(String routeId,DynamicChildAddress address){
        DynamicInstanceRegistry.ReconcileResult runtime=dynamicGraphState(routeId);
        if(runtime==null)return false;
        return runtime.configuredGraph().nodes().stream().filter(node->address.generatorId().equals(node.id()))
                .filter(DynamicGeneratorNode.class::isInstance).map(DynamicGeneratorNode.class::cast)
                .anyMatch(generator->"browser_tabs".equals(generator.registry()));
    }

    protected boolean independentTabVolumeAvailable(DynamicChildAddress address){
        Map<String,Object> binding=address.instance().runtimeBinding();
        String captureKey=string(binding.get("captureStreamKey"),"");
        return !captureKey.isBlank()&&"captured".equals(binding.get("volumeCapability"))
                &&("audio-matrix-chrome-"+captureKey).equals(binding.get("nodeName"))
                &&!string(binding.get("applicationRuleId"),"").isBlank();
    }

    private String graphBindingDiagnostic(GraphNode node,Map<String,Object> config){
        if(node instanceof DeviceSlotNode slot)return deviceBindingDiagnostic(slot.deviceId(),config);
        if(node instanceof OutputNode output)return deviceBindingDiagnostic(output.deviceId(),config);
        if(node instanceof DynamicGeneratorNode generator)
            return t("Генератор · selector ","Generator · selector ")+generator.selector()
                    +t("; конкретные экземпляры принадлежат runtime registry.","; concrete instances are owned by the runtime registry.");
        DynamicChildAddress runtimeAddress=dynamicChildAddress(selectedGraphRouteId,node.id());
        if(runtimeAddress!=null){
            Map<String,Object> binding=runtimeAddress.instance().runtimeBinding();
            if(browserTabInstance(selectedGraphRouteId,runtimeAddress)){
                String captureKey=string(binding.get("captureStreamKey"),"");
                String nodeName=string(binding.get("nodeName"),"");
                boolean exactBinding=!captureKey.isBlank()&&("audio-matrix-chrome-"+captureKey).equals(nodeName);
                String base=t("Chrome tab runtime binding · instance ","Chrome tab runtime binding · instance ")
                        +runtimeAddress.instanceKey()+" · "+(exactBinding?t("точная capture binding","exact capture binding"):t("capture binding отсутствует","capture binding unavailable"));
                return independentTabVolumeAvailable(runtimeAddress)?base+t(" · independentTabVolume доступен"," · independentTabVolume available")
                        :base+t(" · управление громкостью вкладки недоступно"," · tab volume control unavailable");
            }
            String rule=string(binding.get("applicationRuleId"),"");
            return t("Runtime instance ","Runtime instance ")+runtimeAddress.instanceKey()
                    +t(" · generator "," · generator ")+runtimeAddress.generatorId()
                    +(rule.isBlank()?"":" · "+t("application rule ","application rule ")+rule)
                    +" · "+(runtimeAddress.instance().available()?t("binding доступен","binding available"):t("binding недоступен","binding unavailable"));
        }
        if(node instanceof SourceNode source){
            String selector=source.selector();
            if(selector.startsWith("application:")){
                String applicationId=selector.substring("application:".length());
                long matches=snapshot.streams().stream().filter(stream->applicationId.equals(stream.applicationId())).count();
                return t("Сохранённый selector ","Saved selector ")+selector+" · "+matches+t(" подходящих потоков в текущем snapshot."," matching streams in the current snapshot.");
            }
            return t("Сохранённый selector ","Saved selector ")+selector+t("; отдельная аппаратная identity не используется.","; no hardware identity is used.");
        }
        if(node instanceof GainNode gain&&!gain.endpointNodeId().isBlank())
            return t("Привязан к узлу графа ","Bound to graph node ")+gain.endpointNodeId();
        return t("Обработка графа; отдельная hardware identity для этого узла не требуется.",
                "Graph processor; a separate hardware identity is not required for this node.");
    }

    private String deviceBindingDiagnostic(String deviceId,Map<String,Object> config){
        if(deviceId==null||deviceId.isBlank())
            return t("Устройство не привязано; это не подтверждает наличие сигнала.","Device is unbound; this does not indicate signal activity.");
        Map<String,Object> device=maps(config.get("devices")).stream()
                .filter(candidate->deviceId.equals(candidate.get("id"))).findFirst().orElse(null);
        if(device==null)return t("Сохранённая ссылка устройства: ","Saved device reference: ")+deviceId
                +t("; каталог не содержит стабильную identity.","; the catalogue has no stable identity.");
        DeviceDiscovery.Resolution resolution=DeviceDiscovery.resolve(physicalSinks(),device);
        String stable=string(device.get("name"),deviceId);
        String result=stable+" · "+resolution.state();
        if(!resolution.message().isBlank())result+=" · "+resolution.message();
        if(resolution.node()!=null)result+=" · "+resolution.node().description();
        return result;
    }


    private VBox gnomePanelEditor(Map<String,Object> config,AudioGraph selectedGraph){
        VBox editor=card(t("Панель GNOME","GNOME panel"));
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

    private VBox gnomePanelPreview(List<Map<String,Object>> groups,List<Map<String,Object>> pins,String projectionError){
        VBox preview=card(t("Предпросмотр GNOME","GNOME live preview"));
        preview.getChildren().add(label(t("Показываются те же quickGroups и quickPins, которые публикуются в bridge state.",
                "This uses the same quickGroups and quickPins published in bridge state."),"muted"));
        if(!projectionError.isBlank())preview.getChildren().add(label(t("Предпросмотр недоступен: ","Preview unavailable: ")+projectionError,"danger"));
        if(groups.isEmpty()){
            preview.getChildren().add(label(t("В проекции пока нет групп.","No groups are available in the state projection yet."),"muted"));
            return preview;
        }
        List<Map<String,Object>> ordered=new ArrayList<>(groups);
        ordered.sort(Comparator.comparingInt(group->intValue(group.get("order"),0)));
        for(Map<String,Object> group:ordered){
            String groupId=string(group.get("id"),"");
            List<Map<String,Object>> groupPins=pins.stream().filter(pin->groupId.equals(pin.get("groupId")))
                    .sorted(Comparator.comparingInt(pin->intValue(pin.get("order"),0))).toList();
            VBox groupCard=new VBox(6);groupCard.getStyleClass().add("card");
            groupCard.getChildren().add(label(string(group.get("name"),groupId)
                    +" · "+groupPins.size()+t(" элементов"," items"),"card-title"));
            if(Boolean.TRUE.equals(group.get("collapsed"))){
                groupCard.getChildren().add(label(t("Группа свёрнута в меню GNOME.","This group is collapsed in GNOME."),"muted"));
            }else if(groupPins.isEmpty()){
                groupCard.getChildren().add(label(t("Нет отображаемых закреплений.","No projected pins are visible."),"muted"));
            }else{
                for(Map<String,Object> pin:groupPins)groupCard.getChildren().add(gnomePanelPreviewPin(pin));
            }
            preview.getChildren().add(groupCard);
        }
        return preview;
    }

    private VBox gnomePanelPreviewPin(Map<String,Object> pin){
        VBox row=new VBox(3);row.getStyleClass().add("node-card");
        String name=string(pin.get("displayName"),string(pin.get("name"),string(pin.get("nodeId"),"")));
        String instanceKey=string(pin.get("instanceKey"),"");
        if(!instanceKey.isBlank())name+=" · "+instanceKey;
        String icon=string(pin.get("icon"),"");
        row.getChildren().add(label((icon.isBlank()?"":icon+" · ")+name+" · "+string(pin.get("nodeType"),""),"card-title"));
        Map<String,Object> gain=map(pin.get("gain"));
        if(!gain.isEmpty())row.getChildren().add(label(t("Volume ","Volume ")+percent(gain.get("volume"))+" · "+(Boolean.TRUE.equals(gain.get("muted"))?t("mute","muted"):t("включён","unmuted")),"muted"));
        Map<String,Object> sourceGain=map(pin.get("sourceGain"));
        if(!sourceGain.isEmpty())row.getChildren().add(label(t("Уровень источника ","Source gain ")+percent(sourceGain.get("volume"))+" · "+(Boolean.TRUE.equals(sourceGain.get("muted"))?t("mute","muted"):t("включён","unmuted")),"muted"));
        Map<String,Object> output=map(pin.get("output"));
        if(!output.isEmpty()){
            String outputStatus=string(output.get("state"),string(output.get("status"),""));
            String reason=string(output.get("reason"),"");
            row.getChildren().add(label(t("Выход ","Output ")+outputStatus+(reason.isBlank()?"":" · "+reason),"muted"));
        }
        List<Map<String,Object>> applications=maps(pin.get("applications"));
        for(Map<String,Object> application:applications){
            String applicationName=string(application.get("name"),string(application.get("applicationName"),""));
            if(applicationName.isBlank())continue;
            row.getChildren().add(label(applicationName+" · "+percent(application.get("volume"))+" · "
                    +(Boolean.TRUE.equals(application.get("muted"))?t("mute","muted"):t("включён","unmuted")),"muted"));
        }
        for(Map<String,Object> input:maps(pin.get("mixerInputs"))){
            row.getChildren().add(label(string(input.get("name"),string(input.get("id"),""))
                    +" · "+percent(input.get("gain"))+" · "+(Boolean.TRUE.equals(input.get("muted"))?t("mute","muted"):t("включён","unmuted")),"muted"));
        }
        List<Map<String,Object>> controls=maps(pin.get("controls"));
        for(Map<String,Object> control:controls){
            String labelText=string(control.get("label"),string(control.get("parameterId"),""));
            Object value=control.get("value");
            row.getChildren().add(label(labelText+(value==null?"":" · "+value),"muted"));
        }
        return row;
    }

    private String percent(Object value){
        if(!(value instanceof Number number)||!Double.isFinite(number.doubleValue()))return t("не измерено","not measured");
        return String.format(Locale.ROOT,"%.0f%%",Math.max(0,Math.min(1,number.doubleValue()))*100);
    }

    private int intValue(Object value,int fallback){
        return value instanceof Number number?number.intValue():fallback;
    }
    private TreeView<GnomePanelItem> gnomePanelTree(Map<String,Object> config,List<Map<String,Object>> projectedGroups,
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
            List<Map<String,Object>> groupPins=pins.stream().filter(pin->groupId.equals(pin.get("group_id")))
                    .sorted(Comparator.comparingInt(pin->intValue(pin.get("order"),0))).toList();
            for(Map<String,Object> pin:groupPins){
                String routeId=string(pin.get("route_id"),""),nodeId=string(pin.get("node_id"),"");
                String instanceKey=string(pin.get("instance_key"),"");
                AudioGraph graph=graphByRoute.get(routeId);
                GraphNode node=graph==null?null:graph.nodes().stream().filter(candidate->nodeId.equals(candidate.id())).findFirst().orElse(null);
                GraphSubgraph composite=routeById.containsKey(routeId)?GraphSubgraphLibrary.forRoute(config,routeId).stream()
                        .filter(candidate->nodeId.equals(candidate.id())).findFirst().orElse(null):null;
                DynamicInstanceRegistry.RuntimeInstance instance=findRuntimeInstance(activeRuntime,routeId,nodeId,instanceKey);
                if(node==null&&instance!=null&&activeRuntime!=null)
                    node=activeRuntime.effectiveGraph().nodes().stream().filter(candidate->nodeId.equals(candidate.id())).findFirst().orElse(null);
                String fallback=composite==null?(node==null?nodeId:node.name()):composite.name();
                String display=string(pin.get("display_name"),fallback);
                if(!instanceKey.isBlank())display+=" · "+instanceKey;
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
                boolean generated=!group.generatorId().isBlank();
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
                    if(source==null||!"PIN".equals(source.kind())||source.pin()==null)return;
                    String pinId=string(source.pin().get("id"),"");if(pinId.isBlank())return;
                    Dragboard dragboard=startDragAndDrop(TransferMode.MOVE);
                    ClipboardContent content=new ClipboardContent();content.putString(pinId);dragboard.setContent(content);event.consume();
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
                        String targetGroup="GROUP".equals(target.kind())?target.groupId():target.groupId();
                        int order="PIN".equals(target.kind())?intValue(target.pin().get("order"),0):Integer.MAX_VALUE;
                        moveQuickPin(event.getDragboard().getString(),targetGroup,order);completed=true;
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
            String state=instance.available()?t("доступен","available"):t("offline","offline");
            TreeItem<GnomePanelItem> instanceItem=new TreeItem<>(new GnomePanelItem("INSTANCE",
                    instanceKey+" · "+state+" · "+instance.confidence(),routeId,generator.id(),instanceKey,"",null,generator,null,instance));
            boolean pinned=pins.stream().anyMatch(pin->routeId.equals(pin.get("route_id"))
                    &&generator.id().equals(pin.get("node_id"))&&instanceKey.equals(pin.get("instance_key")));
            if(pinned)instanceItem.setValue(new GnomePanelItem("INSTANCE",instanceItem.getValue().label()+" · ✓",
                    routeId,generator.id(),instanceKey,"",null,generator,null,instance));
            for(String childId:instance.nodeIds().values()){
                GraphNode child=effective.nodes().stream().filter(candidate->childId.equals(candidate.id())).findFirst().orElse(null);
                if(child==null)continue;
                instanceItem.getChildren().add(new TreeItem<>(new GnomePanelItem("GENERATED_NODE",
                        child.name()+" · "+GraphNodeLabels.typeName(child,this::t),routeId,generator.id(),instanceKey,"",null,child,null,instance)));
            }
            GraphSubgraph generated=runtime.generatedSubgraphs().stream()
                    .filter(group->instance.compositeId().equals(group.id())).findFirst().orElse(null);
            if(generated!=null)instanceItem.getChildren().add(new TreeItem<>(new GnomePanelItem("GENERATED_COMPOSITE",
                    generated.name()+" · "+generated.nodeIds().size()+t(" нод · runtime"," nodes · runtime"),
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

    private List<TreeItem<GnomePanelItem>> findPanelItems(TreeItem<GnomePanelItem> root,String key){
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
    private VBox gnomePanelProperties(GnomePanelItem item,Map<String,Object> config){
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

    private void moveQuickPin(String pinId,String destinationGroup,int destinationOrder){
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
    protected VBox graphCanvasCard(AudioGraph graph){
        VBox box=card(t("Схема маршрута","Route canvas"));
        box.getChildren().add(label(t("Перетаскивай ноды и соединяй порты. Схема располагается автоматически только для нод без сохранённых координат; «Упорядочить» явно пересчитает все позиции.",
                "Drag nodes and connect ports. Automatic layout applies only to nodes without saved coordinates; “Arrange” explicitly recalculates every position."),"muted"));
        GraphCanvasLayout.Layout defaults=GraphCanvasLayout.arrange(graph,this::graphCanvasNodeHeight);
        Map<String,Object> allLayouts=map(store.snapshot().get("graph_ui_layout"));
        Map<String,Object> routeLayout=map(allLayouts.get(graph.id()));
        Map<String,double[]> initial=new LinkedHashMap<>();
        double canvasWidth=680,canvasHeight=390;
        for(GraphNode node:graph.nodes()){
            GraphCanvasLayout.Position fallback=defaults.positions().getOrDefault(node.id(),
                    new GraphCanvasLayout.Position(GraphCanvasLayout.LEFT_MARGIN,GraphCanvasLayout.TOP_MARGIN));
            Map<String,Object> saved=map(routeLayout.get(node.id()));
            double x=coordinate(saved.get("x"),fallback.x()),y=coordinate(saved.get("y"),fallback.y());
            initial.put(node.id(),new double[]{x,y});
            canvasWidth=Math.max(canvasWidth,x+GraphCanvasLayout.NODE_WIDTH+GraphCanvasLayout.LEFT_MARGIN);
            canvasHeight=Math.max(canvasHeight,y+graphCanvasNodeHeight(node)+GraphCanvasLayout.TOP_MARGIN);
        }
        persistInitialGraphPositions(graph.id(),graph,defaults,routeLayout);
        final double contentWidth=canvasWidth,contentHeight=canvasHeight;
        ScrollPane[] viewportHolder={null};
        Runnable[] persistScaleHolder={()->{}};
        GraphCanvasUiState state=graphCanvasStates.computeIfAbsent(graph.id(),ignored->new GraphCanvasUiState());
        Map<String,Object> savedScales=map(store.snapshot().get("graph_ui_scale"));
        double initialScale=Math.max(.25,Math.min(2,number(savedScales.get(graph.id()),state.scale)));
        HBox zoomRow=new HBox(8);zoomRow.setAlignment(Pos.CENTER_LEFT);
        Slider zoom=new Slider(.25,2,initialScale);zoom.setPrefWidth(180);zoom.setBlockIncrement(.1);zoom.setMajorTickUnit(.25);zoom.setSnapToTicks(true);
        Label zoomLabel=label(Math.round(initialScale*100)+"%","muted");zoom.setAccessibleText(t("Масштаб схемы маршрута","Route canvas zoom"));
        zoomRow.getChildren().addAll(label(t("Масштаб","Zoom"),"muted"),zoom,zoomLabel,
                actionButton(t("Вписать граф","Fit graph"),()->{fitGraphCanvas(zoom,viewportHolder[0],contentWidth,contentHeight);persistScaleHolder[0].run();}),
                actionButton(t("Упорядочить","Arrange"),()->arrangeGraphCanvas(graph)));
        Pane canvas=new Pane();canvas.setMinSize(contentWidth,contentHeight);canvas.setPrefSize(contentWidth,contentHeight);canvas.setStyle("-fx-background-color: #181a1f; -fx-background-radius: 10;");
        Scale canvasScale=new Scale(initialScale,initialScale,0,0);canvas.getTransforms().add(canvasScale);
        Pane scaledContent=new Pane(canvas);scaledContent.setMinSize(contentWidth*initialScale,contentHeight*initialScale);scaledContent.setPrefSize(contentWidth*initialScale,contentHeight*initialScale);
        ScrollPane viewport=new ScrollPane(scaledContent);viewport.setPannable(true);viewport.setFitToWidth(false);viewport.setFitToHeight(false);viewport.setPrefViewportWidth(Math.min(1150,contentWidth));viewport.setPrefViewportHeight(Math.min(510,contentHeight));
        viewportHolder[0]=viewport;
        zoom.valueProperty().addListener((obs,old,value)->{
            double scale=value.doubleValue();state.scale=scale;canvasScale.setX(scale);canvasScale.setY(scale);
            scaledContent.setMinSize(contentWidth*scale,contentHeight*scale);scaledContent.setPrefSize(contentWidth*scale,contentHeight*scale);
            zoomLabel.setText(Math.round(scale*100)+"%");
        });
        Runnable persistScale=()->{
            double scale=Math.rint(zoom.getValue()*100.0)/100.0;state.scale=scale;
            store.updateAsync(config->{Map<String,Object>scales=map(config.get("graph_ui_scale"));config.put("graph_ui_scale",scales);scales.put(graph.id(),scale);})
                    .whenComplete((ignored,error)->Platform.runLater(()->{if(error!=null)setNotice(t("Масштаб не сохранён: ","Zoom could not be saved: ")+rootMessage(error),true);}));
        };
        persistScaleHolder[0]=persistScale;
        zoom.setOnMouseReleased(event->persistScale.run());zoom.setOnKeyReleased(event->persistScale.run());
        viewport.hvalueProperty().addListener((obs,old,value)->state.hValue=value.doubleValue());
        viewport.vvalueProperty().addListener((obs,old,value)->state.vValue=value.doubleValue());
        Platform.runLater(()->{viewport.setHvalue(state.hValue);viewport.setVvalue(state.vValue);});
        Map<String,Node> views=new LinkedHashMap<>();Map<String,Line> lines=new LinkedHashMap<>();
        Map<String,GraphNode> nodes=new LinkedHashMap<>();graph.nodes().forEach(node->nodes.put(node.id(),node));
        Map<String,GraphSubgraph> allGroupsById=new LinkedHashMap<>();
        if(!GraphUiRoutes.common(graph.id()))
            GraphSubgraphLibrary.forRoute(store.snapshot(),graph.id()).forEach(group->allGroupsById.put(group.id(),group));
        DynamicInstanceRegistry.ReconcileResult graphRuntime=dynamicGraphState(graph.id());
        if(graphRuntime!=null)graphRuntime.generatedSubgraphs().forEach(group->allGroupsById.putIfAbsent(group.id(),group));
        List<GraphSubgraph> allGroups=allGroupsById.values().stream()
                .sorted(Comparator.comparingInt(group->compositeDepth(group,allGroupsById))).toList();
        List<GraphSubgraph> groups=allGroups.stream().filter(this::compositeCollapsed)
                .filter(group->!compositeNodeIds(group,allGroupsById,new LinkedHashSet<>()).isEmpty()
                        &&!hasCollapsedCompositeAncestor(group,allGroupsById)).toList();
        Map<String,List<String>> membersByGroup=new LinkedHashMap<>();
        Map<String,GraphSubgraph> collapsedForNode=new LinkedHashMap<>();
        for(GraphSubgraph group:groups){
            List<String> members=compositeNodeIds(group,allGroupsById,new LinkedHashSet<>());
            membersByGroup.put(group.id(),members);
            members.forEach(nodeId->collapsedForNode.putIfAbsent(nodeId,group));
        }
        Map<String,VBox> groupViews=new LinkedHashMap<>();
        for(GraphSubgraph group:groups){
            List<String> members=membersByGroup.getOrDefault(group.id(),group.nodeIds());
            List<GraphSubgraph.BoundaryPort> boundary=GraphSubgraph.boundaryPorts(graph,members);
            VBox groupView=new VBox(5);groupView.setPrefWidth(GraphCanvasLayout.NODE_WIDTH);
            groupView.setStyle("-fx-background-color: #123b3a; -fx-border-color: #37b5a5; -fx-border-width: 2; -fx-border-radius: 9; -fx-background-radius: 9; -fx-padding: 9;");
            Label title=label(group.name(),"card-title");title.setWrapText(true);
            Label description=label(t("Composite · ","Composite · ")+members.size()+t(" нод"," nodes")
                    +(group.generatorId().isBlank()?"":t(" · runtime"," · runtime")),"muted");
            groupView.getChildren().addAll(title,description);
            for(GraphSubgraph.BoundaryPort port:boundary)groupView.getChildren().add(label((port.direction()==GraphSubgraph.Direction.INPUT?"← ":"→ ")+port.label(),"muted"));
            if(group.generatorId().isBlank())
                groupView.getChildren().add(actionButton(t("Развернуть","Expand"),()->setSubgraphCollapsed(graph.id(),group.id(),false)));
            else groupView.getChildren().add(actionButton(t("Развернуть","Expand"),()->setGeneratedCompositeCollapsed(group,false)));
            double x=0,y=0;int positioned=0;
            for(String nodeId:members){double[] point=initial.get(nodeId);if(point==null)continue;x+=point[0];y+=point[1];positioned++;}
            if(positioned==0)continue;
            x/=positioned;y/=positioned;
            groupView.setLayoutX(x);groupView.setLayoutY(y);groupViews.put(group.id(),groupView);canvas.getChildren().add(groupView);
            for(String nodeId:members)views.put(nodeId,groupView);
        }
        for(GraphNode node:graph.nodes()){
            if(collapsedForNode.containsKey(node.id()))continue;
            double[] position=initial.get(node.id());double x=position[0],y=position[1];
            VBox card=new VBox(5);card.setPrefWidth(GraphCanvasLayout.NODE_WIDTH);card.setMinWidth(GraphCanvasLayout.NODE_WIDTH);
            card.setMinHeight(82);card.setLayoutX(x);card.setLayoutY(y);
            card.setStyle("-fx-background-color: -fx-control-inner-background; -fx-border-color: "+(node.id().equals(selectedGraphNodeId)?"#3584e4":"-fx-box-border")+"; -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 8;");
            Label title=label(node.name(),"card-title");title.setWrapText(true);title.setMaxWidth(150);
            Label type=label(GraphNodeLabels.typeName(node,this::t),"muted");
            boolean canComposite=!GraphUiRoutes.common(graph.id())&&isSavedGraphNode(graph.id(),node.id())&&!(node instanceof DynamicGeneratorNode);
            CheckBox subgraphSelection=new CheckBox();subgraphSelection.setAccessibleText(t("Выбрать для Composite: ","Select for Composite: ")+node.name());
            subgraphSelection.setSelected(canComposite&&selectedSubgraphNodeIds.contains(node.id()));subgraphSelection.setDisable(!canComposite);
            subgraphSelection.setOnAction(event->{if(subgraphSelection.isSelected())selectedSubgraphNodeIds.add(node.id());else selectedSubgraphNodeIds.remove(node.id());showPage(Page.GRAPH);});
            VBox inputButtons=new VBox(2),outputButtons=new VBox(2);inputButtons.setAlignment(Pos.CENTER_LEFT);outputButtons.setAlignment(Pos.CENTER_RIGHT);
            for(AudioPort port:node.inputs()){
                Button button=graphPortButton("◉ "+port.id());button.setAccessibleText(t("Входной порт ","Input port ")+node.name()+" · "+port.id());
                button.setOnAction(event->selectGraphPort(graph.id(),node.id(),port));
                GraphPortDrag.installInput(button,(sourceNode,sourcePort)->addGraphPortConnection(graph.id(),new GraphPortRef(graph.id(),sourceNode,sourcePort),node.id(),port.id()));inputButtons.getChildren().add(button);
            }
            for(AudioPort port:node.outputs()){
                Button button=graphPortButton(port.id()+" ◉");button.setAccessibleText(t("Выходной порт ","Output port ")+node.name()+" · "+port.id());
                button.setOnAction(event->selectGraphPort(graph.id(),node.id(),port));GraphPortDrag.installOutput(button,node.id(),port.id());outputButtons.getChildren().add(button);
            }
            if(node instanceof RouterNode router){
                title.setWrapText(false);title.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);title.setMaxWidth(120);
                HBox header=new HBox(6,GraphUiRoutes.common(graph.id())?new Region():subgraphSelection,title,
                        label(GraphNodeLabels.typeName(router,this::t)+" · "+router.activeLinks().size()+"/"+(router.inputs().size()*router.outputs().size()),"muted"));
                card.getChildren().addAll(header,graphRouterCanvas(router,inputButtons,outputButtons));
            }else{
                VBox description=new VBox(3,title,type);HBox portRow=new HBox(4,inputButtons,GraphUiRoutes.common(graph.id())?new Region():subgraphSelection,description,outputButtons);
                HBox.setHgrow(description,Priority.ALWAYS);card.getChildren().add(portRow);
            }
            views.put(node.id(),card);canvas.getChildren().add(card);
        }
        Set<String> highlightedEdges=GraphPathHighlights.throughNode(graph,selectedGraphNodeId);
        for(GraphConnection edge:graph.connections()){
            boolean highlighted=highlightedEdges.contains(edge.id());
            Line line=new Line();line.setStroke(edge.enabled()?javafx.scene.paint.Color.web(highlighted?"#3584e4":"#37b5a5"):javafx.scene.paint.Color.web("#62666f"));
            line.setStrokeWidth(highlighted?4:edge.enabled()?2.5:1.0);if(!edge.enabled())line.getStrokeDashArray().setAll(6.0,5.0);
            line.setMouseTransparent(true);lines.put(edge.id(),line);canvas.getChildren().add(0,line);
        }
        Runnable refreshLines=()->{
            for(GraphConnection edge:graph.connections()){
                Node from=views.get(edge.fromNode()),to=views.get(edge.toNode());Line line=lines.get(edge.id());if(from==null||to==null||line==null)continue;
                GraphSubgraph fromGroup=collapsedForNode.get(edge.fromNode()),toGroup=collapsedForNode.get(edge.toNode());
                if(fromGroup!=null&&toGroup!=null&&fromGroup.id().equals(toGroup.id())){line.setVisible(false);continue;}line.setVisible(true);
                GraphNode source=nodes.get(edge.fromNode()),target=nodes.get(edge.toNode());
                int fromPort=Math.max(0,source.outputs().stream().map(AudioPort::id).toList().indexOf(edge.fromPort()));
                int toPort=Math.max(0,target.inputs().stream().map(AudioPort::id).toList().indexOf(edge.toPort()));
                double startY=fromGroup==null?from.getLayoutY()+(source instanceof RouterNode?42:28)+fromPort*(source instanceof RouterNode?24:22):subgraphBoundaryYForMembers(graph,edge,GraphSubgraph.Direction.OUTPUT,from,membersByGroup.get(fromGroup.id()));
                double endY=toGroup==null?to.getLayoutY()+(target instanceof RouterNode?42:28)+toPort*(target instanceof RouterNode?24:22):subgraphBoundaryYForMembers(graph,edge,GraphSubgraph.Direction.INPUT,to,membersByGroup.get(toGroup.id()));
                line.setStartX(from.getLayoutX()+GraphCanvasLayout.NODE_WIDTH);line.setStartY(startY);line.setEndX(to.getLayoutX());line.setEndY(endY);
            }
        };
        refreshLines.run();
        for(GraphNode node:graph.nodes()){
            if(collapsedForNode.containsKey(node.id()))continue;
            Node view=views.get(node.id());double[] start=initial.get(node.id());double[] pointer=new double[2];boolean[] moved={false};
            view.setOnMousePressed(event->{if(isButtonTarget(event.getTarget(),view))return;pointer[0]=event.getSceneX();pointer[1]=event.getSceneY();start[0]=view.getLayoutX();start[1]=view.getLayoutY();moved[0]=false;event.consume();});
            view.setOnMouseDragged(event->{if(isButtonTarget(event.getTarget(),view))return;double scale=Math.max(.25,zoom.getValue());
                double x=Math.max(0,Math.min(contentWidth-GraphCanvasLayout.NODE_WIDTH,start[0]+(event.getSceneX()-pointer[0])/scale));
                double y=Math.max(0,Math.min(contentHeight-graphCanvasNodeHeight(node),start[1]+(event.getSceneY()-pointer[1])/scale));
                view.setLayoutX(x);view.setLayoutY(y);moved[0]=true;refreshLines.run();event.consume();});
            view.setOnMouseReleased(event->{if(isButtonTarget(event.getTarget(),view))return;
                if(moved[0])saveGraphNodePosition(graph.id(),node.id(),view.getLayoutX(),view.getLayoutY());
                else if(!node.id().equals(selectedGraphNodeId)){selectedGraphNodeId=node.id();showPage(Page.GRAPH);}
            });
        }
        for(GraphSubgraph group:groups){
            Node view=groupViews.get(group.id());if(view==null)continue;
            List<String> members=membersByGroup.getOrDefault(group.id(),group.nodeIds());
            GraphNode firstMember=members.stream().map(nodes::get).filter(Objects::nonNull).findFirst().orElse(null);
            double groupHeight=firstMember==null?82:graphCanvasNodeHeight(firstMember);
            double[] start={view.getLayoutX(),view.getLayoutY()};double[] pointer=new double[2];boolean[] moved={false};
            view.setOnMousePressed(event->{if(isButtonTarget(event.getTarget(),view))return;pointer[0]=event.getSceneX();pointer[1]=event.getSceneY();start[0]=view.getLayoutX();start[1]=view.getLayoutY();moved[0]=false;event.consume();});
            view.setOnMouseDragged(event->{if(isButtonTarget(event.getTarget(),view))return;double scale=Math.max(.25,zoom.getValue());
                double x=Math.max(0,Math.min(contentWidth-GraphCanvasLayout.NODE_WIDTH,start[0]+(event.getSceneX()-pointer[0])/scale));
                double y=Math.max(0,Math.min(contentHeight-groupHeight,start[1]+(event.getSceneY()-pointer[1])/scale));
                view.setLayoutX(x);view.setLayoutY(y);moved[0]=true;refreshLines.run();event.consume();});
            view.setOnMouseReleased(event->{if(!isButtonTarget(event.getTarget(),view)&&moved[0])
                saveCompositeCanvasPosition(graph.id(),group,view.getLayoutX()-start[0],view.getLayoutY()-start[1],initial,members);});
        }
        box.getChildren().addAll(zoomRow,viewport);return box;
    }

    private void fitGraphCanvas(Slider zoom,ScrollPane viewport,double width,double height){
        if(viewport==null)return;
        double availableWidth=Math.max(320,viewport.getViewportBounds().getWidth());
        double availableHeight=Math.max(240,viewport.getViewportBounds().getHeight());
        double fit=Math.max(.25,Math.min(2,Math.min((availableWidth-32)/width,(availableHeight-32)/height)));
        zoom.setValue(Math.rint(fit*100.0)/100.0);viewport.setHvalue(0);viewport.setVvalue(0);
    }

    private void arrangeGraphCanvas(AudioGraph graph){
        GraphCanvasLayout.Layout layout=GraphCanvasLayout.arrange(graph,this::graphCanvasNodeHeight);
        updateRouteConfig(t("Упорядочить граф","Arrange graph"),config->{
            Map<String,Object> all=map(config.get("graph_ui_layout"));config.put("graph_ui_layout",all);
            Map<String,Object> route=map(all.get(graph.id()));all.put(graph.id(),route);
            layout.positions().forEach((nodeId,point)->route.put(nodeId,Map.of("x",point.x(),"y",point.y())));
        });
    }

    private void persistInitialGraphPositions(String routeId,AudioGraph graph,GraphCanvasLayout.Layout layout,
                                             Map<String,Object> savedLayout){
        Map<String,GraphCanvasLayout.Position> missing=new LinkedHashMap<>();
        for(GraphNode node:graph.nodes()){
            Map<String,Object> saved=map(savedLayout.get(node.id()));
            boolean hasX=Double.isFinite(coordinate(saved.get("x"),Double.NaN));
            boolean hasY=Double.isFinite(coordinate(saved.get("y"),Double.NaN));
            if(!hasX||!hasY)missing.put(node.id(),layout.positions().get(node.id()));
        }
        if(missing.isEmpty())return;
        store.updateAsync(config->{
            Map<String,Object> all=map(config.get("graph_ui_layout"));config.put("graph_ui_layout",all);
            Map<String,Object> route=map(all.get(routeId));all.put(routeId,route);
            missing.forEach((nodeId,position)->{
                Map<String,Object> current=map(route.get(nodeId));
                double x=coordinate(current.get("x"),position.x()),y=coordinate(current.get("y"),position.y());
                route.put(nodeId,Map.of("x",x,"y",y));
            });
        }).whenComplete((ignored,error)->Platform.runLater(()->{
            if(error!=null)setNotice(t("Начальная раскладка графа не сохранена: ","Initial graph layout could not be saved: ")+rootMessage(error),true);
        }));
    }
    private double coordinate(Object value,double fallback){
        if(!(value instanceof Number number))return fallback;
        double coordinate=number.doubleValue();
        return Double.isFinite(coordinate)&&coordinate>=0?coordinate:fallback;
    }

    private int compositeDepth(GraphSubgraph group,Map<String,GraphSubgraph> groups){
        int depth=0;Set<String> visited=new LinkedHashSet<>();String parent=group.parentId();
        while(parent!=null&&!parent.isBlank()&&visited.add(parent)){
            GraphSubgraph ancestor=groups.get(parent);if(ancestor==null)break;
            depth++;parent=ancestor.parentId();
        }
        return depth;
    }

    private boolean compositeCollapsed(GraphSubgraph group){
        return group.generatorId().isBlank()?group.collapsed()
                :generatedCompositeCollapseOverrides.getOrDefault(group.id(),group.collapsed());
    }

    private boolean hasCollapsedCompositeAncestor(GraphSubgraph group,Map<String,GraphSubgraph> groups){
        Set<String> visited=new LinkedHashSet<>();String parent=group.parentId();
        while(parent!=null&&!parent.isBlank()&&visited.add(parent)){
            GraphSubgraph ancestor=groups.get(parent);if(ancestor==null)break;
            if(compositeCollapsed(ancestor))return true;
            parent=ancestor.parentId();
        }
        return false;
    }

    private List<String> compositeNodeIds(GraphSubgraph group,Map<String,GraphSubgraph> groups,Set<String> visited){
        if(!visited.add(group.id()))return List.of();
        LinkedHashSet<String> nodes=new LinkedHashSet<>(group.nodeIds());
        for(GraphSubgraph child:groups.values())if(group.id().equals(child.parentId()))
            nodes.addAll(compositeNodeIds(child,groups,visited));
        return List.copyOf(nodes);
    }

    private void setGeneratedCompositeCollapsed(GraphSubgraph group,boolean collapsed){
        generatedCompositeCollapseOverrides.put(group.id(),collapsed);
        refreshGraphPageRuntime();
    }

    private double subgraphBoundaryYForMembers(AudioGraph graph,GraphConnection edge,GraphSubgraph.Direction direction,
                                               Node view,List<String> memberIds){
        String nodeId=direction==GraphSubgraph.Direction.INPUT?edge.toNode():edge.fromNode();
        String portId=direction==GraphSubgraph.Direction.INPUT?edge.toPort():edge.fromPort();
        List<GraphSubgraph.BoundaryPort> ports=GraphSubgraph.boundaryPorts(graph,memberIds);int index=0;
        for(int i=0;i<ports.size();i++){
            GraphSubgraph.BoundaryPort port=ports.get(i);
            if(port.direction()==direction&&port.nodeId().equals(nodeId)&&port.portId().equals(portId)){index=i;break;}
        }
        return view.getLayoutY()+42+index*20;
    }

    private void saveCompositeCanvasPosition(String routeId,GraphSubgraph group,double deltaX,double deltaY,
                                             Map<String,double[]> initial,List<String> memberIds){
        updateRouteConfig(t("Переместить Composite","Move Composite"),config->{
            Map<String,Object> all=map(config.get("graph_ui_layout"));config.put("graph_ui_layout",all);
            Map<String,Object> route=map(all.get(routeId));all.put(routeId,route);
            for(String nodeId:memberIds){
                double[] point=initial.get(nodeId);if(point==null)continue;
                route.put(nodeId,Map.of("x",Math.rint(point[0]+deltaX),"y",Math.rint(point[1]+deltaY)));
            }
        });
    }
    protected record DynamicChildAddress(String generatorId,String instanceKey,DynamicInstanceRegistry.RuntimeInstance instance) {}
    private record GnomePanelItem(String kind,String label,String routeId,String nodeId,String instanceKey,String groupId,
                                  Map<String,Object> pin,GraphNode node,GraphSubgraph composite,
                                  DynamicInstanceRegistry.RuntimeInstance runtime) {
        private String key(){return kind+"\u0001"+routeId+"\u0001"+nodeId+"\u0001"+instanceKey+"\u0001"+groupId;}
    }
    private static final class GraphCanvasUiState {
        private double scale=1;
        private double hValue;
        private double vValue;
    }

    protected double graphCanvasNodeHeight(GraphNode node){
        int ports=Math.max(node.inputs().size(),node.outputs().size());
        return Math.max(82,48+ports*24);
    }

}
