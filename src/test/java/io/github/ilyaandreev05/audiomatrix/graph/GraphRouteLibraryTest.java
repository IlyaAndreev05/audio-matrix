package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.ConfigValidator;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GraphRouteLibraryTest {
    private static final List<String> STEREO=List.of("FL","FR");

    @Test void savedRoutesDuplicateGroupsAndInactiveCopiesHaveIndependentIds(){
        Map<String,Object> config=DefaultConfig.create();String group=GraphRouteLibrary.createGroup(config,"Рабочее место");
        AudioGraph original=graph("route-one","Монитор",group);GraphRouteLibrary.saveRoute(config,original,group);GraphRouteLibrary.activate(config,original.id());
        Map<String,Object> pinGroup=QuickSettingsConfig.findOrCreateGroup(config,"Уровни");
        for(int index=0;index<original.nodes().size();index++)
            QuickSettingsConfig.pinNode(config,original.id(),original.nodes().get(index).id(),String.valueOf(pinGroup.get("id")),index,"EXPANDED");
        @SuppressWarnings("unchecked") List<Map<String,Object>> controls=(List<Map<String,Object>>)config.get("quick_controls");
        controls.add(DefaultConfig.map("id","gain-control","route_id",original.id(),"node_id",original.id()+"-gain",
                "parameter_id","gain","section","Уровни","group_id",pinGroup.get("id"),"order",0,"label","Chrome"));
        QuickSettingsConfig.attachControl(config,original.id(),original.id()+"-gain","gain-control");
        @SuppressWarnings("unchecked") Map<String,Object> layouts=(Map<String,Object>)config.get("graph_ui_layout");layouts.put(original.id(),Map.of(original.nodes().getFirst().id(),Map.of("x",120.0,"y",80.0)));
        @SuppressWarnings("unchecked") Map<String,Object> scales=(Map<String,Object>)config.get("graph_ui_scale");scales.put(original.id(),0.85);
        String copyId=GraphRouteLibrary.saveInactiveCopy(config,original.id(),"Надёжная копия");
        AudioGraph duplicate=GraphRouteLibrary.duplicateRoute(config,original.id(),"Наушники");
        assertNotEquals(original.id(),duplicate.id());assertNotEquals(original.nodes().getFirst().id(),duplicate.nodes().getFirst().id());
        assertIndependentPins(config,original.id(),duplicate);
        assertEquals(original.id(),config.get("active_route"));assertEquals(Boolean.FALSE,((Map<?,?>)((List<?>)config.get("saved_copies")).getFirst()).get("active"));
        AudioGraph restored=GraphRouteLibrary.restoreSavedCopy(config,copyId,"Восстановленный граф");
        assertNotEquals(original.nodes().getFirst().id(),restored.nodes().getFirst().id());
        assertIndependentPins(config,original.id(),restored);
        @SuppressWarnings("unchecked") Map<String,Object> duplicateLayout=(Map<String,Object>)layouts.get(duplicate.id());
        assertNotNull(duplicateLayout.get(duplicate.nodes().getFirst().id()));
        @SuppressWarnings("unchecked") Map<String,Object> restoredLayout=(Map<String,Object>)layouts.get(restored.id());
        assertNotNull(restoredLayout.get(restored.nodes().getFirst().id()));
        assertEquals(0.85,scales.get(duplicate.id()));assertEquals(0.85,scales.get(restored.id()));
        assertEquals(0.85,((Map<?,?>)((List<?>)config.get("saved_copies")).getFirst()).get("ui_scale"));
        String groupCopy=GraphRouteLibrary.duplicateGroup(config,group,"Второй комплект");
        assertTrue(((List<?>)config.get("route_library")).stream().filter(Map.class::isInstance).map(item->(Map<?,?>)item).anyMatch(item->groupCopy.equals(item.get("group_id"))));
        assertEquals(List.of(),ConfigValidator.validate(config));
        layouts.put(original.id(),Map.of(original.nodes().getFirst().id(),Map.of("x",-1.0,"y",80.0)));
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(problem->problem.contains("graph_ui_layout")));
    }

    private static void assertIndependentPins(Map<String,Object> config,String sourceRoute,AudioGraph copiedRoute){
        Map<String,Object> savedOriginal=GraphRouteLibrary.find(GraphRouteLibrary.maps(config.get("route_library")),sourceRoute);
        AudioGraph originalGraph=GraphCodec.fromMap(savedOriginal);
        List<Map<String,Object>> originalPins=QuickSettingsConfig.pins(config).stream()
                .filter(pin->sourceRoute.equals(pin.get("route_id"))).toList();
        List<Map<String,Object>> copiedPins=QuickSettingsConfig.pins(config).stream()
                .filter(pin->copiedRoute.id().equals(pin.get("route_id"))).toList();
        assertEquals(originalPins.size(),copiedPins.size());
        for(Map<String,Object> originalPin:originalPins){
            String originalNode=String.valueOf(originalPin.get("node_id"));
            GraphNode originalNodeValue=originalGraph.nodes().stream().filter(node->originalNode.equals(node.id())).findFirst().orElseThrow();
            GraphNode copiedNode=copiedRoute.nodes().stream().filter(node->node.name().equals(originalNodeValue.name())
                    &&node.type().equals(originalNodeValue.type())).findFirst().orElseThrow();
            Map<String,Object> copiedPin=copiedPins.stream().filter(pin->copiedNode.id().equals(pin.get("node_id"))).findFirst().orElseThrow();
            assertNotEquals(originalNode,copiedNode.id());
            assertNotEquals(originalPin.get("id"),copiedPin.get("id"));
            assertEquals(originalPin.get("group_id"),copiedPin.get("group_id"));
            assertEquals(originalPin.get("order"),copiedPin.get("order"));
            assertEquals(originalPin.get("view"),copiedPin.get("view"));
            assertEquals(((List<?>)originalPin.get("control_ids")).size(),((List<?>)copiedPin.get("control_ids")).size());
        }
        Map<String,Object> copiedGain=copiedPins.stream().filter(pin->!((List<?>)pin.get("control_ids")).isEmpty())
                .findFirst().orElseThrow();
        String copiedControl=String.valueOf(((List<?>)copiedGain.get("control_ids")).getFirst());
        assertNotEquals("gain-control",copiedControl);
        assertTrue(((List<?>)config.get("quick_controls")).stream().map(item->(Map<?,?>)item)
                .anyMatch(control->copiedControl.equals(control.get("id"))
                        &&copiedRoute.id().equals(control.get("route_id"))
                        &&copiedGain.get("node_id").equals(control.get("node_id"))));
    }

    @Test void deletingActiveRouteRequiresAnExistingReplacement(){
        Map<String,Object> config=DefaultConfig.create();AudioGraph first=graph("first","First","");AudioGraph second=graph("second","Second","");
        GraphRouteLibrary.saveRoute(config,first,"");GraphRouteLibrary.saveRoute(config,second,"");GraphRouteLibrary.activate(config,first.id());
        @SuppressWarnings("unchecked") Map<String,Object> scales=(Map<String,Object>)config.get("graph_ui_scale");scales.put(first.id(),1.25);
        assertThrows(IllegalArgumentException.class,()->GraphRouteLibrary.deleteRoute(config,first.id(),null));
        GraphRouteLibrary.deleteRoute(config,first.id(),second.id());assertEquals(second.id(),config.get("active_route"));assertFalse(scales.containsKey(first.id()));
    }

    @Test void canvasZoomIsRangeCheckedAndMustBelongToASavedRoute(){
        Map<String,Object> config=DefaultConfig.create();AudioGraph route=graph("zoom-route","Zoom route","");GraphRouteLibrary.saveRoute(config,route,"");
        @SuppressWarnings("unchecked") Map<String,Object> scales=(Map<String,Object>)config.get("graph_ui_scale");
        scales.put(route.id(),1.5);assertTrue(ConfigValidator.validate(config).isEmpty());
        scales.put(route.id(),2.1);assertTrue(ConfigValidator.validate(config).stream().anyMatch(problem->problem.contains("graph_ui_scale")));
        scales.clear();scales.put("missing",1.0);assertTrue(ConfigValidator.validate(config).stream().anyMatch(problem->problem.contains("missing route")));
    }

    @Test void routeCopiesKeepTheOutputChannelMatrix(){
        Map<String,Object> config=DefaultConfig.create();
        ChannelMatrix rear=new ChannelMatrix(STEREO,List.of("RL","RR"),List.of(List.of(1.0,0.0),List.of(0.0,1.0)));
        AudioGraph route=new AudioGraph("rear-route","Rear channels",List.of(
                new SourceNode("source","Apps","application:unassigned",STEREO),
                new OutputNode("rear","Wired headphones","device:family17h",STEREO,rear)),
                List.of(new GraphConnection("source-to-rear","source","out","rear","in",true,1)));
        GraphRouteLibrary.saveRoute(config,route,"");

        AudioGraph duplicate=GraphRouteLibrary.duplicateRoute(config,route.id(),"Rear copy");
        OutputNode copied=(OutputNode)duplicate.nodes().stream().filter(OutputNode.class::isInstance).findFirst().orElseThrow();

        assertEquals(rear,copied.channelMatrix());
        assertEquals(rear,((OutputNode)GraphCodec.fromMap(GraphCodec.toMap(route)).nodes().get(1)).channelMatrix());
    }

    private static AudioGraph graph(String id,String name,String group){return new AudioGraph(id,name,List.of(
            new SourceNode(id+"-source","Chrome","application:chrome",STEREO),
            new GainNode(id+"-gain","Chrome volume",STEREO,.7,false),
            new OutputNode(id+"-output","Monitor","device:monitor",STEREO)),List.of(
            new GraphConnection(id+"-link-a",id+"-source","out",id+"-gain","in",true,1),
            new GraphConnection(id+"-link-b",id+"-gain","out",id+"-output","in",true,1)));}
}
