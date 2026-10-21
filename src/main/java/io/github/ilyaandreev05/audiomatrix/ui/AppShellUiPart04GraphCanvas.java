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

abstract class AppShellUiPart04GraphCanvas extends AppShellUiPart04GnomeEditor {
    protected AppShellUiPart04GraphCanvas(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
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
}
