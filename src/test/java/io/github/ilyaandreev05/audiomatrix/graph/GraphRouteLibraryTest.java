package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.ConfigValidator;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GraphRouteLibraryTest {
    private static final List<String> STEREO=List.of("FL","FR");

    @Test void savedRoutesDuplicateGroupsAndInactiveCopiesHaveIndependentIds(){
        Map<String,Object> config=DefaultConfig.create();String group=GraphRouteLibrary.createGroup(config,"Рабочее место");
        AudioGraph original=graph("route-one","Монитор",group);GraphRouteLibrary.saveRoute(config,original,group);GraphRouteLibrary.activate(config,original.id());
        @SuppressWarnings("unchecked") Map<String,Object> layouts=(Map<String,Object>)config.get("graph_ui_layout");layouts.put(original.id(),Map.of(original.nodes().getFirst().id(),Map.of("x",120.0,"y",80.0)));
        String copyId=GraphRouteLibrary.saveInactiveCopy(config,original.id(),"Надёжная копия");
        AudioGraph duplicate=GraphRouteLibrary.duplicateRoute(config,original.id(),"Наушники");
        assertNotEquals(original.id(),duplicate.id());assertNotEquals(original.nodes().getFirst().id(),duplicate.nodes().getFirst().id());
        assertEquals(original.id(),config.get("active_route"));assertEquals(Boolean.FALSE,((Map<?,?>)((List<?>)config.get("saved_copies")).getFirst()).get("active"));
        AudioGraph restored=GraphRouteLibrary.restoreSavedCopy(config,copyId,"Восстановленный граф");
        assertNotEquals(original.nodes().getFirst().id(),restored.nodes().getFirst().id());
        @SuppressWarnings("unchecked") Map<String,Object> duplicateLayout=(Map<String,Object>)layouts.get(duplicate.id());
        assertNotNull(duplicateLayout.get(duplicate.nodes().getFirst().id()));
        @SuppressWarnings("unchecked") Map<String,Object> restoredLayout=(Map<String,Object>)layouts.get(restored.id());
        assertNotNull(restoredLayout.get(restored.nodes().getFirst().id()));
        String groupCopy=GraphRouteLibrary.duplicateGroup(config,group,"Второй комплект");
        assertTrue(((List<?>)config.get("route_library")).stream().filter(Map.class::isInstance).map(item->(Map<?,?>)item).anyMatch(item->groupCopy.equals(item.get("group_id"))));
        assertEquals(List.of(),ConfigValidator.validate(config));
        layouts.put(original.id(),Map.of(original.nodes().getFirst().id(),Map.of("x",-1.0,"y",80.0)));
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(problem->problem.contains("graph_ui_layout")));
    }

    @Test void deletingActiveRouteRequiresAnExistingReplacement(){
        Map<String,Object> config=DefaultConfig.create();AudioGraph first=graph("first","First","");AudioGraph second=graph("second","Second","");
        GraphRouteLibrary.saveRoute(config,first,"");GraphRouteLibrary.saveRoute(config,second,"");GraphRouteLibrary.activate(config,first.id());
        assertThrows(IllegalArgumentException.class,()->GraphRouteLibrary.deleteRoute(config,first.id(),null));
        GraphRouteLibrary.deleteRoute(config,first.id(),second.id());assertEquals(second.id(),config.get("active_route"));
    }

    private static AudioGraph graph(String id,String name,String group){return new AudioGraph(id,name,List.of(
            new SourceNode(id+"-source","Chrome","application:chrome",STEREO),
            new GainNode(id+"-gain","Chrome volume",STEREO,.7,false),
            new OutputNode(id+"-output","Monitor","device:monitor",STEREO)),List.of(
            new GraphConnection(id+"-link-a",id+"-source","out",id+"-gain","in",true,1),
            new GraphConnection(id+"-link-b",id+"-gain","out",id+"-output","in",true,1)));}
}
