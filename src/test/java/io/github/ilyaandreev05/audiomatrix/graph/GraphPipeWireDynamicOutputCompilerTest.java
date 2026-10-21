package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.STEREO;
import static io.github.ilyaandreev05.audiomatrix.graph.GraphTestFixtures.link;
import static org.junit.jupiter.api.Assertions.*;

class GraphPipeWireDynamicOutputCompilerTest {
    @Test void compilesOnlySelectedDynamicOutputBranchesFromMultiSwitchActiveLinks(){
        AudioNode monitor=dynamicOutput(51,"alsa_output.monitor","Monitor");
        AudioNode haptics=dynamicOutput(52,"alsa_output.haptics","Haptics");
        String monitorPort=DynamicInstanceRegistry.stableOutputPortId("outputs",monitor.identity().fingerprint());
        String hapticsPort=DynamicInstanceRegistry.stableOutputPortId("outputs",haptics.identity().fingerprint());
        SourceNode source=new SourceNode("source","Applications","application:unassigned",STEREO);
        DynamicGeneratorNode outputGenerator=new DynamicGeneratorNode("outputs","All outputs","device:*",STEREO,
                Map.of("registry","outputs","connect_to",Map.of("node_id","multi","port_id","in")));
        Map<String,List<DynamicInstanceRegistry.InstanceSnapshot>> observations=Map.of("outputs",
                List.of(DynamicInstanceRegistry.output(monitor),DynamicInstanceRegistry.output(haptics)));

        AudioGraph monitorSelectedGraph=new AudioGraph("route-dynamic-outputs","Dynamic output routing",
                List.of(source,outputGenerator,new SwitchNode("multi","MultiSwitch",STEREO,
                        List.of(monitorPort,hapticsPort),List.of("in->"+monitorPort))),
                List.of(link("source","out","multi","in")));
        Map<String,Object> monitorPlan=GraphPipeWireCompiler.compile(
                DynamicInstanceRegistry.reconcile(monitorSelectedGraph,observations),DefaultConfig.create());
        @SuppressWarnings("unchecked") List<Map<String,Object>> monitorRoutes=(List<Map<String,Object>>)monitorPlan.get("routes");
        // Every discovered output keeps its route; the unselected one is compiled muted.
        assertEquals(2,monitorRoutes.size());
        Map<String,Object> monitorRoute=monitorRoutes.stream().filter(route->monitor.name().equals(route.get("to"))).findFirst().orElseThrow();
        Map<String,Object> mutedHaptics=monitorRoutes.stream().filter(route->haptics.name().equals(route.get("to"))).findFirst().orElseThrow();
        assertEquals("source",monitorRoute.get("from"));
        assertEquals(1.0,monitorRoute.get("volume"));
        assertEquals(false,monitorRoute.get("mute"));
        assertEquals(0.0,mutedHaptics.get("volume"));
        assertEquals(true,mutedHaptics.get("mute"));
        @SuppressWarnings("unchecked") Map<String,Object> monitorProfile=(Map<String,Object>)((List<?>)monitorPlan.get("profiles")).getFirst();
        assertEquals(Set.of(monitorRoute.get("id"),mutedHaptics.get("id")),
                Set.copyOf((List<?>)monitorProfile.get("active_routes")));

        AudioGraph hapticsSelectedGraph=new AudioGraph("route-dynamic-outputs","Dynamic output routing",
                List.of(source,outputGenerator,new SwitchNode("multi","MultiSwitch",STEREO,
                        List.of(monitorPort,hapticsPort),List.of("in->"+hapticsPort))),
                List.of(link("source","out","multi","in")));
        Map<String,Object> hapticsPlan=GraphPipeWireCompiler.compile(
                DynamicInstanceRegistry.reconcile(hapticsSelectedGraph,observations),DefaultConfig.create());
        @SuppressWarnings("unchecked") List<Map<String,Object>> hapticsRoutes=(List<Map<String,Object>>)hapticsPlan.get("routes");
        assertEquals(2,hapticsRoutes.size());
        Map<String,Object> hapticsRoute=hapticsRoutes.stream().filter(route->haptics.name().equals(route.get("to"))).findFirst().orElseThrow();
        Map<String,Object> mutedMonitor=hapticsRoutes.stream().filter(route->monitor.name().equals(route.get("to"))).findFirst().orElseThrow();
        assertEquals("source",hapticsRoute.get("from"));
        assertEquals(1.0,hapticsRoute.get("volume"));
        assertEquals(false,hapticsRoute.get("mute"));
        assertEquals(0.0,mutedMonitor.get("volume"));
        assertEquals(true,mutedMonitor.get("mute"));
        // Switching the selected output changes levels only: the route ids stay stable.
        assertEquals(monitorRoute.get("id"),mutedMonitor.get("id"));
        assertEquals(mutedHaptics.get("id"),hapticsRoute.get("id"));
        assertNotEquals(monitorRoute.get("id"),hapticsRoute.get("id"));
        assertEquals(monitorPlan.get("buses"),hapticsPlan.get("buses"));
        assertEquals(monitorPlan.get("applications"),hapticsPlan.get("applications"));
        @SuppressWarnings("unchecked") Map<String,Object> hapticsProfile=(Map<String,Object>)((List<?>)hapticsPlan.get("profiles")).getFirst();
        assertEquals(Set.of(hapticsRoute.get("id"),mutedMonitor.get("id")),
                Set.copyOf((List<?>)hapticsProfile.get("active_routes")));
    }

    @Test void compilesOfflineDynamicGraphWithoutGeneratedPcmSourcesOrOutputs(){
        DynamicGeneratorNode applications=new DynamicGeneratorNode("applications","Applications","application:unassigned",STEREO);
        DynamicGeneratorNode outputs=new DynamicGeneratorNode("outputs","Outputs","device:*",STEREO,
                Map.of("registry","outputs","connect_to",Map.of("node_id","multi","port_id","in")));
        SwitchNode multi=new SwitchNode("multi","MultiSwitch",STEREO,List.of("unassigned"),List.of());
        AudioGraph graph=new AudioGraph("route-offline-control","Offline dynamic routing",List.of(applications,outputs,multi),List.of());

        DynamicInstanceRegistry.ReconcileResult reconciled=DynamicInstanceRegistry.reconcile(graph,Map.of());

        assertTrue(reconciled.instances().isEmpty());
        assertTrue(reconciled.effectiveGraph().nodes().stream().noneMatch(node->node instanceof SourceNode||node instanceof OutputNode));
        Map<String,Object> compiled=GraphPipeWireCompiler.compile(reconciled,DefaultConfig.create());

        assertEquals(List.of(),compiled.get("buses"));
        assertEquals(List.of(),compiled.get("routes"));
        assertEquals(List.of(),compiled.get("applications"));
        assertEquals("",compiled.get("graph_default_bus"));
        @SuppressWarnings("unchecked") Map<String,Object> profile=(Map<String,Object>)((List<?>)compiled.get("profiles")).getFirst();
        assertEquals(List.of(),profile.get("active_buses"));
        assertEquals(List.of(),profile.get("active_routes"));
    }

    private static AudioNode dynamicOutput(int id,String name,String description){
        Map<String,String> properties=Map.of("device.bus-path","pci-0000:01:00.0","device.serial","serial-"+id,
                "device.profile.name","hdmi-stereo","device.port.name","hdmi-output-"+id,"audio.position","FL,FR");
        DeviceIdentity identity=DeviceIdentity.from(properties,2);
        return new AudioNode(id,name,description,"Audio/Sink","running","","","",2,List.of(48_000),1.0,false,null,identity);
    }
}
