package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.*;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GnomeQuickSettingsNodeStateTest {
    private static final List<String> STEREO=List.of("FL","FR");


    @Test void nativePresentationLocalizesSystemInputAndHidesInternalDeviceIds() {
        Map<String,Object> system=GnomeQuickSettingsNodeState.presentation(
                Map.of(),"system-input","System input","source");
        assertEquals("Системный звук",system.get("name"));
        Map<String,Object> configured=GnomeQuickSettingsNodeState.presentation(
                Map.of("display_name","System input"),"system-input","source","source");
        assertEquals("Системный звук",configured.get("name"));
        Map<String,Object> internal=GnomeQuickSettingsNodeState.presentation(
                Map.of("display_name","device:6d7a4ff3-7237-4ae8-b171-b9f91dff1dee"),
                "output","BenQ GW2480 HDMI","output");
        assertEquals("BenQ GW2480 HDMI",internal.get("name"));
    }

    @Test void generatedSystemInputUsesRussianDisplayName() {
        DynamicInstanceRegistry.InstanceSnapshot input=DynamicInstanceRegistry.systemInput();
        assertEquals("Системный звук",input.displayName());
        assertEquals("Системный звук",input.runtimeBinding().get("applicationName"));
    }

    @Test void eventSoundStudioUsesProductNameUnlessTheUserSetAnAlias() {
        Map<String,Object> defaults=GnomeQuickSettingsNodeState.presentation(
                Map.of(),UnifiedGraphConfig.EVENT_GAIN_ID,"Громкость событий","gain");
        assertEquals("Event Sound Studio",defaults.get("name"));
        Map<String,Object> custom=GnomeQuickSettingsNodeState.presentation(
                Map.of("display_name","Мои звуки"),UnifiedGraphConfig.EVENT_GAIN_ID,"Громкость событий","gain");
        assertEquals("Мои звуки",custom.get("name"));
    }
    @Test void outputPinsExposeControlsOnlyForACommandableSavedDeviceOrGraphGain() {
        DeviceIdentity identity=DeviceIdentity.from(Map.of("device.serial","monitor-1",
                "device.profile.name","hdmi-stereo","device.port.name","hdmi-output-0","audio.position","FL,FR"),2);
        AudioNode sink=new AudioNode(21,"alsa_output.monitor","BenQ HDMI","Audio/Sink","running","","","",
                2,List.of(48000),.63,true,null,identity);
        Map<String,Object> device=DefaultConfig.map("id","monitor","identity",identity.toMap());
        Map<String,Object> config=DefaultConfig.create();config.put("devices",List.of(device));
        var input=new GnomeQuickSettingsNodeState.Inputs(config,online(List.of(sink),List.of()),List.of(),List.of());

        OutputNode defaultOutput=new OutputNode("default-output","System output","system_default",STEREO);
        AudioGraph defaultGraph=new AudioGraph("default-route","Default route",List.of(defaultOutput),List.of());
        Map<String,Object> defaultPin=DefaultConfig.map("id","default-pin","route_id",defaultGraph.id(),"node_id",defaultOutput.id());
        Map<?,?> defaultState=(Map<?,?>)GnomeQuickSettingsNodeState.describe(
                defaultPin,defaultOutput,defaultGraph,input,List.of()).get("output");
        assertEquals(true,defaultState.get("available"));
        assertFalse(defaultState.containsKey("volume"));
        assertFalse(defaultState.containsKey("muted"));

        DeviceSlotNode defaultSlot=new DeviceSlotNode("default-slot","Default slot","system_default",STEREO,.8,true);
        AudioGraph slotGraph=new AudioGraph("slot-route","Slot route",List.of(defaultSlot),List.of());
        Map<String,Object> slotPin=DefaultConfig.map("id","slot-pin","route_id",slotGraph.id(),"node_id",defaultSlot.id());
        Map<?,?> slotState=(Map<?,?>)GnomeQuickSettingsNodeState.describe(
                slotPin,defaultSlot,slotGraph,input,List.of()).get("output");
        assertFalse(slotState.containsKey("volume"));
        assertFalse(slotState.containsKey("muted"));

        OutputNode savedOutput=new OutputNode("saved-output","Saved output","monitor",STEREO);
        AudioGraph savedGraph=new AudioGraph("saved-route","Saved route",List.of(savedOutput),List.of());
        Map<String,Object> savedPin=DefaultConfig.map("id","saved-pin","route_id",savedGraph.id(),"node_id",savedOutput.id());
        Map<?,?> savedState=(Map<?,?>)GnomeQuickSettingsNodeState.describe(
                savedPin,savedOutput,savedGraph,input,List.of()).get("output");
        assertEquals(.63,savedState.get("volume"));
        assertEquals(true,savedState.get("muted"));

        GainNode gain=new GainNode("output-gain","Output gain",STEREO,.42,true,defaultOutput.id());
        AudioGraph gainedGraph=new AudioGraph(defaultGraph.id(),defaultGraph.name(),List.of(defaultOutput,gain),List.of());
        Map<?,?> gainedState=(Map<?,?>)GnomeQuickSettingsNodeState.describe(
                defaultPin,defaultOutput,gainedGraph,input,List.of()).get("output");
        assertEquals(.42,gainedState.get("volume"));
        assertEquals(true,gainedState.get("muted"));
        assertEquals(gain.id(),gainedState.get("graphGainNodeId"));
    }
    @Test void savedCompositePublishesRealChildControlsWithoutInventingAParentVolume() {
        Map<String,Object> config=DefaultConfig.create();QuickSettingsConfig.pins(config).clear();
        AudioGraph graph=new AudioGraph("composite-route","Составной маршрут",List.of(
                new GainNode("first","Первый",STEREO,.27,false),new GainNode("second","Второй",STEREO,.63,true)),List.of());
        config.put("route_library",new ArrayList<>(List.of(GraphCodec.toMap(graph))));config.put("active_route",graph.id());
        GraphSubgraph composite=GraphSubgraphLibrary.create(config,graph,"Два уровня",List.of("first","second"));
        QuickSettingsConfig.updatePin(config,graph.id(),composite.id(),"","","routing",0,"EXPANDED",null,null,true,false,false);
        Map<String,Object> state=GnomeQuickSettingsPins.build(config,online(List.of(),List.of()),List.of(),List.of(),List.of(),graph,Map.of())
                .stream().filter(pin->composite.id().equals(pin.get("nodeId"))).findFirst().orElseThrow();
        assertEquals("composite",state.get("nodeType"));assertEquals(false,state.get("expanded"));
        assertFalse(state.containsKey("gain"));assertFalse(state.containsKey("sourceGain"));
        List<Map<String,Object>> children=maps(state.get("children"));assertEquals(2,children.size());
        assertEquals(.27,((Map<?,?>)children.getFirst().get("gain")).get("volume"));
        assertEquals(true,((Map<?,?>)children.get(1).get("gain")).get("muted"));
    }

    @Test void generatedChildUsesItsOwnPresentationAndTheCanonicalInstanceGainTarget() {
        Map<String,Object> config=DefaultConfig.create();QuickSettingsConfig.pins(config).clear();
        DynamicGeneratorNode generator=new DynamicGeneratorNode("apps","Приложения","application:*",STEREO);
        AudioGraph graph=new AudioGraph("generated-route","Созданные узлы",List.of(generator),List.of());
        AudioNode stream=new AudioNode(12,"player-stream","Player","Stream/Output/Audio","running","Player","player","org.example.Player",2,List.of(48000),.37,true,null);
        DynamicInstanceRegistry.InstanceSnapshot observed=DynamicInstanceRegistry.application(stream);
        var runtime=DynamicInstanceRegistry.reconcile(graph,Map.of("apps",List.of(observed)),Map.of(),(selector,item)->new DynamicInstanceRegistry.GainState(.37,true));
        Map<String,Object> pin=QuickSettingsConfig.pinNode(config,graph.id(),generator.id(),observed.stableKey(),"gain","applications",4,"EXPANDED");
        Map<String,Object> state=GnomeQuickSettingsGeneratedPins.savedPin(pin,graph.id(),runtime.effectiveGraph(),online(List.of(),List.of(stream)),config,List.of(),List.of(),runtime.instances());
        assertNotNull(state);assertEquals("gain",state.get("nodeType"));assertEquals("gain",state.get("childId"));
        assertEquals(DynamicInstanceRegistry.generatedGain(runtime,"apps",observed.stableKey()).name(),state.get("name"));
        Map<?,?> gain=(Map<?,?>)state.get("gain");assertEquals("apps",gain.get("nodeId"));
        assertEquals(observed.stableKey(),gain.get("instanceKey"));assertEquals(.37,gain.get("volume"));
        QuickSettingsConfig.unpinNode(config,graph.id(),generator.id(),observed.stableKey(),"gain");
        assertNull(GnomeQuickSettingsGeneratedPins.savedPin(pin,graph.id(),runtime.effectiveGraph(),online(List.of(),List.of(stream)),config,List.of(),List.of(),runtime.instances()));
        assertEquals(.37,DynamicInstanceRegistry.generatedGain(runtime,"apps",observed.stableKey()).gain(),.0001);
    }

    @Test void routeOptionResolvesThroughIdentityBoundDeviceAliasAndNeverClaimsAnOfflineRoute() {
        Map<String,Object> config=DefaultConfig.create();
        DeviceIdentity identity=DeviceIdentity.from(Map.of("device.serial","monitor-1",
                "device.profile.name","hdmi-stereo","device.port.name","hdmi-output-0","audio.position","FL,FR"),2);
        AudioNode sink=new AudioNode(21,"alsa_output.monitor","BenQ HDMI","Audio/Sink","running","","","",
                2,List.of(48000),.6,false,null,identity);
        Map<String,Object> device=DefaultConfig.map("id","monitor","name","Мой BenQ","identity",identity.toMap());
        config.put("devices",List.of(device));
        SwitchNode router=new SwitchNode("switch","MultiSwitch",STEREO,List.of("monitor"),List.of("in->monitor"));
        AudioGraph graph=new AudioGraph("route","Маршрут",List.of(new SourceNode("source","Приложения","application:*",STEREO),router,
                new GainNode("gain","Техническая громкость",STEREO,.6,false,"output"),new OutputNode("output","device:opaque","monitor",STEREO)),
                List.of(edge("source-switch","source","out","switch","in"),edge("switch-gain","switch","monitor","gain","in"),edge("gain-output","gain","out","output","in")));
        Map<String,Object> pin=DefaultConfig.map("id","route-pin","route_id",graph.id(),"node_id",router.id(),"group_id","routing","view","EXPANDED");
        var onlineSnapshot=online(List.of(sink),List.of());
        Map<String,Object> output=GnomeOutputState.devices(onlineSnapshot,List.of(device)).getFirst();
        assertEquals("Мой BenQ",output.get("name"),"An online output card resolves its identity-bound alias without a selector");
        var connected=new GnomeQuickSettingsNodeState.Inputs(config,onlineSnapshot,List.of(),List.of());
        Map<?,?> route=(Map<?,?>)GnomeQuickSettingsNodeState.describe(pin,router,graph,connected,List.of()).get("routeControl");
        Map<String,Object> option=maps(route.get("options")).getFirst();assertEquals("Мой BenQ",option.get("label"));assertEquals(true,option.get("available"));
        var missing=new GnomeQuickSettingsNodeState.Inputs(config,online(List.of(),List.of()),List.of(),List.of());
        Map<?,?> absent=(Map<?,?>)GnomeQuickSettingsNodeState.describe(pin,router,graph,missing,List.of()).get("routeControl");
        assertEquals(false,maps(absent.get("options")).getFirst().get("available"));assertEquals(List.of(),absent.get("value"));
        assertEquals(List.of("in->monitor"),router.activeLinks(),"Saved routing remains available for reliable reconnect");
    }

    private static PipeWireBackend.Snapshot online(List<AudioNode> sinks,List<AudioNode> streams){return new PipeWireBackend.Snapshot(true,"",sinks,List.of(),streams,List.of(),Map.of(),sinks.isEmpty()?-1:sinks.getFirst().id(),0);}
    private static GraphConnection edge(String id,String from,String fromPort,String to,String toPort){return new GraphConnection(id,from,fromPort,to,toPort,true,1);}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value){return (List<Map<String,Object>>)value;}
}
