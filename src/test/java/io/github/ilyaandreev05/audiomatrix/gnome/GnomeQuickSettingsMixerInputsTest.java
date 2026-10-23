package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Mixer input labels and liveness published to Quick Settings. */
class GnomeQuickSettingsMixerInputsTest {
    private static final List<String> STEREO=List.of("FL","FR");

    @Test void mixerKeepsFriendlyNamesAcrossDisabledEdgesAndRetainedGeneratedInputs() {
        DynamicGeneratorNode generator=new DynamicGeneratorNode("apps","Приложения","application:*",STEREO);
        String port=DynamicInstanceRegistry.stableMixerInputPortId(generator.id(),"player");
        MixerNode mixer=new MixerNode("mixer","Микшер",STEREO,Map.of("static",.3,port,.7),Map.of(port,true));
        SourceNode source=new SourceNode("source","Мои приложения","application:*",STEREO);
        GainNode gain=new GainNode("static-gain","Технический уровень",STEREO,.9,false,source.id());
        AudioGraph graph=new AudioGraph("route","Маршрут",List.of(generator,mixer,source,gain),
                List.of(new GraphConnection("disabled",gain.id(),"out",mixer.id(),"static",false,1)));
        var retained=new DynamicInstanceRegistry.RuntimeInstance(generator.id(),"player",Map.of("gain","gone"),
                "instance",false,false,false,DynamicInstanceRegistry.Confidence.HIGH,
                Map.of("applicationName","Проигрыватель"),Map.of());
        var input=new GnomeQuickSettingsNodeState.Inputs(DefaultConfig.create(),online(List.of(),List.of()),
                List.of(),List.of(),Map.of(new DynamicInstanceRegistry.InstanceAddress(generator.id(),"player"),retained));
        var published=mixerState(graph,mixer,input);
        assertEquals("Мои приложения",published.stream().filter(item->"static".equals(item.get("id"))).findFirst().orElseThrow().get("name"));
        var generated=published.stream().filter(item->port.equals(item.get("id"))).findFirst().orElseThrow();
        assertEquals("Проигрыватель",generated.get("name"));
        assertEquals(false,generated.get("available"));
        assertEquals(.7,generated.get("volume"));
        assertEquals(true,generated.get("muted"));
    }

    @Test void generatedMixerInputRequiresItsLiveRegisteredOwnerAndExactEnabledBoundary() {
        DynamicGeneratorNode generator=new DynamicGeneratorNode("apps","Приложения","application:*",STEREO);
        String port=DynamicInstanceRegistry.stableMixerInputPortId(generator.id(),"player");
        MixerNode mixer=new MixerNode("mixer","Микшер",STEREO,Map.of(port,.7));
        GainNode gain=new GainNode("player-gain","Проигрыватель",STEREO,.8,false);
        var owner=new DynamicInstanceRegistry.RuntimeInstance(generator.id(),"player",Map.of("gain",gain.id()),
                "instance",true,true,true,DynamicInstanceRegistry.Confidence.HIGH,
                Map.of("applicationName","Проигрыватель"),Map.of("display_name","Моя музыка"));
        var owners=Map.of(new DynamicInstanceRegistry.InstanceAddress(generator.id(),"player"),owner);
        var input=new GnomeQuickSettingsNodeState.Inputs(DefaultConfig.create(),online(List.of(),List.of()),List.of(),List.of(),owners);
        AudioGraph graph=new AudioGraph("route","Маршрут",List.of(generator,mixer,gain),
                List.of(edge("live",gain.id(),"out",mixer.id(),port)));
        assertEquals(true,mixerState(graph,mixer,input).getFirst().get("available"));
        assertEquals("Моя музыка",mixerState(graph,mixer,input).getFirst().get("name"));
        AudioGraph disabled=new AudioGraph(graph.id(),graph.name(),graph.nodes(),
                List.of(new GraphConnection("offline",gain.id(),"out",mixer.id(),port,false,1)));
        assertEquals(false,mixerState(disabled,mixer,input).getFirst().get("available"));
        var disconnected=new GnomeQuickSettingsNodeState.Inputs(DefaultConfig.create(),
                new PipeWireBackend.Snapshot(false,"",List.of(),List.of(),List.of(),List.of(),Map.of(),-1,0),
                List.of(),List.of(),owners);
        assertEquals(false,mixerState(graph,mixer,disconnected).getFirst().get("available"));
        AudioGraph orphan=new AudioGraph(graph.id(),graph.name(),List.of(generator,mixer),List.of());
        var unknown=new GnomeQuickSettingsNodeState.Inputs(DefaultConfig.create(),online(List.of(),List.of()),List.of(),List.of());
        var orphanState=mixerState(orphan,mixer,unknown).getFirst();
        assertEquals("Приложение недоступно",orphanState.get("name"));
        assertEquals(false,orphanState.get("available"));
        AudioGraph unrelated=new AudioGraph(graph.id(),graph.name(),graph.nodes(),
                List.of(edge("wrong-owner",generator.id(),"out",mixer.id(),port)));
        assertEquals(false,mixerState(unrelated,mixer,input).getFirst().get("available"));
    }

