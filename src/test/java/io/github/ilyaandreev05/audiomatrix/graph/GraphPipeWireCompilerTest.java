package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.STEREO;
import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.link;
import static org.junit.jupiter.api.Assertions.*;

class GraphPipeWireCompilerTest {
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
        assertFalse(compiled.toString().contains("object.id"));
        assertFalse(compiled.toString().contains("link.output.node"));
    }

    @Test void mixerInputGainAndMuteSurviveJsonRoundTripAndMuteOnlyTheirOwnBranch(){
        SourceNode browser=new SourceNode("browser","Chrome","application:chrome",STEREO);
        SourceNode music=new SourceNode("music","Music","application:music",STEREO);
        MixerNode mixer=new MixerNode("mixer","App mixer",STEREO,Map.of("browser-in",.7,"music-in",.4),
                Map.of("browser-in",true,"music-in",false));
        OutputNode output=new OutputNode("monitor","Monitor","monitor",STEREO);
        AudioGraph graph=new AudioGraph("route-mixer","Mixer route",List.of(browser,music,mixer,output),List.of(
                link("browser","out","mixer","browser-in"),link("music","out","mixer","music-in"),
                link("mixer","out","monitor","in")));

        AudioGraph decoded=GraphCodec.fromMap(GraphCodec.toMap(graph));
        MixerNode decodedMixer=(MixerNode)decoded.nodes().stream().filter(MixerNode.class::isInstance).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") List<Map<String,Object>> routes=(List<Map<String,Object>>)
                GraphPipeWireCompiler.compile(decoded,DefaultConfig.create()).get("routes");
        Map<String,Object> browserRoute=routes.stream().filter(route->"browser".equals(route.get("from"))).findFirst().orElseThrow();
        Map<String,Object> musicRoute=routes.stream().filter(route->"music".equals(route.get("from"))).findFirst().orElseThrow();

        assertEquals(Map.of("browser-in",.7,"music-in",.4),decodedMixer.inputGains());
        assertEquals(Map.of("browser-in",true,"music-in",false),decodedMixer.inputMutes());
        assertEquals(0.0,browserRoute.get("volume"));
        assertEquals(true,browserRoute.get("mute"));
        assertEquals(.4,musicRoute.get("volume"));
        assertEquals(false,musicRoute.get("mute"));
    }
}
