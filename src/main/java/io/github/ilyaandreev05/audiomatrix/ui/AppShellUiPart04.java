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

abstract class AppShellUiPart04 extends AppShellUiPart03 {
    protected AppShellUiPart04(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected VBox graphPage() {
        Map<String,Object> config=store.snapshot();
        String profileId=GraphRouteLibrary.activeProfileId(config);
        List<Map<String,Object>> routes=GraphRouteLibrary.routesForProfile(config,profileId);
        VBox page=page(t("Один активный маршрут содержит все одновременно работающие ветки. Изменения хранятся в config.json.",
                "One active route contains all branches that run together. Changes are stored in config.json."));
        HBox selector=new HBox(10);selector.setAlignment(Pos.CENTER_LEFT);
        ComboBox<Map<String,Object>> routePicker=new ComboBox<>();routePicker.getItems().setAll(routes);routePicker.setMaxWidth(420);
        routePicker.setConverter(new StringConverter<>(){
            @Override public String toString(Map<String,Object> route){return route==null?"":string(route.get("name"),string(route.get("id"),""));}
            @Override public Map<String,Object> fromString(String value){return null;}
        });
        String chosen=routes.stream().anyMatch(route->selectedGraphRouteId.equals(route.get("id")))?selectedGraphRouteId:string(config.get("active_route"),"");
        Map<String,Object> selected=routes.stream().filter(route->chosen.equals(route.get("id"))).findFirst().orElse(routes.isEmpty()?null:routes.getFirst());
        if(selected!=null){selectedGraphRouteId=string(selected.get("id"),"");routePicker.getSelectionModel().select(selected);}
        routePicker.setOnAction(event->{Map<String,Object> choice=routePicker.getValue();if(choice!=null){selectedGraphRouteId=string(choice.get("id"),"");showPage(Page.GRAPH);}});
        selector.getChildren().addAll(label(t("Маршрут", "Route"),"muted"),routePicker,
                actionButton(t("Создать", "New"),this::createGraphRoute),
                actionButton(t("Дублировать", "Duplicate"),()->duplicateSelectedGraphRoute()),
                actionButton(t("Переименовать", "Rename"),()->renameSelectedGraphRoute()),
                actionButton(t("Активировать", "Activate"),()->activateSelectedGraphRoute()),
                actionButton(t("Сохранить копию", "Save snapshot"),()->saveSelectedGraphCopy()),
                actionButton(t("Удалить", "Delete"),()->deleteSelectedGraphRoute()));
        page.getChildren().add(selector);
        if(selected==null){
            VBox empty=card(t("Граф маршрута", "Route graph"));empty.getChildren().add(label(t("Создай маршрут, затем добавь источники, обработку и выходы.","Create a route, then add sources, processing and outputs."),"muted"));page.getChildren().add(empty);
        }else{
            AudioGraph graph;
            try{graph=GraphCodec.fromMap(selected);}catch(RuntimeException invalid){
                VBox error=card(t("Некорректный граф", "Invalid graph"));error.getChildren().add(label(invalid.getMessage(),"danger"));page.getChildren().add(error);return page;
            }
            List<String> issues=GraphValidator.validate(graph);
            VBox status=card(t("Состояние графа", "Graph status"));
            boolean active=graph.id().equals(config.get("active_route"));
            status.getChildren().addAll(label((active?t("Активен в конфигурации", "Active in configuration"):t("Сохранённый маршрут", "Saved route"))+" · "+graph.nodes().size()+t(" нод · "," nodes · ")+graph.connections().size()+t(" связей"," links"),active?"accent":"muted"),
                    label(t("Применение графа перенастроит PipeWire. Неподдерживаемые ноды будут отклонены до внесения изменений.",
                            "Applying this graph will reconfigure PipeWire. Unsupported nodes are rejected before any changes."),"warning"),
                    actionButton(t("Применить активный граф к PipeWire","Apply active graph to PipeWire"),this::applyRoutes));
            if(!issues.isEmpty())issues.forEach(problem->status.getChildren().add(label(problem,"danger")));
            page.getChildren().add(status);
            page.getChildren().add(graphCanvasCard(graph));
            page.getChildren().add(graphMatrixCard(graph));
            page.getChildren().add(graphNodeCard(graph));
            page.getChildren().add(graphConnectionCard(graph));
            page.getChildren().add(graphRouteGroupCard(graph,config));
        }
        page.getChildren().add(graphSavedCopiesCard(config));
        page.getChildren().add(routeHistoryCard());
        return page;
    }

    protected VBox graphCanvasCard(AudioGraph graph){
        VBox box=card(t("Схема маршрута","Route canvas"));
        box.getChildren().add(label(t("Перетаскивай ноды и соединяй порты. Внешние связи свёрнутой ноды проходят через её граничные порты. Масштаб и положение хранятся отдельно от аудиосхемы.","Drag nodes and connect ports. External links cross a collapsed subgraph through its boundary ports. Canvas layout is saved separately from the audio graph."),"muted"));
        int sourceCount=(int)graph.nodes().stream().filter(SourceNode.class::isInstance).count();
        int outputCount=(int)graph.nodes().stream().filter(OutputNode.class::isInstance).count();
        double processHeight=graph.nodes().stream().filter(node->!(node instanceof SourceNode)&&!(node instanceof OutputNode))
                .mapToDouble(this::graphCanvasNodeHeight).sum();
        long processCount=graph.nodes().stream().filter(node->!(node instanceof SourceNode)&&!(node instanceof OutputNode)).count();
        processHeight+=Math.max(0,processCount-1)*16;
        double canvasHeight=Math.max(390,Math.max(Math.max(sourceCount,outputCount)*112+55,processHeight+55));
        double canvasWidth=1080;
        Map<String,Object> savedScales=map(store.snapshot().get("graph_ui_scale"));double initialScale=Math.max(.5,Math.min(2,number(savedScales.get(graph.id()),1.0)));
        HBox zoomRow=new HBox(8);zoomRow.setAlignment(Pos.CENTER_LEFT);
        Slider zoom=new Slider(.5,2,initialScale);zoom.setPrefWidth(180);zoom.setBlockIncrement(.1);zoom.setMajorTickUnit(.25);zoom.setSnapToTicks(true);
        Label zoomLabel=label(Math.round(initialScale*100)+"%","muted");zoom.setAccessibleText(t("Масштаб схемы маршрута","Route canvas zoom"));
        zoomRow.getChildren().addAll(label(t("Масштаб","Zoom"),"muted"),zoom,zoomLabel);box.getChildren().add(zoomRow);
        Pane canvas=new Pane();canvas.setMinSize(canvasWidth,canvasHeight);canvas.setPrefSize(canvasWidth,canvasHeight);canvas.setStyle("-fx-background-color: #181a1f; -fx-background-radius: 10;");
        Scale canvasScale=new Scale(initialScale,initialScale,0,0);canvas.getTransforms().add(canvasScale);
        Pane scaledContent=new Pane(canvas);scaledContent.setMinSize(canvasWidth*initialScale,canvasHeight*initialScale);scaledContent.setPrefSize(canvasWidth*initialScale,canvasHeight*initialScale);
        ScrollPane viewport=new ScrollPane(scaledContent);viewport.setPannable(true);viewport.setFitToWidth(false);viewport.setFitToHeight(false);viewport.setPrefViewportWidth(980);viewport.setPrefViewportHeight(Math.min(510,canvasHeight));
        zoom.valueProperty().addListener((obs,old,value)->{double scale=value.doubleValue();canvasScale.setX(scale);canvasScale.setY(scale);scaledContent.setMinSize(canvasWidth*scale,canvasHeight*scale);scaledContent.setPrefSize(canvasWidth*scale,canvasHeight*scale);zoomLabel.setText(Math.round(scale*100)+"%");});
        Runnable persistScale=()->{double scale=Math.rint(zoom.getValue()*100.0)/100.0;store.updateAsync(config->{Map<String,Object>scales=map(config.get("graph_ui_scale"));config.put("graph_ui_scale",scales);scales.put(graph.id(),scale);}).whenComplete((ignored,error)->Platform.runLater(()->{if(error!=null)setNotice(t("Масштаб не сохранён: ","Zoom could not be saved: ")+rootMessage(error),true);}));};
        zoom.setOnMouseReleased(event->persistScale.run());zoom.setOnKeyReleased(event->persistScale.run());
        Map<String,Object> allLayouts=map(store.snapshot().get("graph_ui_layout"));
        Map<String,Object> routeLayout=map(allLayouts.get(graph.id()));
        Map<String,Node> views=new LinkedHashMap<>();Map<String,Line> lines=new LinkedHashMap<>();
        Map<String,GraphNode> nodes=new LinkedHashMap<>();graph.nodes().forEach(node->nodes.put(node.id(),node));
        int sourceIndex=0,outputIndex=0;double processY=25;
        Map<String,double[]> initial=new LinkedHashMap<>();
        for(GraphNode node:graph.nodes()){
            double defaultX,defaultY;
            if(node instanceof SourceNode){defaultX=25;defaultY=25+sourceIndex++*112;}
            else if(node instanceof OutputNode){defaultX=810;defaultY=25+outputIndex++*112;}
            else{defaultX=360;defaultY=processY;processY+=graphCanvasNodeHeight(node)+16;}
            Map<String,Object> saved=map(routeLayout.get(node.id()));double x=number(saved.get("x"),defaultX),y=number(saved.get("y"),defaultY);
            initial.put(node.id(),new double[]{x,y});
        }
        List<GraphSubgraph> groups=GraphSubgraphLibrary.forRoute(store.snapshot(),graph.id()).stream().filter(GraphSubgraph::collapsed).toList();
        Map<String,GraphSubgraph> collapsedForNode=new LinkedHashMap<>();groups.forEach(group->group.nodeIds().forEach(nodeId->collapsedForNode.put(nodeId,group)));
        Map<String,VBox> groupViews=new LinkedHashMap<>();
        for(GraphSubgraph group:groups){
            List<GraphSubgraph.BoundaryPort> boundary=GraphSubgraph.boundaryPorts(graph,group.nodeIds());
            VBox groupView=new VBox(5);groupView.setPrefWidth(285);groupView.setStyle("-fx-background-color: #123b3a; -fx-border-color: #37b5a5; -fx-border-width: 2; -fx-border-radius: 9; -fx-background-radius: 9; -fx-padding: 9;");
            Label title=label(group.name(),"card-title");title.setWrapText(true);Label description=label(t("Подграф · ","Subgraph · ")+group.nodeIds().size()+t(" нод"," nodes"),"muted");groupView.getChildren().addAll(title,description);
            for(GraphSubgraph.BoundaryPort port:boundary)groupView.getChildren().add(label((port.direction()==GraphSubgraph.Direction.INPUT?"← ":"→ ")+port.label(),"muted"));
            groupView.getChildren().add(actionButton(t("Развернуть","Expand"),()->setSubgraphCollapsed(graph.id(),group.id(),false)));
            double x=0,y=0;for(String nodeId:group.nodeIds()){double[] point=initial.get(nodeId);x+=point[0];y+=point[1];}x/=group.nodeIds().size();y/=group.nodeIds().size();
            groupView.setLayoutX(x);groupView.setLayoutY(y);groupViews.put(group.id(),groupView);canvas.getChildren().add(groupView);
            for(String nodeId:group.nodeIds())views.put(nodeId,groupView);
        }
        for(GraphNode node:graph.nodes()){
            if(collapsedForNode.containsKey(node.id()))continue;
            double[] position=initial.get(node.id());double x=position[0],y=position[1];
            VBox card=new VBox(5);card.setPrefWidth(node instanceof RouterNode?360:250);card.setMinHeight(82);card.setLayoutX(x);card.setLayoutY(y);card.setStyle("-fx-background-color: #292d35; -fx-border-color: #656b77; -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 8;");
            Label title=label(node.name(),"card-title");title.setWrapText(true);title.setMaxWidth(160);
            Label type=label(node.type(),"muted");
            VBox inputButtons=new VBox(2),outputButtons=new VBox(2);inputButtons.setAlignment(Pos.CENTER_LEFT);outputButtons.setAlignment(Pos.CENTER_RIGHT);
            for(AudioPort port:node.inputs()){Button button=graphPortButton("◉ "+port.id());button.setAccessibleText(t("Входной порт ","Input port ")+node.name()+" · "+port.id());button.setOnAction(event->selectGraphPort(graph.id(),node.id(),port));inputButtons.getChildren().add(button);}
            for(AudioPort port:node.outputs()){Button button=graphPortButton(port.id()+" ◉");button.setAccessibleText(t("Выходной порт ","Output port ")+node.name()+" · "+port.id());button.setOnAction(event->selectGraphPort(graph.id(),node.id(),port));outputButtons.getChildren().add(button);}
            if(node instanceof RouterNode router){
                title.setWrapText(false);title.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);title.setMaxWidth(185);
                HBox header=new HBox(10,title,label(router.type()+" · "+router.activeLinks().size()+"/"+(router.inputs().size()*router.outputs().size()),"muted"));
                card.getChildren().addAll(header,graphRouterCanvas(router,inputButtons,outputButtons));
            }else{
                VBox description=new VBox(3,title,type);HBox portRow=new HBox(4,inputButtons,description,outputButtons);HBox.setHgrow(description,Priority.ALWAYS);
                card.getChildren().add(portRow);
            }
            views.put(node.id(),card);canvas.getChildren().add(card);
        }
        for(GraphConnection edge:graph.connections()){
            Line line=new Line();line.setStroke(edge.enabled()?javafx.scene.paint.Color.web("#37b5a5"):javafx.scene.paint.Color.web("#62666f"));line.setStrokeWidth(edge.enabled()?2.5:1.0);if(!edge.enabled())line.getStrokeDashArray().setAll(6.0,5.0);line.setMouseTransparent(true);lines.put(edge.id(),line);canvas.getChildren().add(0,line);
        }
        Runnable refreshLines=()->{
            for(GraphConnection edge:graph.connections()){
                Node from=views.get(edge.fromNode()),to=views.get(edge.toNode());Line line=lines.get(edge.id());if(from==null||to==null||line==null)continue;
                GraphSubgraph fromGroup=collapsedForNode.get(edge.fromNode()),toGroup=collapsedForNode.get(edge.toNode());
                if(fromGroup!=null&&toGroup!=null&&fromGroup.id().equals(toGroup.id())){line.setVisible(false);continue;}line.setVisible(true);
                GraphNode source=nodes.get(edge.fromNode()),target=nodes.get(edge.toNode());int fromPort=Math.max(0,source.outputs().stream().map(AudioPort::id).toList().indexOf(edge.fromPort()));int toPort=Math.max(0,target.inputs().stream().map(AudioPort::id).toList().indexOf(edge.toPort()));
                double startY=fromGroup==null?from.getLayoutY()+(source instanceof RouterNode?42:28)+fromPort*(source instanceof RouterNode?24:22):subgraphBoundaryY(graph,fromGroup,edge,GraphSubgraph.Direction.OUTPUT,from);
                double endY=toGroup==null?to.getLayoutY()+(target instanceof RouterNode?42:28)+toPort*(target instanceof RouterNode?24:22):subgraphBoundaryY(graph,toGroup,edge,GraphSubgraph.Direction.INPUT,to);
                line.setStartX(from.getLayoutX()+from.getBoundsInLocal().getWidth());line.setStartY(startY);line.setEndX(to.getLayoutX());line.setEndY(endY);
            }
        };
        refreshLines.run();
        for(GraphNode node:graph.nodes()){
            if(collapsedForNode.containsKey(node.id()))continue;
            Node view=views.get(node.id());double[] start=initial.get(node.id());double[] pointer=new double[2];boolean[] moved={false};
            view.setOnMousePressed(event->{if(isButtonTarget(event.getTarget(),view))return;pointer[0]=event.getSceneX();pointer[1]=event.getSceneY();start[0]=view.getLayoutX();start[1]=view.getLayoutY();moved[0]=false;event.consume();});
            view.setOnMouseDragged(event->{if(isButtonTarget(event.getTarget(),view))return;double scale=Math.max(.5,zoom.getValue());double x=Math.max(0,Math.min(canvasWidth-260,start[0]+(event.getSceneX()-pointer[0])/scale));double y=Math.max(0,Math.min(canvasHeight-90,start[1]+(event.getSceneY()-pointer[1])/scale));view.setLayoutX(x);view.setLayoutY(y);moved[0]=true;refreshLines.run();event.consume();});
            view.setOnMouseReleased(event->{if(!isButtonTarget(event.getTarget(),view)&&moved[0])saveGraphNodePosition(graph.id(),node.id(),view.getLayoutX(),view.getLayoutY());});
        }
        for(GraphSubgraph group:groups){
            Node view=groupViews.get(group.id());double[] start={view.getLayoutX(),view.getLayoutY()};double[] pointer=new double[2];boolean[] moved={false};
            view.setOnMousePressed(event->{if(isButtonTarget(event.getTarget(),view))return;pointer[0]=event.getSceneX();pointer[1]=event.getSceneY();start[0]=view.getLayoutX();start[1]=view.getLayoutY();moved[0]=false;event.consume();});
            view.setOnMouseDragged(event->{if(isButtonTarget(event.getTarget(),view))return;double scale=Math.max(.5,zoom.getValue());double x=Math.max(0,Math.min(canvasWidth-300,start[0]+(event.getSceneX()-pointer[0])/scale));double y=Math.max(0,Math.min(canvasHeight-100,start[1]+(event.getSceneY()-pointer[1])/scale));view.setLayoutX(x);view.setLayoutY(y);moved[0]=true;refreshLines.run();event.consume();});
            view.setOnMouseReleased(event->{if(!isButtonTarget(event.getTarget(),view)&&moved[0])saveSubgraphPosition(graph.id(),group,view.getLayoutX()-start[0],view.getLayoutY()-start[1],initial);});
        }
        box.getChildren().add(viewport);return box;
    }

    protected double graphCanvasNodeHeight(GraphNode node){
        int ports=Math.max(node.inputs().size(),node.outputs().size());
        return Math.max(82,40+ports*24);
    }
}
