package io.github.ilyaandreev05.audiomatrix.audio;
import org.junit.jupiter.api.Test;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.EventSoundStudioSource;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphPipeWireCompiler;
import io.github.ilyaandreev05.audiomatrix.graph.SwitchNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireApplicationRoutingTest extends PipeWireRouteManagerTestSupport {
@Test
    void unmatchedEventSoundStudioStreamNeverUsesTheGeneralCatchAllButExactRuleCanRouteIt() throws IOException {
        Map<String,Object> config = DefaultConfig.create();
        config.put("active_route", "");
        config.put("graph_default_bus", "main");
        @SuppressWarnings("unchecked") List<Map<String,Object>> routes = (List<Map<String,Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "main-out", "from", "main", "to", "system_default", "enabled", true));
        @SuppressWarnings("unchecked") List<Map<String,Object>> profiles = (List<Map<String,Object>>) config.get("profiles");
        profiles.getFirst().put("active_buses", List.of("main"));
        profiles.getFirst().put("active_routes", List.of("main-out"));
        AudioNode sink = sink(70, "default-sink", "Default output");
        AudioNode eventStream = new AudioNode(71, "event-stream", "Event effects", "Stream/Output/Audio", "running",
                "Event Sound Studio", "pw-cat", PipeWireRouteManager.EVENT_SOUND_STUDIO_APPLICATION_ID,
                2, List.of(48000), 1.0, false, null);
        PipeWireBackend.Snapshot snapshot = snapshotWithStreams(List.of(sink), List.of(eventStream), sink.id());
        PipeWireRouteManager manager = new PipeWireRouteManager();

        PipeWireRouteManager.Plan isolated = manager.plan(config, snapshot);
        assertTrue(isolated.streams().isEmpty(), "an unmatched event stream must remain on its isolated virtual sink");
        assertEquals(List.of("main-out"), isolated.routes().stream().map(PipeWireRouteManager.RoutePlan::id).toList());

        AudioNode previewStream = new AudioNode(72, "preview-stream", "Event Sound Studio preview",
                "Stream/Output/Audio", "running", "Event Sound Studio", "EventSoundStudio", "",
                2, List.of(48000), 1.0, false, null);
        assertFalse(PipeWireRouteManager.isEventSoundStudioStream(previewStream),
                "ordinary Java Sound preview must not be mistaken for the dedicated event stream");
        PipeWireRouteManager.Plan previewPlan = manager.plan(config,
                snapshotWithStreams(List.of(sink), List.of(previewStream), sink.id()));
        assertEquals(List.of("preview-stream"), previewPlan.routedStreamNames(),
                "preview playback continues through the normal application catch-all");

        @SuppressWarnings("unchecked") List<Map<String,Object>> applications = (List<Map<String,Object>>) config.get("applications");
        applications.add(DefaultConfig.map("id", "event-effects", "name", "Event effects", "match",
                DefaultConfig.map("app_id", PipeWireRouteManager.EVENT_SOUND_STUDIO_APPLICATION_ID), "bus", "main",
                "volume", 1.0, "mute", false));
        PipeWireRouteManager.Plan explicitlyRouted = manager.plan(config, snapshot);
        assertEquals(List.of("event-stream"), explicitlyRouted.routedStreamNames());
    }

@Test
    void busEqIsComposedBeforeRouteEqInTheActivePath() throws IOException {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> buses = (List<Map<String, Object>>) config.get("buses");
        buses.stream().filter(bus -> "music".equals(bus.get("id"))).findFirst().orElseThrow()
                .put("filters_ref", "dsp.route_chains.bus_eq");
        @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) config.get("dsp");
        @SuppressWarnings("unchecked") Map<String, Object> chains = (Map<String, Object>) dsp.get("route_chains");
        chains.put("bus_eq", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "low_pass", "frequency_hz", 120.0, "enabled", true))));
        chains.put("route_eq", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "notch", "frequency_hz", 80.0, "gain_db", -2.0, "q", 2.0, "enabled", true))));

        List<Map<String, Object>> filters = new PipeWireRouteManager().routeFilters(
                DefaultConfig.map("id", "music-out", "from", "music", "filters_ref", "dsp.route_chains.route_eq"),
                config);
        assertEquals(List.of("low_pass", "notch"), filters.stream().map(filter -> filter.get("type")).toList());
    }
@Test
void graphEventSourceUsesThePersistentSinkMonitorWithOrWithoutAnActivePulse() throws IOException {
    Map<String,Object> config=DefaultConfig.create();
    var events=new io.github.ilyaandreev05.audiomatrix.graph.SourceNode("events","Event effects",EventSoundStudioSource.SELECTOR,List.of("FL","FR"));
    var output=new io.github.ilyaandreev05.audiomatrix.graph.OutputNode("output","Output","system_default",List.of("FL","FR"));
    var graph=new AudioGraph("event-route","Event route",List.of(events,output),List.of(
            new GraphConnection("event-output","events","out","output","in",true,1)));
    Map<String,Object> compiled=GraphPipeWireCompiler.compile(graph,config);
    AudioNode sink=sink(70,"default-sink","Default output");
    AudioNode eventStream=new AudioNode(71,"pw-cat","Event effects","Stream/Output/Audio","running",
            "Event Sound Studio","pw-cat",EventSoundStudioSource.APPLICATION_ID,2,List.of(48000),1.0,false,null);
    PipeWireRouteManager manager=new PipeWireRouteManager();

    var idle=manager.plan(compiled,snapshotWithStreams(List.of(sink),List.of(),sink.id()));
    var pulsing=manager.plan(compiled,snapshotWithStreams(List.of(sink),List.of(eventStream),sink.id()));

    assertEquals(List.of("audiomatrix_events"),idle.routedStreamNames());
    assertEquals(List.of("audiomatrix_events"),pulsing.routedStreamNames());
    assertEquals("events",pulsing.streams().getFirst().busId());
}
}
