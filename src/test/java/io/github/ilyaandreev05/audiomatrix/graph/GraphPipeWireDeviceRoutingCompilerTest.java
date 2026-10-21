package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.STEREO;
import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.link;
import static org.junit.jupiter.api.Assertions.*;

class GraphPipeWireDeviceRoutingCompilerTest {
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
}
