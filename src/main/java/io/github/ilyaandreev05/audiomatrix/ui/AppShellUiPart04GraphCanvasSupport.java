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

abstract class AppShellUiPart04GraphCanvasSupport extends AppShellUiPart04GraphInspector {
    protected AppShellUiPart04GraphCanvasSupport(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }
    protected void fitGraphCanvas(Slider zoom,ScrollPane viewport,double width,double height){
        if(viewport==null)return;
        double availableWidth=Math.max(320,viewport.getViewportBounds().getWidth());
        double availableHeight=Math.max(240,viewport.getViewportBounds().getHeight());
        double fit=Math.max(.25,Math.min(2,Math.min((availableWidth-32)/width,(availableHeight-32)/height)));
        zoom.setValue(Math.rint(fit*100.0)/100.0);viewport.setHvalue(0);viewport.setVvalue(0);
    }

    protected void arrangeGraphCanvas(AudioGraph graph){
        GraphCanvasLayout.Layout layout=GraphCanvasLayout.arrange(graph,this::graphCanvasNodeHeight);
        updateRouteConfig(t("Упорядочить граф","Arrange graph"),config->{
            Map<String,Object> all=map(config.get("graph_ui_layout"));config.put("graph_ui_layout",all);
            Map<String,Object> route=map(all.get(graph.id()));all.put(graph.id(),route);
            layout.positions().forEach((nodeId,point)->route.put(nodeId,Map.of("x",point.x(),"y",point.y())));
        });
    }

    protected void persistInitialGraphPositions(String routeId,AudioGraph graph,GraphCanvasLayout.Layout layout,
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
    protected double coordinate(Object value,double fallback){
        if(!(value instanceof Number number))return fallback;
        double coordinate=number.doubleValue();
        return Double.isFinite(coordinate)&&coordinate>=0?coordinate:fallback;
    }

    protected int compositeDepth(GraphSubgraph group,Map<String,GraphSubgraph> groups){
        int depth=0;Set<String> visited=new LinkedHashSet<>();String parent=group.parentId();
        while(parent!=null&&!parent.isBlank()&&visited.add(parent)){
            GraphSubgraph ancestor=groups.get(parent);if(ancestor==null)break;
            depth++;parent=ancestor.parentId();
        }
        return depth;
    }

    protected boolean compositeCollapsed(GraphSubgraph group){
        return group.generatorId().isBlank()?group.collapsed()
                :generatedCompositeCollapseOverrides.getOrDefault(group.id(),group.collapsed());
    }

    protected boolean hasCollapsedCompositeAncestor(GraphSubgraph group,Map<String,GraphSubgraph> groups){
        Set<String> visited=new LinkedHashSet<>();String parent=group.parentId();
        while(parent!=null&&!parent.isBlank()&&visited.add(parent)){
            GraphSubgraph ancestor=groups.get(parent);if(ancestor==null)break;
            if(compositeCollapsed(ancestor))return true;
            parent=ancestor.parentId();
        }
        return false;
    }

    protected List<String> compositeNodeIds(GraphSubgraph group,Map<String,GraphSubgraph> groups,Set<String> visited){
        if(!visited.add(group.id()))return List.of();
        LinkedHashSet<String> nodes=new LinkedHashSet<>(group.nodeIds());
        for(GraphSubgraph child:groups.values())if(group.id().equals(child.parentId()))
            nodes.addAll(compositeNodeIds(child,groups,visited));
        return List.copyOf(nodes);
    }

    protected void setGeneratedCompositeCollapsed(GraphSubgraph group,boolean collapsed){
        generatedCompositeCollapseOverrides.put(group.id(),collapsed);
        refreshGraphPageRuntime();
    }

    protected double subgraphBoundaryYForMembers(AudioGraph graph,GraphConnection edge,GraphSubgraph.Direction direction,
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

    protected void saveCompositeCanvasPosition(String routeId,GraphSubgraph group,double deltaX,double deltaY,
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
    protected double graphCanvasNodeHeight(GraphNode node){
        int ports=Math.max(node.inputs().size(),node.outputs().size());
        return Math.max(82,48+ports*24);
    }
}
