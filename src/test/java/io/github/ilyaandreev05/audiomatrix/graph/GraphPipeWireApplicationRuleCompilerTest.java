package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.STEREO;
import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.link;
import static org.junit.jupiter.api.Assertions.*;

class GraphPipeWireApplicationRuleCompilerTest {
    @Test void compilesStableApplicationSelectorsAndMultiOutputBranches(){
        SourceNode browser=new SourceNode("browser","Chrome","application:org.chromium.Chromium",STEREO);
        OutputNode monitor=new OutputNode("monitor","Monitor","monitor-id",STEREO);
        OutputNode aux=new OutputNode("aux","Wired headphones","aux-id",STEREO);
        AudioGraph graph=new AudioGraph("route-browser","Browser outputs",List.of(browser,monitor,aux),List.of(
                link("browser","out","monitor","in"),link("browser","out","aux","in")));
        Map<String,Object> compiled=GraphPipeWireCompiler.compile(graph,DefaultConfig.create());
        assertEquals("",compiled.get("graph_default_bus"));
        assertEquals(2,((List<?>)compiled.get("routes")).size());
        @SuppressWarnings("unchecked") Map<String,Object> rule=(Map<String,Object>)((List<?>)compiled.get("applications")).getFirst();
        assertEquals(Map.of("app_id","org.chromium.Chromium"),rule.get("match"));
        assertEquals("multi-output",((Map<?,?>)((List<?>)compiled.get("profiles")).getFirst()).get("mode"));
    }

    @Test void compilesEventSoundStudioAsAnIsolatedMonitorInput(){
        SourceNode events=new SourceNode("events","Event effects",EventSoundStudioSource.SELECTOR,STEREO);
        OutputNode output=new OutputNode("output","Vibration output","vibration",STEREO);
        AudioGraph graph=new AudioGraph("route-events","Event route",List.of(events,output),List.of(
                link("events","out","output","in")));

        Map<String,Object> compiled=GraphPipeWireCompiler.compile(graph,DefaultConfig.create());

        assertEquals(List.of("events"),compiled.get("event_input_buses"));
        @SuppressWarnings("unchecked") Map<String,Object> eventRule=(Map<String,Object>)((List<?>)compiled.get("applications")).getFirst();
        assertEquals(Map.of("app_id",EventSoundStudioSource.APPLICATION_ID),eventRule.get("match"));
        assertEquals("events",eventRule.get("bus"));
    }
}
