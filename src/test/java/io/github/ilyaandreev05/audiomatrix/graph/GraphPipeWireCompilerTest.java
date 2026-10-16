package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphPipeWireCompilerTest {
    private static final List<String> STEREO=List.of("FL","FR");

    @Test void compilesSourceGainLowPassAndOutputWithoutPersistingPipeWireIds(){
        SourceNode source=new SourceNode("source-main","Applications","application:unassigned",STEREO);
        GainNode gain=new GainNode("gain-main","Sub level",STEREO,.4,false);
        FilterNode lowPass=new FilterNode("filter-low","Low pass",STEREO,"low_pass",true,Map.of("frequency_hz",100.0,"slope_db_oct",24.0));
        OutputNode output=new OutputNode("output-haptic","Vibrodynamic","haptic-output",STEREO);
        AudioGraph graph=new AudioGraph("route-main","Monitor plus haptics",List.of(source,gain,lowPass,output),List.of(
                link("source-main","out","gain-main","in"),link("gain-main","out","filter-low","in"),link("filter-low","out","output-haptic","in")));

        Map<String,Object> compiled=GraphPipeWireCompiler.compile(graph,DefaultConfig.create());

        assertEquals("source-main",compiled.get("graph_default_bus"));
        assertEquals(1,((List<?>)compiled.get("buses")).size());
        @SuppressWarnings("unchecked") Map<String,Object> route=(Map<String,Object>)((List<?>)compiled.get("routes")).getFirst();
        assertEquals("haptic-output",route.get("to"));
        assertEquals(.4,route.get("volume"));
        assertEquals("dsp.route_chains.graph-chain-"+route.get("id"),route.get("filters_ref"));
        assertFalse(compiled.toString().contains("node_id"));
    }

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

    @Test void compilesSelectedManyToManyMatrixCellAndIgnoresDisabledCells(){
        SourceNode source=new SourceNode("source","Apps","application:unassigned",STEREO);
        MatrixNode matrix=new MatrixNode("matrix","Routing matrix",STEREO,List.of("in-a","in-b"),List.of("out-a","out-b"),List.of("in-a->out-b"));
        OutputNode unused=new OutputNode("unused","Disabled output","unused-id",STEREO);
        OutputNode output=new OutputNode("output","Output","system_default",STEREO);
        AudioGraph graph=new AudioGraph("route","Matrix selection",List.of(source,matrix,unused,output),List.of(
                link("source","out","matrix","in-a"),link("matrix","out-a","unused","in"),link("matrix","out-b","output","in")));

        Map<String,Object> compiled=GraphPipeWireCompiler.compile(graph,DefaultConfig.create());

        assertEquals(1,((List<?>)compiled.get("routes")).size());
        @SuppressWarnings("unchecked") Map<String,Object> route=(Map<String,Object>)((List<?>)compiled.get("routes")).getFirst();
        assertEquals("system_default",route.get("to"));
        AudioGraph decoded=GraphCodec.fromMap(GraphCodec.toMap(graph));
        assertEquals(List.of("in-a->out-b"),((MatrixNode)decoded.nodes().stream().filter(MatrixNode.class::isInstance).findFirst().orElseThrow()).activeLinks());
    }

    @Test void switchSelectsExactlyOneOutputAndMultiswitchCanSelectSeveral(){
        SourceNode source=new SourceNode("source","Apps","application:unassigned",STEREO);
        SwitchNode single=new SwitchNode("single","Single select",STEREO,List.of("to-monitor","to-aux"),List.of("in->to-aux"));
        OutputNode monitor=new OutputNode("monitor","Monitor","monitor-id",STEREO);
        OutputNode aux=new OutputNode("aux","AUX","aux-id",STEREO);
        AudioGraph switchGraph=new AudioGraph("route-switch","Switch",List.of(source,single,monitor,aux),List.of(
                link("source","out","single","in"),link("single","to-monitor","monitor","in"),link("single","to-aux","aux","in")));
        Map<String,Object> switchPlan=GraphPipeWireCompiler.compile(switchGraph,DefaultConfig.create());
        assertEquals(1,((List<?>)switchPlan.get("routes")).size());
        @SuppressWarnings("unchecked") Map<String,Object> route=(Map<String,Object>)((List<?>)switchPlan.get("routes")).getFirst();
        assertEquals("aux-id",route.get("to"));

        MultiSwitchNode multi=new MultiSwitchNode("multi","Multi select",STEREO,List.of("to-monitor","to-aux"),List.of("in->to-monitor","in->to-aux"));
        AudioGraph multiGraph=new AudioGraph("route-multi","Multi",List.of(source,multi,monitor,aux),List.of(
                link("source","out","multi","in"),link("multi","to-monitor","monitor","in"),link("multi","to-aux","aux","in")));
        assertEquals(2,((List<?>)GraphPipeWireCompiler.compile(multiGraph,DefaultConfig.create()).get("routes")).size());
    }

    @Test void rejectsTwoPathsFromOneSourceToTheSamePhysicalSink(){
        SourceNode source=new SourceNode("source","Apps","application:unassigned",STEREO);
        OutputNode first=new OutputNode("first","Monitor alias","same-device",STEREO);
        OutputNode second=new OutputNode("second","Second alias","same-device",STEREO);
        AudioGraph graph=new AudioGraph("route","Duplicate sink paths",List.of(source,first,second),List.of(
                link("source","out","first","in"),link("source","out","second","in")));
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->GraphPipeWireCompiler.compile(graph,DefaultConfig.create()));
        assertTrue(error.getMessage().contains("Multiple paths"));
    }

    private static GraphConnection link(String from,String fromPort,String to,String toPort){return new GraphConnection("link-"+from+"-"+to,from,fromPort,to,toPort,true,1);}
}
