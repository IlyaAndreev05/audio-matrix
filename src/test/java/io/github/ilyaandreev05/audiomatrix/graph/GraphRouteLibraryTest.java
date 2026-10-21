package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.ConfigValidator;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GraphRouteLibraryTest {

    @Test void deletingActiveRouteRequiresAnExistingReplacement(){
        Map<String,Object> config=DefaultConfig.create();AudioGraph first=GraphRouteLibraryFixtures.graph("first","First","");AudioGraph second=GraphRouteLibraryFixtures.graph("second","Second","");
        GraphRouteLibrary.saveRoute(config,first,"");GraphRouteLibrary.saveRoute(config,second,"");GraphRouteLibrary.activate(config,first.id());
        @SuppressWarnings("unchecked") Map<String,Object> scales=(Map<String,Object>)config.get("graph_ui_scale");scales.put(first.id(),1.25);
        assertThrows(IllegalArgumentException.class,()->GraphRouteLibrary.deleteRoute(config,first.id(),null));
        GraphRouteLibrary.deleteRoute(config,first.id(),second.id());assertEquals(second.id(),config.get("active_route"));assertFalse(scales.containsKey(first.id()));
    }

    @Test void incompleteRouteCannotReplaceTheActiveRoute(){
        Map<String,Object> config=DefaultConfig.create();
        AudioGraph active=GraphRouteLibraryFixtures.graph("active-route","Active route","");
        GraphRouteLibrary.saveRoute(config,active,"");GraphRouteLibrary.activate(config,active.id());
        AudioGraph empty=new AudioGraph("empty-route","Empty route",List.of(),List.of());
        GraphRouteLibrary.saveRoute(config,empty,"");

        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,
                ()->GraphRouteLibrary.activate(config,empty.id()));

        assertTrue(error.getMessage().contains("at least one source and one output"));
        assertEquals(active.id(),config.get("active_route"));
    }

    @Test void canvasZoomIsRangeCheckedAndMustBelongToASavedRoute(){
        Map<String,Object> config=DefaultConfig.create();AudioGraph route=GraphRouteLibraryFixtures.graph("zoom-route","Zoom route","");GraphRouteLibrary.saveRoute(config,route,"");
        @SuppressWarnings("unchecked") Map<String,Object> scales=(Map<String,Object>)config.get("graph_ui_scale");
        scales.put(route.id(),1.5);assertTrue(ConfigValidator.validate(config).isEmpty());
        scales.put(route.id(),2.1);assertTrue(ConfigValidator.validate(config).stream().anyMatch(problem->problem.contains("graph_ui_scale")));
        scales.clear();scales.put("missing",1.0);assertTrue(ConfigValidator.validate(config).stream().anyMatch(problem->problem.contains("missing route")));
    }

    @Test void routeCopiesKeepTheOutputChannelMatrix(){
        Map<String,Object> config=DefaultConfig.create();
        ChannelMatrix rear=new ChannelMatrix(GraphRouteLibraryFixtures.STEREO,List.of("RL","RR"),List.of(List.of(1.0,0.0),List.of(0.0,1.0)));
        AudioGraph route=new AudioGraph("rear-route","Rear channels",List.of(
                new SourceNode("source","Apps","application:unassigned",GraphRouteLibraryFixtures.STEREO),
                new OutputNode("rear","Wired headphones","device:family17h",GraphRouteLibraryFixtures.STEREO,rear)),
                List.of(new GraphConnection("source-to-rear","source","out","rear","in",true,1)));
        GraphRouteLibrary.saveRoute(config,route,"");

        AudioGraph duplicate=GraphRouteLibrary.duplicateRoute(config,route.id(),"Rear copy");
        OutputNode copied=(OutputNode)duplicate.nodes().stream().filter(OutputNode.class::isInstance).findFirst().orElseThrow();

        assertEquals(rear,copied.channelMatrix());
        assertEquals(rear,((OutputNode)GraphCodec.fromMap(GraphCodec.toMap(route)).nodes().get(1)).channelMatrix());
    }
}
