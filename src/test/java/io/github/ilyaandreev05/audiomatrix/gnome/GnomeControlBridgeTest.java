package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.MatrixNode;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

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

        List<Map<String, Object>> routes = GnomeChannelRouteState.channelRoutes(snapshot, config, profile, selected, 12,
                List.of(Map.of("id", "usb", "selector", Map.of("kind", "name_equals", "value", selected.name()))));

        assertEquals(List.of(Map.of("id", "active-usb", "name", "Transducer", "channel_map", "mono_sum")), routes);
    }

    @Test void noPhysicalSelectionPublishesNoChannelControls() {
        PipeWireBackend.Snapshot snapshot = PipeWireBackend.Snapshot.offline("Disconnected");
        assertEquals(List.of(), GnomeChannelRouteState.channelRoutes(snapshot, Map.of("routes", List.of()),
                Map.of("active_routes", List.of()), null, -1, List.of()));
    }

    @Test void hidesTheInternalSystemMixerSinkFromPhysicalOutputControls() {
        AudioNode systemSink = new AudioNode(13, PipeWireBackend.SYSTEM_MIXER_SINK_NAME, "Audio Matrix System Input",
                "Audio/Sink", "running", "", "", "", 2, List.of(48000), .8, false, null);
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(selected, systemSink),
                List.of(), List.of(), List.of(), Map.of(), systemSink.id(), 0);

        List<Map<String, Object>> outputs = GnomeOutputState.devices(snapshot, List.of());
        assertEquals(1, outputs.size());
        assertEquals(selected, GnomeOutputState.resolve(snapshot.sinks(),
                String.valueOf(outputs.getFirst().get("id")), List.of()));
        assertEquals(false, outputs.getFirst().get("default"));
    }

    @Test void mixerOutputCommandsUseConfiguredDeviceIdsAndResolveAfterReconnect() {
        PipeWireBackend.Snapshot snapshot = new PipeWireBackend.Snapshot(true, "", List.of(selected),
                List.of(), List.of(), List.of(), Map.of(), selected.id(), 0);
        List<Map<String, Object>> devices = List.of(Map.of("id", "monitor-benq", "selector",
                Map.of("kind", "name_equals", "value", selected.name())));

        Map<String, Object> output = GnomeOutputState.devices(snapshot, devices).getFirst();

        assertEquals("device:monitor-benq", output.get("id"));
        assertEquals(selected, GnomeOutputState.resolve(List.of(selected), "device:monitor-benq", devices));
        assertEquals(selected, GnomeOutputState.resolve(List.of(selected), "monitor-benq", devices));
        assertEquals(selected, GnomeOutputState.resolve(List.of(selected), "name:" + selected.name(), List.of()));
    }

    @Test void decodesEscapedTabFromGdbusCommandSignal() {
        GnomeControlBridge.Command command = GnomeControlBridge.parseCommandLine(
                "/org/ilya/AudioMatrix: org.ilya.AudioMatrix.Command "
                        + "(uint32 5, 'clientUUID:requestUUID', '41', 'set_channel_map', 'test-route\\tmono_sum')");

        assertEquals(new GnomeControlBridge.Command(5, "clientUUID:requestUUID", "41",
                "set_channel_map", "test-route\tmono_sum"), command);
    }

    @Test void parsesOnlyTheExactApiV5CommandTuple() {
        assertEquals(new GnomeControlBridge.Command(5, "clientUUID:request-22", "41", "open", ""),
                GnomeControlBridge.parseCommandArguments(
                        List.of("5", "clientUUID:request-22", "41", "open", "")));
        assertNull(GnomeControlBridge.parseCommandArguments(List.of("5", "request-22", "open", "")));
        assertNull(GnomeControlBridge.parseCommandLine(
                "AudioMatrix.Command (uint32 4, 'clientUUID:request-22', '41', 'open', '')"));
        assertNull(GnomeControlBridge.parseCommandLine(
                "AudioMatrix.Command ('open', '')"));
    }

    @Test void parsesVersionedCommandAndRequestIdFromGdbusSignal() {
        GnomeControlBridge.Command command = GnomeControlBridge.parseCommandLine(
                "/org/ilya/AudioMatrix: org.ilya.AudioMatrix.Command "
                        + "(uint32 5, 'clientUUID:request-17', '40', 'set_output_volume', 'speaker\\t0.35')");

        assertEquals(new GnomeControlBridge.Command(5, "clientUUID:request-17", "40",
                "set_output_volume", "speaker\t0.35"), command);
    }

    @Test void parsesMultilineGdbusCommandArguments() {
        assertEquals(new GnomeControlBridge.Command(5, "clientUUID:request-22", "41", "open", ""),
                GnomeControlBridge.parseCommandArguments(List.of("5", "clientUUID:request-22", "41", "open", "")));
        assertNull(GnomeControlBridge.parseCommandArguments(List.of("4", "clientUUID:request-22", "41", "open", "")));
        assertEquals(new GnomeControlBridge.Command(5, "clientUUID:request-23", "41", "select_route", "route-9"),
                GnomeControlBridge.parseCommandArguments(
                        List.of("5", "clientUUID:request-23", "41", "select_route", "route-9")));
    }

    @Test void revisionedGraphEditsRejectStaleSnapshots() {
        GnomeControlBridge.Command command = GnomeControlBridge.parseCommandLine(
                "AudioMatrix.Command (uint32 5, 'clientUUID:request-24', '41', 'select_route', 'route-9')");

        assertEquals(new GnomeControlBridge.Command(5, "clientUUID:request-24", "41", "select_route", "route-9"), command);
        assertTrue(GnomeCommandRevision.isStale(command, 42));
        assertFalse(GnomeCommandRevision.isStale(command, 41));
    }

    @Test void versionedCommandAcknowledgesOnlyAfterItsHandlerCompletes() {
        CompletableFuture<Void> operation = new CompletableFuture<>();
        CompletableFuture<GnomeControlBridge.CommandResult> result = GnomeControlBridge.execute(
                new GnomeControlBridge.Command(5, "clientUUID:request-18", "40",
                        "set_output_volume", "speaker\t0.35"), ignored -> operation);

        assertFalse(result.isDone());
        operation.complete(null);
        assertEquals(new GnomeControlBridge.CommandResult("ok", "Applied"), result.join());
    }

    @Test void versionedCommandReturnsActionFailuresAndRejectsOldOrUnknownCommands() {
        GnomeControlBridge.CommandResult failed = GnomeControlBridge.execute(
                new GnomeControlBridge.Command(5, "clientUUID:request-19", "40",
                        "set_output_volume", "speaker\t0.35"),
                ignored -> CompletableFuture.failedFuture(new IllegalStateException("output disconnected"))).join();
        AtomicBoolean called = new AtomicBoolean();
        GnomeControlBridge.CommandResult oldApi = GnomeControlBridge.execute(
                new GnomeControlBridge.Command(4, "clientUUID:request-20", "40", "open", ""),
                ignored -> { called.set(true); return CompletableFuture.completedFuture(null); }).join();
        GnomeControlBridge.CommandResult unknown = GnomeControlBridge.execute(
                new GnomeControlBridge.Command(5, "clientUUID:request-21", "40",
                        "delete_everything", ""),
                ignored -> { called.set(true); return CompletableFuture.completedFuture(null); }).join();

        assertEquals(new GnomeControlBridge.CommandResult("error", "output disconnected"), failed);
        assertEquals("error", oldApi.status());
        assertEquals("error", unknown.status());
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

        assertEquals(1,controls.size());assertEquals("bass-level",controls.getFirst().get("id"));assertEquals("slider",controls.getFirst().get("kind"));assertEquals(.42,controls.getFirst().get("value"));assertEquals("Bass / events",controls.getFirst().get("section"));assertEquals("Bass / events",controls.getFirst().get("groupId"));assertEquals(4,controls.getFirst().get("order"));
    }

    @Test void publishesOrderedGroupsAndRoutesForTheActiveProfile() {
        Map<String, Object> config = DefaultConfig.create();
        config.put("route_library", new java.util.ArrayList<>());
        config.put("quick_settings", DefaultConfig.map("groups", List.of(
                DefaultConfig.map("id", "custom-sources", "name", "My arbitrary sources", "order", 0),
                DefaultConfig.map("id", "custom-outputs", "name", "My arbitrary outputs", "order", 1))));
        List<Map<String, Object>> groups = QuickSettingsConfig.groups(config);
        groups.getFirst().put("order", 2);
        groups.getLast().put("order", 0);
        AudioGraph route = new AudioGraph("desk-route", "Desk route", List.of(), List.of());
        GraphRouteLibrary.saveRoute(config, route, "");

        assertEquals("custom-outputs", GnomeQuickSettingsState.groups(config).getFirst().get("id"));
        assertEquals(List.of(Map.of("id", "desk-route", "name", "Desk route")), GnomeQuickSettingsState.routes(config));
    }

    @Test void publishesRouterChoicesForPinnedMatrixParameters(){
        Map<String,Object> config=DefaultConfig.create();List<String> stereo=List.of("FL","FR");
        AudioGraph graph=new AudioGraph("route-switch","Output selector",List.of(new SourceNode("source","Apps","application:unassigned",stereo),new SwitchNode("switch","Monitor or AUX",stereo,List.of("monitor","aux"),List.of("in->monitor")),new OutputNode("monitor-output","Monitor","monitor-id",stereo),new OutputNode("aux-output","AUX","aux-id",stereo)),List.of(new GraphConnection("source-switch","source","out","switch","in",true,1),new GraphConnection("switch-monitor","switch","monitor","monitor-output","in",true,1),new GraphConnection("switch-aux","switch","aux","aux-output","in",true,1)));
        GraphRouteLibrary.saveRoute(config,graph,"");GraphRouteLibrary.activate(config,graph.id());
        config.put("quick_controls",List.of(Map.of("id","output-choice","route_id",graph.id(),"node_id","switch","parameter_id","active_links","label","Playback destination")));

        List<Map<String,Object>> controls=GnomeControlBridge.quickControls(config);

        assertEquals(1,controls.size());Map<String,Object> control=controls.getFirst();assertEquals("links",control.get("kind"));assertEquals("switch",control.get("nodeType"));assertEquals(List.of("in->monitor"),control.get("value"));assertEquals(List.of(Map.of("id","in->monitor","label","in → monitor"),Map.of("id","in->aux","label","in → aux")),control.get("options"));assertEquals(true,control.get("multiple"));
    }

    @Test void publishesMatrixViewAndIndependentLinksForPinnedMatrixNode(){
        Map<String,Object> config=DefaultConfig.create();List<String> stereo=List.of("FL","FR");
        AudioGraph graph=new AudioGraph("route-matrix","Matrix route",List.of(
                new SourceNode("source","Apps","application:unassigned",stereo),
                new MatrixNode("matrix","Output matrix",stereo,List.of("apps"),List.of("monitor","aux"),List.of("apps->monitor")),
                new OutputNode("monitor-output","Monitor","monitor-id",stereo),
                new OutputNode("aux-output","AUX","aux-id",stereo)),List.of(
                new GraphConnection("source-matrix","source","out","matrix","apps",true,1),
                new GraphConnection("matrix-monitor","matrix","monitor","monitor-output","in",true,1),
                new GraphConnection("matrix-aux","matrix","aux","aux-output","in",true,1)));
        GraphRouteLibrary.saveRoute(config,graph,"");GraphRouteLibrary.activate(config,graph.id());
        config.put("quick_controls",List.of(Map.of("id","matrix-control","route_id",graph.id(),"node_id","matrix",
                "parameter_id","active_links","label","Outputs","view","ARROWS")));

        Map<String,Object> control=GnomeControlBridge.quickControls(config).getFirst();

        assertEquals("matrix",control.get("nodeType"));assertEquals(true,control.get("multiple"));
        assertEquals("ARROWS",control.get("view"));assertEquals(List.of("apps->monitor"),control.get("value"));
    }
}
