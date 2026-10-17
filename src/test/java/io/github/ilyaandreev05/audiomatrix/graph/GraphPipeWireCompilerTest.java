package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;
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

    @Test void compilesPeakLimiterAsOutputStageAfterRouteFilters(){
        SourceNode source=new SourceNode("source-main","Applications","application:unassigned",STEREO);
        FilterNode lowPass=new FilterNode("filter-low","Low pass",STEREO,"low_pass",true,Map.of("frequency_hz",100.0,"slope_db_oct",24.0));
        FilterNode limiter=new FilterNode("limiter","Peak limiter",STEREO,"limiter",true,
                Map.of("ceiling_db",-2.0,"lookahead_ms",5.0,"release_ms",80.0));
        OutputNode output=new OutputNode("output-haptic","Vibrodynamic","haptic-output",STEREO);
        AudioGraph graph=new AudioGraph("route-limited","Limited bass",List.of(source,lowPass,limiter,output),List.of(
                link("source-main","out","filter-low","in"),link("filter-low","out","limiter","in"),
                link("limiter","out","output-haptic","in")));

        Map<String,Object> compiled=GraphPipeWireCompiler.compile(graph,DefaultConfig.create());

        @SuppressWarnings("unchecked") Map<String,Object> route=(Map<String,Object>)((List<?>)compiled.get("routes")).getFirst();
        @SuppressWarnings("unchecked") Map<String,Object> dsp=(Map<String,Object>)compiled.get("dsp");
        @SuppressWarnings("unchecked") Map<String,Object> routeChains=(Map<String,Object>)dsp.get("route_chains");
        @SuppressWarnings("unchecked") Map<String,Object> chain=(Map<String,Object>)routeChains.get(String.valueOf(route.get("filters_ref")).substring("dsp.route_chains.".length()));
        @SuppressWarnings("unchecked") List<Map<String,Object>> filters=(List<Map<String,Object>>)chain.get("filters");
        @SuppressWarnings("unchecked") Map<String,Object> outputLimiters=(Map<String,Object>)dsp.get("output_limiters");
        @SuppressWarnings("unchecked") Map<String,Object> outputLimiter=(Map<String,Object>)outputLimiters.get("haptic-output");

        assertEquals(List.of("low_pass"),filters.stream().map(filter->filter.get("type")).toList());
        assertEquals(-2.0,outputLimiter.get("ceiling_db"));
        assertEquals(5.0,outputLimiter.get("lookahead_ms"));
        assertEquals(80.0,outputLimiter.get("release_ms"));
    }

    @Test void rejectsSignalProcessingAfterAPostMixLimiter(){
        SourceNode source=new SourceNode("source","Applications","application:unassigned",STEREO);
        FilterNode limiter=new FilterNode("limiter","Peak limiter",STEREO,"limiter",true,Map.of("ceiling_db",-1.0));
        GainNode gain=new GainNode("gain","Post limiter gain",STEREO,.8,false);
        OutputNode output=new OutputNode("output","Output","shared-output",STEREO);
        AudioGraph graph=new AudioGraph("route-conflict","Post limiter gain",List.of(source,limiter,gain,output),List.of(
                link("source","out","limiter","in"),link("limiter","out","gain","in"),link("gain","out","output","in")));

        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->GraphPipeWireCompiler.compile(graph,DefaultConfig.create()));
        assertTrue(error.getMessage().contains("final processor before an output"),error.getMessage());
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

        @SuppressWarnings("unchecked") List<Map<String,Object>> routes=(List<Map<String,Object>>)compiled.get("routes");
        assertEquals(2,routes.size());
        Map<String,Object> selected=routes.stream().filter(route->"system_default".equals(route.get("to"))).findFirst().orElseThrow();
        Map<String,Object> inactive=routes.stream().filter(route->"unused-id".equals(route.get("to"))).findFirst().orElseThrow();
        assertEquals(1.0,selected.get("volume"));
        assertEquals(false,selected.get("mute"));
        assertEquals(0.0,inactive.get("volume"));
        assertEquals(true,inactive.get("mute"));
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
        @SuppressWarnings("unchecked") List<Map<String,Object>> switchRoutes=(List<Map<String,Object>>)switchPlan.get("routes");
        assertEquals(2,switchRoutes.size());
        Map<String,Object> activeAux=switchRoutes.stream().filter(route->"aux-id".equals(route.get("to"))).findFirst().orElseThrow();
        Map<String,Object> inactiveMonitor=switchRoutes.stream().filter(route->"monitor-id".equals(route.get("to"))).findFirst().orElseThrow();
        assertEquals(1.0,activeAux.get("volume"));
        assertEquals(0.0,inactiveMonitor.get("volume"));

        SwitchNode switched=new SwitchNode("single","Single select",STEREO,List.of("to-monitor","to-aux"),List.of("in->to-monitor"));
        AudioGraph switchedGraph=new AudioGraph("route-switch","Switch",List.of(source,switched,monitor,aux),switchGraph.connections());
        @SuppressWarnings("unchecked") List<Map<String,Object>> switchedRoutes=(List<Map<String,Object>>)GraphPipeWireCompiler.compile(switchedGraph,DefaultConfig.create()).get("routes");
        assertEquals(switchRoutes.stream().map(route->route.get("id")).toList(),switchedRoutes.stream().map(route->route.get("id")).toList());
        assertEquals(1.0,switchedRoutes.stream().filter(route->"monitor-id".equals(route.get("to"))).findFirst().orElseThrow().get("volume"));
        assertEquals(0.0,switchedRoutes.stream().filter(route->"aux-id".equals(route.get("to"))).findFirst().orElseThrow().get("volume"));

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

    @Test void sendsOneSourceToIndependentFrontAndRearChannelsOfTheSamePhysicalDevice(){
        SourceNode source=new SourceNode("source","Applications","application:unassigned",STEREO);
        OutputNode front=new OutputNode("front","Vibration driver","family17h",STEREO);
        OutputNode rear=new OutputNode("rear","Wired headphones","family17h",STEREO,
                new ChannelMatrix(STEREO,List.of("RL","RR"),List.of(List.of(1.0,0.0),List.of(0.0,1.0))));
        AudioGraph graph=new AudioGraph("route","Family 17h channel pairs",List.of(source,front,rear),List.of(
                link("source","out","front","in"),link("source","out","rear","in")));

        Map<String,Object> compiled=GraphPipeWireCompiler.compile(graph,DefaultConfig.create());

        assertEquals(2,((List<?>)compiled.get("routes")).size());
        @SuppressWarnings("unchecked") List<Map<String,Object>> routes=(List<Map<String,Object>>)compiled.get("routes");
        Map<String,Object> rearRoute=routes.stream().filter(route->route.containsKey("channel_matrix")).findFirst().orElseThrow();
        assertEquals(Map.of("inputs",STEREO,"outputs",List.of("RL","RR"),"coefficients",List.of(List.of(1.0,0.0),List.of(0.0,1.0))),rearRoute.get("channel_matrix"));

        AudioGraph decoded=GraphCodec.fromMap(GraphCodec.toMap(graph));
        OutputNode decodedRear=(OutputNode)decoded.nodes().stream().filter(OutputNode.class::isInstance).map(OutputNode.class::cast).filter(node->node.id().equals("rear")).findFirst().orElseThrow();
        assertEquals(List.of("RL","RR"),decodedRear.channelMatrix().outputs());
    }

    @Test void sharesOnePostMixLimiterAcrossFrontAndRearPairsOfOnePhysicalDevice(){
        SourceNode source=new SourceNode("source","Applications","application:unassigned",STEREO);
        MultiSwitchNode split=new MultiSwitchNode("split","Family outputs",STEREO,List.of("front","rear"),List.of("in->front","in->rear"));
        FilterNode limiter=new FilterNode("limiter","Vibration limiter",STEREO,"limiter",true,
                Map.of("ceiling_db",-2.0,"lookahead_ms",5.0,"release_ms",80.0));
        OutputNode front=new OutputNode("front-output","Green AUX", "family17h",STEREO);
        OutputNode rear=new OutputNode("rear-output","Black AUX", "family17h",STEREO,
                new ChannelMatrix(STEREO,List.of("RL","RR"),List.of(List.of(1.0,0.0),List.of(0.0,1.0))));
        AudioGraph graph=new AudioGraph("family-route","Family channel pairs",List.of(source,split,limiter,front,rear),List.of(
                link("source","out","split","in"),link("split","front","limiter","in"),link("limiter","out","front-output","in"),
                link("split","rear","rear-output","in")));

        Map<String,Object> compiled=GraphPipeWireCompiler.compile(graph,DefaultConfig.create());

        @SuppressWarnings("unchecked") Map<String,Object> dsp=(Map<String,Object>)compiled.get("dsp");
        @SuppressWarnings("unchecked") Map<String,Object> outputLimiters=(Map<String,Object>)dsp.get("output_limiters");
        @SuppressWarnings("unchecked") Map<String,Object> familyLimiter=(Map<String,Object>)outputLimiters.get("family17h");
        assertEquals(-2.0,familyLimiter.get("ceiling_db"));
        assertEquals(2,((List<?>)compiled.get("routes")).size());
    }

    private static GraphConnection link(String from,String fromPort,String to,String toPort){return new GraphConnection("link-"+from+"-"+to,from,fromPort,to,toPort,true,1);}
}
