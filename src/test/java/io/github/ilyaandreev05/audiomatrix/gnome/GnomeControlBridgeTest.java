package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import io.github.ilyaandreev05.audiomatrix.graph.SwitchNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GnomeControlBridgeTest {
    private final AudioNode selected = new AudioNode(12, "alsa_output.usb-test", "USB test output", "Audio/Sink",
            "running", "", "", "", 2, List.of(48000), .5, false, null);

    @Test void publishesOnlyEnabledActiveRoutesThatEndAtSelectedPhysicalOutput() {
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(selected), List.of(),
                List.of(), List.of(), Map.of(), 12, 0);
        Map<String, Object> config = Map.of(
                "devices", List.of(Map.of("id", "usb", "selector", Map.of("kind", "name_equals", "value", selected.name()))),
                "buses", List.of(Map.of("id", "effects")),
                "routes", List.of(
                        Map.of("id", "active-usb", "name", "Transducer", "to", "usb", "channel_map", "mono_sum", "enabled", true),
                        Map.of("id", "disabled-usb", "to", "usb", "channel_map", "stereo", "enabled", false),
                        Map.of("id", "inactive-usb", "to", "usb", "channel_map", "stereo", "enabled", true),
                        Map.of("id", "bus-route", "to", "effects", "channel_map", "stereo", "enabled", true),
                        Map.of("id", "other-output", "to", "missing", "channel_map", "stereo", "enabled", true)));
        Map<String, Object> profile = Map.of("active_routes", List.of("active-usb", "disabled-usb", "bus-route", "other-output"));

        List<Map<String, Object>> routes = GnomeControlBridge.channelRoutes(snapshot, config, profile, selected, 12,
                List.of(Map.of("id", "usb", "selector", Map.of("kind", "name_equals", "value", selected.name()))));

        assertEquals(List.of(Map.of("id", "active-usb", "name", "Transducer", "channel_map", "mono_sum")), routes);
    }

    @Test void noPhysicalSelectionPublishesNoChannelControls() {
        PipeWireBackend.Snapshot snapshot = PipeWireBackend.Snapshot.offline("Disconnected");
        assertEquals(List.of(), GnomeControlBridge.channelRoutes(snapshot, Map.of("routes", List.of()),
                Map.of("active_routes", List.of()), null, -1, List.of()));
    }

    @Test void hidesTheInternalSystemMixerSinkFromPhysicalOutputControls() {
        AudioNode systemSink = new AudioNode(13, PipeWireBackend.SYSTEM_MIXER_SINK_NAME, "Audio Matrix System Input",
                "Audio/Sink", "running", "", "", "", 2, List.of(48000), .8, false, null);
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(selected, systemSink),
                List.of(), List.of(), List.of(), Map.of(), systemSink.id(), 0);

        List<Map<String, Object>> outputs = GnomeControlBridge.outputDevices(snapshot);

        assertEquals(List.of("alsa_output.usb-test"), outputs.stream().map(output -> output.get("name")).toList());
        assertEquals(false, outputs.getFirst().get("default"));
    }

    @Test void decodesEscapedTabFromGdbusCommandSignal() {
        GnomeControlBridge.Command command = GnomeControlBridge.parseCommandLine(
                "/org/ilya/AudioMatrix: org.ilya.AudioMatrix.Command ('set_channel_map', 'test-route\\tmono_sum')");

        assertEquals(new GnomeControlBridge.Command("set_channel_map", "test-route\tmono_sum"), command);
    }

    @Test void parsesVersionedCommandAndRequestIdFromGdbusSignal() {
        GnomeControlBridge.Command command = GnomeControlBridge.parseCommandLine(
                "/org/ilya/AudioMatrix: org.ilya.AudioMatrix.Command (uint32 2, 'request-17', 'set_output_volume', 'speaker\\t0.35')");

        assertEquals(new GnomeControlBridge.Command(2,"request-17","set_output_volume","speaker\t0.35"),command);
    }

    @Test void parsesMultilineGdbusCommandArguments(){
        assertEquals(new GnomeControlBridge.Command(2,"request-22","open",""),
                GnomeControlBridge.parseCommandArguments(List.of("2","request-22","open","")));
        assertEquals(null,GnomeControlBridge.parseCommandArguments(List.of("2","request-22","open")));
    }

    @Test void versionedCommandAcknowledgesOnlyAfterItsHandlerCompletes(){
        CompletableFuture<Void> operation=new CompletableFuture<>();
        CompletableFuture<GnomeControlBridge.CommandResult> result=GnomeControlBridge.execute(
                new GnomeControlBridge.Command(2,"request-18","set_output_volume","speaker\t0.35"),ignored->operation);

        assertFalse(result.isDone());
        operation.complete(null);
        assertEquals(new GnomeControlBridge.CommandResult("ok","Applied"),result.join());
    }

    @Test void versionedCommandReturnsActionFailuresAndRejectsOldOrUnknownCommands(){
        GnomeControlBridge.CommandResult failed=GnomeControlBridge.execute(
                new GnomeControlBridge.Command(2,"request-19","set_output_volume","speaker\t0.35"),
                ignored->CompletableFuture.failedFuture(new IllegalStateException("output disconnected"))).join();
        AtomicBoolean called=new AtomicBoolean();
        GnomeControlBridge.CommandResult oldApi=GnomeControlBridge.execute(
                new GnomeControlBridge.Command(1,"request-20","open",""),ignored->{called.set(true);return CompletableFuture.completedFuture(null);}).join();
        GnomeControlBridge.CommandResult unknown=GnomeControlBridge.execute(
                new GnomeControlBridge.Command(2,"request-21","delete_everything",""),ignored->{called.set(true);return CompletableFuture.completedFuture(null);}).join();

        assertEquals(new GnomeControlBridge.CommandResult("error","output disconnected"),failed);
        assertEquals("error",oldApi.status());
        assertEquals("error",unknown.status());
        assertFalse(called.get());
    }

    @Test void publishesPinnedControlsByStableParameterIdsForTheActiveRouteOnly(){
        Map<String,Object> config=DefaultConfig.create();List<String> stereo=List.of("FL","FR");
        AudioGraph graph=new AudioGraph("route-active","Desk graph",List.of(new SourceNode("source","Apps","application:unassigned",stereo),new GainNode("gain","Bass level",stereo,.42,false),new OutputNode("output","Monitor","monitor-id",stereo)),List.of(
                new GraphConnection("a","source","out","gain","in",true,1),new GraphConnection("b","gain","out","output","in",true,1)));
        GraphRouteLibrary.saveRoute(config,graph,"");GraphRouteLibrary.activate(config,graph.id());
        config.put("quick_controls",List.of(
                Map.of("id","bass-level","route_id",graph.id(),"node_id","gain","parameter_id","gain","label","Bass","section","Bass / events","order",4),
                Map.of("id","inactive","route_id","another-route","node_id","gain","parameter_id","gain")));

        List<Map<String,Object>> controls=GnomeControlBridge.quickControls(config);

        assertEquals(1,controls.size());assertEquals("bass-level",controls.getFirst().get("id"));assertEquals("slider",controls.getFirst().get("kind"));assertEquals(.42,controls.getFirst().get("value"));assertEquals("Bass / events",controls.getFirst().get("section"));assertEquals(4,controls.getFirst().get("order"));
    }

    @Test void publishesRouterChoicesForPinnedMatrixParameters(){
        Map<String,Object> config=DefaultConfig.create();List<String> stereo=List.of("FL","FR");
        AudioGraph graph=new AudioGraph("route-switch","Output selector",List.of(new SourceNode("source","Apps","application:unassigned",stereo),new SwitchNode("switch","Monitor or AUX",stereo,List.of("monitor","aux"),List.of("in->monitor")),new OutputNode("monitor-output","Monitor","monitor-id",stereo),new OutputNode("aux-output","AUX","aux-id",stereo)),List.of(new GraphConnection("source-switch","source","out","switch","in",true,1),new GraphConnection("switch-monitor","switch","monitor","monitor-output","in",true,1),new GraphConnection("switch-aux","switch","aux","aux-output","in",true,1)));
        GraphRouteLibrary.saveRoute(config,graph,"");GraphRouteLibrary.activate(config,graph.id());
        config.put("quick_controls",List.of(Map.of("id","output-choice","route_id",graph.id(),"node_id","switch","parameter_id","active_links","label","Playback destination")));

        List<Map<String,Object>> controls=GnomeControlBridge.quickControls(config);

        assertEquals(1,controls.size());Map<String,Object> control=controls.getFirst();assertEquals("links",control.get("kind"));assertEquals(List.of("in->monitor"),control.get("value"));assertEquals(List.of(Map.of("id","in->monitor","label","in → monitor"),Map.of("id","in->aux","label","in → aux")),control.get("options"));assertEquals(false,control.get("multiple"));
    }
}
