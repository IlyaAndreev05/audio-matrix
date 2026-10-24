package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Mixer input labels and liveness published to Quick Settings.
 *
 * <p>A retained generated input keeps its saved level for a later reconnect but is not a current
 * control, so it must not populate the GNOME mixer; static inputs stay visible.</p>
 */
class GnomeQuickSettingsMixerInputsTest {
    private static final List<String> STEREO=List.of("FL","FR");

    @Test void offlineGeneratedInputIsHiddenWhileItsStaticSiblingAndSavedLevelSurvive() {
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
        assertEquals(1,published.size(),"only the static input stays in the current mixer");
        var staticInput=published.getFirst();
        assertEquals("static",staticInput.get("id"));
        assertEquals("Мои приложения",staticInput.get("name"));
        assertEquals(false,staticInput.get("generated"));
        assertEquals(true,staticInput.get("available"),"a static input remains editable");
        assertEquals(.3,staticInput.get("volume"));
        assertTrue(published.stream().noneMatch(item->port.equals(item.get("id"))),
                "a retained offline generated input is not published as a usable row");
        assertEquals(.7,mixer.inputGains().get(port),"the saved level is kept for a later reconnect");
        assertEquals(true,mixer.inputMuted(port),"the saved mute is kept for a later reconnect");
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
        var live=mixerState(graph,mixer,input);
        assertEquals(1,live.size());
        assertEquals(true,live.getFirst().get("available"));
        assertEquals(true,live.getFirst().get("generated"));
        assertEquals("Моя музыка",live.getFirst().get("name"));
        AudioGraph disabled=new AudioGraph(graph.id(),graph.name(),graph.nodes(),
                List.of(new GraphConnection("offline",gain.id(),"out",mixer.id(),port,false,1)));
        assertTrue(mixerState(disabled,mixer,input).isEmpty(),
                "a generated row without an enabled boundary is not a current control");
        var disconnected=new GnomeQuickSettingsNodeState.Inputs(DefaultConfig.create(),
                new PipeWireBackend.Snapshot(false,"",List.of(),List.of(),List.of(),List.of(),Map.of(),-1,0),
                List.of(),List.of(),owners);
        assertTrue(mixerState(graph,mixer,disconnected).isEmpty(),
                "a disconnected PipeWire publishes no generated mixer row");
        AudioGraph orphan=new AudioGraph(graph.id(),graph.name(),List.of(generator,mixer),List.of());
        var unknown=new GnomeQuickSettingsNodeState.Inputs(DefaultConfig.create(),online(List.of(),List.of()),List.of(),List.of());
        assertTrue(mixerState(orphan,mixer,unknown).isEmpty(),
                "a generated row without a registered owner is not published");
        AudioGraph unrelated=new AudioGraph(graph.id(),graph.name(),graph.nodes(),
                List.of(edge("wrong-owner",generator.id(),"out",mixer.id(),port)));
        assertTrue(mixerState(unrelated,mixer,input).isEmpty(),
                "an unrelated boundary owner does not keep the generated row alive");
    }

