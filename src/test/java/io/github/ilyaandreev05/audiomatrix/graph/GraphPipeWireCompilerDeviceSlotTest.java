package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphPipeWireCompilerDeviceSlotTest {

    @Test void savedPresetRouteWithEndpointGainKeepsOneConnectionIntoTheSharedMainMixer(){
        Map<String,Object> config=DefaultConfig.create();
        GraphRouteLibrary.saveRoute(config,route(config,"octine-monitor"),"","monitor");
        AudioGraph saved=route(config,"octine-monitor");

        assertTrue(saved.nodes().stream().anyMatch(node->node instanceof GainNode gain&&!gain.endpointNodeId().isBlank()));
        Map<String,Object> compiled=assertDoesNotThrow(()->GraphPipeWireCompiler.compile(saved,config));
        assertEquals(1,((List<?>)compiled.get("buses")).stream()
                .filter(bus->"octine-main-source".equals(((Map<?,?>)bus).get("id"))).count());
    }

    @Test void unboundDeviceSlotsRemainSilentInsteadOfFallingBackToADefaultSink(){
        Map<String,Object> config=DefaultConfig.create();
        AudioGraph free=route(config,"octine-free");

        Map<String,Object> compiled=GraphPipeWireCompiler.compile(free,config);

        assertEquals(List.of(),compiled.get("routes"));
    }

    @Test void boundDeviceSlotRoutesOnlyToItsAssignedPhysicalOutput(){
        Map<String,Object> config=DefaultConfig.create();
        AudioGraph free=route(config,"octine-free");
        SwitchNode freeSwitch=(SwitchNode)free.nodes().stream().filter(node->"octine-free-switch".equals(node.id())).findFirst().orElseThrow();
        List<GraphNode> nodes=withSlot(free,"octine-free-monitor","slot-monitor");
        SwitchNode selected=new SwitchNode(freeSwitch.id(),freeSwitch.name(),freeSwitch.inputs().getFirst().channels(),
                freeSwitch.outputs().stream().map(port->port.id()).toList(),List.of("in->monitor"));
        nodes=nodes.stream().map(node->node.id().equals(selected.id())?selected:node).toList();
        AudioGraph changed=new AudioGraph(free.id(),free.name(),nodes,free.connections());

        Map<String,Object> compiled=GraphPipeWireCompiler.compile(changed,config);
        @SuppressWarnings("unchecked") List<Map<String,Object>> routes=(List<Map<String,Object>>)compiled.get("routes");

        assertEquals(1,routes.size());
        assertEquals("slot-monitor",routes.getFirst().get("to"));
    }

    @Test void rejectsPhysicalOutputSharedWithTheVibroSlot(){
        Map<String,Object> config=DefaultConfig.create();
        GraphCommonVibro.save(config,replaceSlot(GraphCommonVibro.graph(config),
                GraphCommonVibro.VIBRO_OUTPUT_ID,"shared-output"));
        AudioGraph monitor=replaceSlot(route(config,"octine-monitor"),"octine-monitor-output","shared-output");

        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,
                ()->GraphPipeWireCompiler.compile(monitor,config));
        assertTrue(error.getMessage().contains("Vibro slot"),error.getMessage());
    }

    private static AudioGraph route(Map<String,Object> config,String routeId){
        @SuppressWarnings("unchecked") List<Map<String,Object>> routes=(List<Map<String,Object>>)config.get("route_library");
        return GraphCodec.fromMap(routes.stream().filter(item->routeId.equals(item.get("id"))).findFirst().orElseThrow());
    }
    private static AudioGraph replaceSlot(AudioGraph graph,String slotId,String deviceId){
        return new AudioGraph(graph.id(),graph.name(),withSlot(graph,slotId,deviceId),graph.connections());
    }
    private static List<GraphNode> withSlot(AudioGraph graph,String slotId,String deviceId){
        return graph.nodes().stream().map(node->node instanceof DeviceSlotNode slot&&slotId.equals(slot.id())
                ? new DeviceSlotNode(slot.id(),slot.name(),deviceId,slot.inputs().getFirst().channels()):node).toList();
    }
}
