package io.github.ilyaandreev05.audiomatrix.graph;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GraphValidatorTest {
    private static final List<String> STEREO=List.of("FL","FR");

    @Test void sourceGainAndOutputFormAValidGraph(){
        AudioGraph graph=new AudioGraph("route","Desk",List.of(
                new SourceNode("browser","Browser","application:chrome",STEREO),
                new GainNode("browser-gain","Browser volume",STEREO,0.72,false),
                new OutputNode("monitor","Monitor","device:hdmi",STEREO)),List.of(
                edge("a","browser","out","browser-gain","in"),edge("b","browser-gain","out","monitor","in")));
        assertEquals(List.of(),GraphValidator.validate(graph));
    }

    @Test void manySourcesNeedAnExplicitMixerBeforeOneOutput(){
        AudioGraph graph=new AudioGraph("route","Mix",List.of(
                new SourceNode("a","A","app:a",STEREO),new SourceNode("b","B","app:b",STEREO),
                new OutputNode("out","Output","device:one",STEREO)),List.of(
                edge("a1","a","out","out","in"),edge("b1","b","out","out","in")));
        assertTrue(GraphValidator.validate(graph).stream().anyMatch(message->message.contains("explicit Mixer")));
    }

    @Test void explicitMixerAcceptsSeveralIndependentInputs(){
        AudioGraph graph=new AudioGraph("route","Mix",List.of(
                new SourceNode("a","A","app:a",STEREO),new SourceNode("b","B","app:b",STEREO),
                new MixerNode("mix","Mixer",STEREO,Map.of("a-in",0.7,"b-in",0.5)),
                new OutputNode("out","Output","device:one",STEREO)),List.of(
                edge("a1","a","out","mix","a-in"),edge("b1","b","out","mix","b-in"),edge("m1","mix","out","out","in")));
        assertEquals(List.of(),GraphValidator.validate(graph));
    }

    @Test void incompatibleChannelsAndFeedbackCyclesAreRejected(){
        AudioGraph graph=new AudioGraph("route","Broken",List.of(
                new GainNode("a","A",STEREO,1,false),new GainNode("b","B",List.of("MONO"),1,false)),List.of(
                edge("ab","a","out","b","in"),edge("ba","b","out","a","in")));
        List<String> errors=GraphValidator.validate(graph);
        assertTrue(errors.stream().anyMatch(message->message.contains("Channel layout mismatch")));
        assertTrue(errors.stream().anyMatch(message->message.contains("feedback cycle")));
    }

    @Test void duplicateEnabledPortConnectionsAreRejectedBeforeTheyCanDoubleTheSignal(){
        AudioGraph graph=new AudioGraph("route","Duplicate",List.of(
                new SourceNode("source","Browser","application:browser",STEREO),
                new OutputNode("output","Output","device:one",STEREO)),List.of(
                edge("first","source","out","output","in"),edge("second","source","out","output","in")));
        assertTrue(GraphValidator.validate(graph).stream().anyMatch(message->message.contains("Duplicate enabled connection")));
    }

    private static GraphConnection edge(String id,String from,String fromPort,String to,String toPort){return new GraphConnection(id,from,fromPort,to,toPort,true,1);}
}