    @Test void retainedGeneratedInputWithoutAFriendlyNameIsNeverLabeledWithItsKey() {
        DynamicGeneratorNode generator=new DynamicGeneratorNode("apps","Приложения","application:*",STEREO);
        String port=DynamicInstanceRegistry.stableMixerInputPortId(generator.id(),"app:org.example.Player");
        String gainId=DynamicInstanceRegistry.stableNodeId(generator.id(),"app:org.example.Player","gain");
        MixerNode mixer=new MixerNode("mixer","Микшер",STEREO,Map.of(port,.35));
        GainNode gain=new GainNode(gainId,"app:org.example.Player · громкость",STEREO,.35,true);
        AudioGraph graph=new AudioGraph("route","Маршрут",List.of(generator,mixer,gain),
                List.of(edge("boundary",gainId,"out",mixer.id(),port)));
        var retained=new DynamicInstanceRegistry.RuntimeInstance(generator.id(),"app:org.example.Player",
                Map.of("gain",gainId),"instance",false,false,false,DynamicInstanceRegistry.Confidence.HIGH,
                Map.of(),Map.of());
        var input=new GnomeQuickSettingsNodeState.Inputs(DefaultConfig.create(),online(List.of(),List.of()),
                List.of(),List.of(),Map.of(new DynamicInstanceRegistry.InstanceAddress(generator.id(),"app:org.example.Player"),retained));

        var published=mixerState(graph,mixer,input).getFirst();
        assertEquals("Приложение недоступно",published.get("name"),"a retained key is not a label");
        assertEquals(false,published.get("available"));
        assertEquals(.35,published.get("volume"),"the saved level stays published");
    }

    @Test void staticMixerInputKeepsItsSourceNameWhenTheEffectiveGraphDropsTheGeneratorEdge() {
        DynamicGeneratorNode generator=new DynamicGeneratorNode("apps","Все приложения, кроме событий","application:*",STEREO);
        MixerNode mixer=new MixerNode("mixer","Микшер",STEREO,Map.of("applications",1.0,"additional",.5));
        AudioGraph saved=new AudioGraph("route","Маршрут",List.of(generator,mixer),
                List.of(edge("main-input",generator.id(),"out",mixer.id(),"applications")));
        // The reconciler drops every edge that touches a generator: the effective graph keeps only
        // the runtime boundary links, so the static source name must come from the saved topology.
        AudioGraph effective=new AudioGraph(saved.id(),saved.name(),saved.nodes(),List.of());
        Map<String,Object> config=DefaultConfig.create();
        config.put("route_library",new ArrayList<>(List.of(new LinkedHashMap<>(GraphCodec.toMap(saved)))));
        var input=new GnomeQuickSettingsNodeState.Inputs(config,online(List.of(),List.of()),List.of(),List.of());

        var published=mixerState(effective,mixer,input);
        var applications=published.stream().filter(item->"applications".equals(item.get("id"))).findFirst().orElseThrow();
        assertEquals("Все приложения, кроме событий",applications.get("name"),"a saved source name outlives reconciliation");
        assertEquals(true,applications.get("available"),"a saved static input stays editable");
        var additional=published.stream().filter(item->"additional".equals(item.get("id"))).findFirst().orElseThrow();
        assertEquals("Вход микшера",additional.get("name"),"an unnamed static port keeps the generic label, not its id");
    }

    private static List<Map<String,Object>> mixerState(AudioGraph graph,MixerNode mixer,GnomeQuickSettingsNodeState.Inputs input){
        return maps(GnomeQuickSettingsNodeState.describe(Map.of("id","mixer-pin","route_id",graph.id()),
                mixer,graph,input,List.of()).get("mixerInputs"));
    }

    private static PipeWireBackend.Snapshot online(List<AudioNode> sinks,List<AudioNode> streams){return new PipeWireBackend.Snapshot(true,"",sinks,List.of(),streams,List.of(),Map.of(),sinks.isEmpty()?-1:sinks.getFirst().id(),0);}
    private static GraphConnection edge(String id,String from,String fromPort,String to,String toPort){return new GraphConnection(id,from,fromPort,to,toPort,true,1);}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value){return (List<Map<String,Object>>)value;}
}
