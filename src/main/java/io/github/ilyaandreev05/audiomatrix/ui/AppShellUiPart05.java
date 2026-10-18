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

abstract class AppShellUiPart05 extends AppShellUiPart04 {
    protected AppShellUiPart05(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected HBox graphRouterCanvas(RouterNode router,VBox inputButtons,VBox outputButtons){
        int rows=Math.max(router.inputs().size(),router.outputs().size());
        double rowHeight=24,diagramWidth=132,diagramHeight=Math.max(rowHeight,rows*rowHeight);
        inputButtons.setSpacing(2);outputButtons.setSpacing(2);
        inputButtons.setPrefWidth(72);outputButtons.setPrefWidth(88);
        for(Node child:inputButtons.getChildren())if(child instanceof Button button){button.setPrefSize(72,22);button.setMinSize(72,22);button.setMaxSize(72,22);button.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);}
        for(Node child:outputButtons.getChildren())if(child instanceof Button button){button.setPrefSize(88,22);button.setMinSize(88,22);button.setMaxSize(88,22);button.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);}

        Pane diagram=new Pane();diagram.setPrefSize(diagramWidth,diagramHeight);diagram.setMinSize(diagramWidth,diagramHeight);diagram.setMaxSize(diagramWidth,diagramHeight);
        diagram.setStyle("-fx-background-color: #171a20; -fx-border-color: #474e5a; -fx-border-width: 1; -fx-border-radius: 10; -fx-background-radius: 10;");
        diagram.setAccessibleText(t("Активные связи матрицы: ","Active matrix connections: ")+String.join(", ",router.activeLinks()));
        javafx.scene.paint.Color inputColor=javafx.scene.paint.Color.web("#16a394");
        javafx.scene.paint.Color outputColor=javafx.scene.paint.Color.web("#3978ff");
        javafx.scene.paint.Color linkColor=javafx.scene.paint.Color.web("#13998e");
        for(String activeLink:router.activeLinks()){
            String[] endpoints=activeLink.split("->",2);if(endpoints.length!=2)continue;
            int inputIndex=-1,outputIndex=-1;
            for(int i=0;i<router.inputs().size();i++)if(router.inputs().get(i).id().equals(endpoints[0]))inputIndex=i;
            for(int i=0;i<router.outputs().size();i++)if(router.outputs().get(i).id().equals(endpoints[1]))outputIndex=i;
            if(inputIndex<0||outputIndex<0)continue;
            double startY=inputIndex*rowHeight+rowHeight/2,endY=outputIndex*rowHeight+rowHeight/2;
            javafx.scene.shape.Path path=new javafx.scene.shape.Path(
                    new javafx.scene.shape.MoveTo(7,startY),
                    new javafx.scene.shape.CubicCurveTo(diagramWidth*.40,startY,diagramWidth*.60,endY,diagramWidth-7,endY));
            path.setFill(javafx.scene.paint.Color.TRANSPARENT);path.setStroke(linkColor);path.setStrokeWidth(2.2);path.setMouseTransparent(true);
            diagram.getChildren().add(path);
        }
        for(int i=0;i<router.inputs().size();i++)diagram.getChildren().add(new Circle(7,i*rowHeight+rowHeight/2,4.5,inputColor));
        for(int i=0;i<router.outputs().size();i++)diagram.getChildren().add(new Circle(diagramWidth-7,i*rowHeight+rowHeight/2,4.5,outputColor));
        HBox body=new HBox(4,inputButtons,diagram,outputButtons);body.setAlignment(Pos.CENTER_LEFT);body.setAccessibleText(router.name()+" · "+router.activeLinks().size()+t(" активных связей"," active connections"));
        return body;
    }

    protected double subgraphBoundaryY(AudioGraph graph,GraphSubgraph group,GraphConnection edge,GraphSubgraph.Direction direction,Node view){
        String nodeId=direction==GraphSubgraph.Direction.INPUT?edge.toNode():edge.fromNode();String portId=direction==GraphSubgraph.Direction.INPUT?edge.toPort():edge.fromPort();
        List<GraphSubgraph.BoundaryPort> ports=GraphSubgraph.boundaryPorts(graph,group.nodeIds());int index=0;
        for(int i=0;i<ports.size();i++){GraphSubgraph.BoundaryPort port=ports.get(i);if(port.direction()==direction&&port.nodeId().equals(nodeId)&&port.portId().equals(portId)){index=i;break;}}
        return view.getLayoutY()+42+index*20;
    }

    protected void saveSubgraphPosition(String routeId,GraphSubgraph group,double deltaX,double deltaY,Map<String,double[]> initial){
        updateRouteConfig(t("Переместить подграф","Move subgraph"),config->{Map<String,Object>layouts=map(config.get("graph_ui_layout"));config.put("graph_ui_layout",layouts);Map<String,Object>route=map(layouts.get(routeId));layouts.put(routeId,route);for(String nodeId:group.nodeIds()){double[] point=initial.get(nodeId);route.put(nodeId,Map.of("x",Math.rint(point[0]+deltaX),"y",Math.rint(point[1]+deltaY)));}});
    }

    protected Button graphPortButton(String text){Button button=new Button(text);button.setFocusTraversable(true);button.setStyle("-fx-font-size: 10px; -fx-padding: 2 5 2 5; -fx-background-color: #20242b; -fx-border-color: #37b5a5; -fx-border-radius: 8; -fx-background-radius: 8;");return button;}

    protected void selectGraphPort(String routeId,String nodeId,AudioPort port){
        if(port.direction()==AudioPort.Direction.OUTPUT){pendingGraphOutput=new GraphPortRef(routeId,nodeId,port.id());setNotice(t("Выбран выход ","Selected output ")+port.id()+t(". Теперь выбери входной порт.",". Choose an input port next."),false);return;}
        GraphPortRef source=pendingGraphOutput;if(source==null||!source.routeId().equals(routeId)){setNotice(t("Сначала выбери выходной порт.","Choose an output port first."),true);return;}
        pendingGraphOutput=null;if(source.nodeId().equals(nodeId)){setNotice(t("Нода не может соединяться сама с собой.","A node cannot connect to itself."),true);return;}
        addGraphPortConnection(routeId,source,nodeId,port.id());
    }

    protected void addGraphPortConnection(String routeId,GraphPortRef source,String targetNode,String targetPort){
        updateRouteConfig(t("Соединить порты графа","Connect graph ports"),config->{Map<String,Object> route=GraphUiRoutes.route(config,routeId);AudioGraph old=GraphCodec.fromMap(route);boolean duplicate=old.connections().stream().anyMatch(edge->edge.fromNode().equals(source.nodeId())&&edge.fromPort().equals(source.portId())&&edge.toNode().equals(targetNode)&&edge.toPort().equals(targetPort));if(duplicate)return;List<GraphConnection> edges=new ArrayList<>(old.connections());edges.add(new GraphConnection("link-"+UUID.randomUUID(),source.nodeId(),source.portId(),targetNode,targetPort,true,1));AudioGraph candidate=new AudioGraph(old.id(),old.name(),old.nodes(),edges);List<String>problems=GraphValidator.validate(candidate);if(!problems.isEmpty())throw new IllegalArgumentException(String.join("; ",problems));GraphRouteLibrary.saveRoute(config,candidate,string(route.get("group_id"),""));});
    }

    protected void saveGraphNodePosition(String routeId,String nodeId,double x,double y){
        updateRouteConfig(t("Переместить ноду","Move graph node"),config->{Map<String,Object> layouts=map(config.get("graph_ui_layout"));config.put("graph_ui_layout",layouts);Map<String,Object> route=map(layouts.get(routeId));layouts.put(routeId,route);route.put(nodeId,Map.of("x",Math.rint(x),"y",Math.rint(y)));});
    }

    protected VBox graphMatrixCard(AudioGraph graph){
        VBox box=card(t("Матрица соединений", "Connection matrix"));
        List<SourceNode> sources=graph.nodes().stream().filter(SourceNode.class::isInstance).map(SourceNode.class::cast).toList();
        List<OutputNode> outputs=graph.nodes().stream().filter(OutputNode.class::isInstance).map(OutputNode.class::cast).toList();
        box.getChildren().add(label(t("Строки — источники, столбцы — выходы. Совместный маршрут нескольких источников к одному выходу автоматически использует Mixer.",
                "Rows are sources and columns are outputs. Multiple sources feeding one output use an explicit Mixer."),"muted"));
        if(sources.isEmpty()||outputs.isEmpty()){box.getChildren().add(label(t("Добавь хотя бы один источник и один выход, чтобы построить матрицу.","Add at least one source and one output to show the matrix."),"muted"));return box;}
        GridPane grid=new GridPane();grid.setHgap(14);grid.setVgap(9);grid.add(label(t("Вход ↓ / выход →","Input ↓ / output →"),"card-title"),0,0);
        for(int col=0;col<outputs.size();col++){Label title=label(outputs.get(col).name(),"muted");title.setWrapText(true);title.setMaxWidth(150);grid.add(title,col+1,0);}
        for(int row=0;row<sources.size();row++){
            SourceNode source=sources.get(row);grid.add(label(source.name(),""),0,row+1);
            for(int col=0;col<outputs.size();col++){
                OutputNode output=outputs.get(col);boolean connected=GraphMatrixRouting.connected(graph,source,output);
                CheckBox cell=new CheckBox();cell.setSelected(connected);cell.setAccessibleText(source.name()+" → "+output.name());
                cell.setDisable(!GraphMatrixRouting.canToggle(graph,source,output));
                if(cell.isDisabled())cell.setTooltip(new Tooltip(t("Изменяй обработанный маршрут в редакторе графа.","Edit processed paths in the graph editor.")));
                cell.setOnAction(event->setDirectMatrixConnection(graph.id(),source,output,cell.isSelected()));grid.add(cell,col+1,row+1);
            }
        }
        box.getChildren().add(grid);return box;
    }

    protected void setDirectMatrixConnection(String routeId,SourceNode source,OutputNode output,boolean enabled){
        Map<String,Object> config=store.snapshot();Map<String,Object> route=GraphUiRoutes.route(config,routeId);
        AudioGraph old=GraphCodec.fromMap(route);
        try{saveGraph(routeId,GraphMatrixRouting.setDirect(old,source,output,enabled));}
        catch(IllegalArgumentException invalid){setNotice(invalid.getMessage(),true);showPage(Page.GRAPH);}
    }
}