    @Test void retainedGeneratedInputReturnsWithItsStableIdAndSavedLevelAfterReconnect() {
        DynamicGeneratorNode generator=new DynamicGeneratorNode("apps","Приложения","application:*",STEREO);
        String port=DynamicInstanceRegistry.stableMixerInputPortId(generator.id(),"app:org.example.Player");
        String gainId=DynamicInstanceRegistry.stableNodeId(generator.id(),"app:org.example.Player","gain");
        MixerNode mixer=new MixerNode("mixer","Микшер",STEREO,Map.of(port,.35),Map.of(port,true));
        GainNode gain=new GainNode(gainId,"app:org.example.Player · громкость",STEREO,.35,true);
        AudioGraph graph=new AudioGraph("route","Маршрут",List.of(generator,mixer,gain),
                List.of(edge("boundary",gainId,"out",mixer.id(),port)));
        var retained=new DynamicInstanceRegistry.RuntimeInstance(generator.id(),"app:org.example.Player",
                Map.of("gain",gainId),"instance",false,false,false,DynamicInstanceRegistry.Confidence.HIGH,
                Map.of(),Map.of());
        var address=new DynamicInstanceRegistry.InstanceAddress(generator.id(),"app:org.example.Player");
        var offline=new GnomeQuickSettingsNodeState.Inputs(DefaultConfig.create(),online(List.of(),List.of()),
                List.of(),List.of(),Map.of(address,retained));
        assertTrue(mixerState(graph,mixer,offline).isEmpty(),
                "the retained key never becomes a visible row while offline");

        var reconnected=new DynamicInstanceRegistry.RuntimeInstance(generator.id(),"app:org.example.Player",
                Map.of("gain",gainId),"instance",true,true,true,DynamicInstanceRegistry.Confidence.HIGH,
                Map.of("applicationName","Проигрыватель"),Map.of());
        var online=new GnomeQuickSettingsNodeState.Inputs(DefaultConfig.create(),online(List.of(),List.of()),
                List.of(),List.of(),Map.of(address,reconnected));
        var published=mixerState(graph,mixer,online);
        assertEquals(1,published.size());
        var row=published.getFirst();
        assertEquals(port,row.get("id"),"the same stable port id returns after reconnect");
        assertEquals("Проигрыватель",row.get("name"));
        assertEquals(true,row.get("available"));
        assertEquals(true,row.get("generated"));
        assertEquals(.35,row.get("volume"),"the saved input gain returns unchanged");
        assertEquals(true,row.get("muted"),"the saved input mute returns unchanged");
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
        assertEquals(false,applications.get("generated"));
        var additional=published.stream().filter(item->"additional".equals(item.get("id"))).findFirst().orElseThrow();
        assertEquals("Вход микшера",additional.get("name"),"an unnamed static port keeps the generic label, not its id");
    }

    @Test void offlineOnlyMixerPublishesNoInputsSoItsPinHasNoRow() {
        DynamicGeneratorNode generator=new DynamicGeneratorNode("apps","Приложения","application:*",STEREO);
        String port=DynamicInstanceRegistry.stableMixerInputPortId(generator.id(),"player");
        MixerNode mixer=new MixerNode("mixer","Микшер",STEREO,Map.of(port,.5));
        AudioGraph graph=new AudioGraph("route","Маршрут",List.of(generator,mixer),List.of());
        var retained=new DynamicInstanceRegistry.RuntimeInstance(generator.id(),"player",Map.of(),
                "instance",false,false,false,DynamicInstanceRegistry.Confidence.HIGH,
                Map.of("applicationName","Проигрыватель"),Map.of());
        var input=new GnomeQuickSettingsNodeState.Inputs(DefaultConfig.create(),online(List.of(),List.of()),
                List.of(),List.of(),Map.of(new DynamicInstanceRegistry.InstanceAddress(generator.id(),"player"),retained));
        assertTrue(mixerState(graph,mixer,input).isEmpty(),
                "a mixer pin with no visible input must not create a card or a placeholder");
    }

    private static List<Map<String,Object>> mixerState(AudioGraph graph,MixerNode mixer,GnomeQuickSettingsNodeState.Inputs input){
        return maps(GnomeQuickSettingsNodeState.describe(Map.of("id","mixer-pin","route_id",graph.id()),
                mixer,graph,input,List.of()).get("mixerInputs"));
    }

    private static PipeWireBackend.Snapshot online(List<AudioNode> sinks,List<AudioNode> streams){return new PipeWireBackend.Snapshot(true,"",sinks,List.of(),streams,List.of(),Map.of(),sinks.isEmpty()?-1:sinks.getFirst().id(),0);}
    private static GraphConnection edge(String id,String from,String fromPort,String to,String toPort){return new GraphConnection(id,from,fromPort,to,toPort,true,1);}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value){return (List<Map<String,Object>>)value;}
}
