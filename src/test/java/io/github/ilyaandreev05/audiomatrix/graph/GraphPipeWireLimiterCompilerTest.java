package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.STEREO;
import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.link;
import static org.junit.jupiter.api.Assertions.*;

class GraphPipeWireLimiterCompilerTest {
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

    @Test void sharesOnePostMixLimiterAcrossFrontAndRearPairsOfOnePhysicalDevice(){
        SourceNode source=new SourceNode("source","Applications","application:unassigned",STEREO);
        SwitchNode split=new SwitchNode("split","Family outputs",STEREO,List.of("front","rear"),List.of("in->front","in->rear"));
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
}
