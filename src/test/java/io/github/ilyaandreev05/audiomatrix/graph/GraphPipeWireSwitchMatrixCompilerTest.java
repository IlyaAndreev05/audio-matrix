package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.STEREO;
import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.link;
import static org.junit.jupiter.api.Assertions.*;

class GraphPipeWireSwitchMatrixCompilerTest {
    @Test void compilesSelectedManyToManyMatrixCellAndIgnoresDisabledCells(){
        SourceNode source=new SourceNode("source","Apps","application:unassigned",STEREO);
        MatrixNode matrix=new MatrixNode("matrix","Routing matrix",STEREO,List.of("in-a","in-b"),List.of("out-a","out-b"),List.of("in-a->out-b"));
        OutputNode unused=new OutputNode("unused","Disabled output","unused-id",STEREO);
        OutputNode output=new OutputNode("output","Output","system_default",STEREO);
        AudioGraph graph=new AudioGraph("route","Matrix selection",List.of(source,matrix,unused,output),List.of(
                link("source","out","matrix","in-a"),link("matrix","out-a","unused","in"),link("matrix","out-b","output","in")));

        Map<String,Object> compiled=GraphPipeWireCompiler.compile(graph,DefaultConfig.create());

        @SuppressWarnings("unchecked") List<Map<String,Object>> routes=(List<Map<String,Object>>)compiled.get("routes");
        // Every cell stays owned by the plan; a disabled cell is compiled as a muted route.
        assertEquals(2,routes.size());
        Map<String,Object> selected=routes.stream().filter(route->"system_default".equals(route.get("to"))).findFirst().orElseThrow();
        Map<String,Object> disabled=routes.stream().filter(route->"unused-id".equals(route.get("to"))).findFirst().orElseThrow();
        assertEquals(1.0,selected.get("volume"));
        assertEquals(false,selected.get("mute"));
        assertEquals(0.0,disabled.get("volume"));
        assertEquals(true,disabled.get("mute"));
        @SuppressWarnings("unchecked") Map<String,Object> profile=(Map<String,Object>)((List<?>)compiled.get("profiles")).getFirst();
        assertEquals(Set.of(selected.get("id"),disabled.get("id")),Set.copyOf((List<?>)profile.get("active_routes")));
        AudioGraph decoded=GraphCodec.fromMap(GraphCodec.toMap(graph));
        assertEquals(List.of("in-a->out-b"),((MatrixNode)decoded.nodes().stream().filter(MatrixNode.class::isInstance).findFirst().orElseThrow()).activeLinks());
    }

    @Test void compilesOnlySelectedSwitchBranchAndAllowsSeveralOutputs(){
        SourceNode source=new SourceNode("source","Apps","application:unassigned",STEREO);
        SwitchNode single=new SwitchNode("single","Single select",STEREO,List.of("to-monitor","to-aux"),List.of("in->to-aux"));
        OutputNode monitor=new OutputNode("monitor","Monitor","monitor-id",STEREO);
        OutputNode aux=new OutputNode("aux","AUX","aux-id",STEREO);
        AudioGraph switchGraph=new AudioGraph("route-switch","Switch",List.of(source,single,monitor,aux),List.of(
                link("source","out","single","in"),link("single","to-monitor","monitor","in"),link("single","to-aux","aux","in")));
        Map<String,Object> switchPlan=GraphPipeWireCompiler.compile(switchGraph,DefaultConfig.create());
        @SuppressWarnings("unchecked") List<Map<String,Object>> switchRoutes=(List<Map<String,Object>>)switchPlan.get("routes");
        // Every branch stays owned by the plan; the unselected branch is compiled as a muted route.
        assertEquals(2,switchRoutes.size());
        Map<String,Object> activeAux=switchRoutes.stream().filter(route->"aux-id".equals(route.get("to"))).findFirst().orElseThrow();
        Map<String,Object> mutedMonitor=switchRoutes.stream().filter(route->"monitor-id".equals(route.get("to"))).findFirst().orElseThrow();
        assertEquals(1.0,activeAux.get("volume"));
        assertEquals(false,activeAux.get("mute"));
        assertEquals(0.0,mutedMonitor.get("volume"));
        assertEquals(true,mutedMonitor.get("mute"));
        @SuppressWarnings("unchecked") Map<String,Object> switchProfile=(Map<String,Object>)((List<?>)switchPlan.get("profiles")).getFirst();
        assertEquals(Set.of(activeAux.get("id"),mutedMonitor.get("id")),Set.copyOf((List<?>)switchProfile.get("active_routes")));

        SwitchNode switched=new SwitchNode("single","Single select",STEREO,List.of("to-monitor","to-aux"),List.of("in->to-monitor"));
        AudioGraph switchedGraph=new AudioGraph("route-switch","Switch",List.of(source,switched,monitor,aux),switchGraph.connections());
        Map<String,Object> switchedPlan=GraphPipeWireCompiler.compile(switchedGraph,DefaultConfig.create());
        @SuppressWarnings("unchecked") List<Map<String,Object>> switchedRoutes=(List<Map<String,Object>>)switchedPlan.get("routes");
        assertEquals(2,switchedRoutes.size());
        Map<String,Object> activeMonitor=switchedRoutes.stream().filter(route->"monitor-id".equals(route.get("to"))).findFirst().orElseThrow();
        Map<String,Object> mutedAux=switchedRoutes.stream().filter(route->"aux-id".equals(route.get("to"))).findFirst().orElseThrow();
        assertEquals(1.0,activeMonitor.get("volume"));
        assertEquals(false,activeMonitor.get("mute"));
        assertEquals(0.0,mutedAux.get("volume"));
        assertEquals(true,mutedAux.get("mute"));
        // Switching the selected branch changes levels only: the route ids stay stable.
        assertEquals(activeAux.get("id"),mutedAux.get("id"));
        assertEquals(activeMonitor.get("id"),mutedMonitor.get("id"));
        assertNotEquals(activeAux.get("id"),activeMonitor.get("id"));
        assertEquals(switchPlan.get("buses"),switchedPlan.get("buses"));
        @SuppressWarnings("unchecked") Map<String,Object> switchedProfile=(Map<String,Object>)((List<?>)switchedPlan.get("profiles")).getFirst();
        assertEquals(Set.of(activeAux.get("id"),activeMonitor.get("id")),Set.copyOf((List<?>)switchedProfile.get("active_routes")));

        SwitchNode multi=new SwitchNode("multi","Multi select",STEREO,List.of("to-monitor","to-aux"),List.of("in->to-monitor","in->to-aux"));
        AudioGraph multiGraph=new AudioGraph("route-multi","Multi",List.of(source,multi,monitor,aux),List.of(
                link("source","out","multi","in"),link("multi","to-monitor","monitor","in"),link("multi","to-aux","aux","in")));
        assertEquals(2,((List<?>)GraphPipeWireCompiler.compile(multiGraph,DefaultConfig.create()).get("routes")).size());
    }
}
