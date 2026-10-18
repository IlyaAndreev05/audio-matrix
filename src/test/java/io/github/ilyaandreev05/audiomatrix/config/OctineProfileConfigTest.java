package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OctineProfileConfigTest {

    @Test void freshConfigurationShipsThreeProfilesAndOneSharedVibroGraph() {
        Map<String,Object> config = DefaultConfig.create();
        assertEquals(Set.of("monitor","headphones","free"),
                maps(config.get("profiles")).stream().map(profile->profile.get("id")).collect(java.util.stream.Collectors.toSet()));
        assertEquals("octine-monitor", config.get("active_route"));
        AudioGraph common = GraphCommonVibro.graph(config);
        assertEquals(GraphCommonVibro.COMMON_ROUTE_ID, common.id());
        assertTrue(common.nodes().stream().anyMatch(node -> GraphCommonVibro.VIBRO_OUTPUT_ID.equals(node.id())));
        assertEquals(1, common.nodes().stream().filter(node -> GraphCommonVibro.VIBRO_MIXER_ID.equals(node.id())
                && node instanceof MixerNode).count());
        assertEquals(0, GraphValidator.validate(common).size());
        assertEquals(List.of(), ConfigValidator.validate(config));
    }

    @Test void installingPresetsKeepsExistingWorkspaceAndDoesNotActivate() {
        Map<String,Object> config = DefaultConfig.create();
        List<Map<String,Object>> profiles = maps(config.get("profiles"));
        profiles.clear(); profiles.add(DefaultConfig.map("id","desk","name","Desk","mode","hybrid",
                "default_output","","selected_route_id","desk-route"));
        maps(config.get("route_library")).clear();
        GraphRouteLibrary.saveRoute(config,new AudioGraph("desk-route","Desk",List.of(),List.of()),"","desk");
        config.put("active_profile","desk"); config.put("active_route","desk-route");
        config.remove("common_vibro_graph");

        OctineProfileConfig.install(config);

        assertEquals("desk",config.get("active_profile"));
        assertEquals("desk-route",config.get("active_route"));
        assertTrue(maps(config.get("profiles")).stream().anyMatch(profile->"desk".equals(profile.get("id"))));
        assertEquals(3,maps(config.get("profiles")).stream().filter(profile->List.of("monitor","headphones","free")
                .contains(profile.get("id"))).count());
        assertNotNull(GraphCommonVibro.graph(config));
    }

    @Test void unboundProfilesStaySilentAndEventNeverReachesOrdinaryOutputs() {
        Map<String,Object> config = DefaultConfig.create();
        GraphCommonVibro.save(config, replaceSlots(GraphCommonVibro.graph(config),
                Map.of(GraphCommonVibro.VIBRO_OUTPUT_ID,"vibro-device")));
        AudioGraph monitor = replaceSlots(route(config,"octine-monitor"),Map.of(
                "octine-monitor-output","monitor-device"));
        Map<String,Object> compiled = GraphPipeWireCompiler.compile(monitor,config);
        List<Map<String,Object>> routes = list(compiled.get("routes"));
        assertEquals(Set.of("monitor-device","vibro-device"),
                routes.stream().map(route->route.get("to")).collect(java.util.stream.Collectors.toSet()));
        Map<String,Object> eventRoute = routes.stream()
                .filter(route->"octine-event-source".equals(route.get("from"))).findFirst().orElseThrow();
        assertEquals("vibro-device",eventRoute.get("to"));
        assertFalse(eventRoute.containsKey("filters_ref"));
        Map<String,Object> bassRoute = routes.stream()
                .filter(route->GraphCommonVibro.MAIN_SOURCE_ID.equals(route.get("from"))&&"vibro-device".equals(route.get("to")))
                .findFirst().orElseThrow();
        assertTrue(bassRoute.containsKey("filters_ref"),"bass copy must pass the 120 Hz Low-pass");
        Map<String,Object> chain = nested(compiled,"dsp","route_chains");
        assertTrue(chain.toString().contains("low_pass"));
    }

    @Test void sharedVibroParametersSurviveProfileSwitching() {
        Map<String,Object> config = DefaultConfig.create();
        AudioGraph common = GraphCommonVibro.graph(config);
        List<GraphNode> nodes = common.nodes().stream().map(node -> GraphCommonVibro.VIBRO_GAIN_ID.equals(node.id())
                ? new GainNode(node.id(),node.name(),node.inputs().getFirst().channels(),0.23,true) : node).toList();
        GraphCommonVibro.save(config,new AudioGraph(common.id(),common.name(),nodes,common.connections()));
        config.put("active_profile","monitor");
        String route = GraphProfileLibrary.activateProfile(config,"headphones");
        assertEquals("octine-headphones",route);
        GainNode shared = (GainNode)GraphCommonVibro.graph(config).nodes().stream()
                .filter(node->GraphCommonVibro.VIBRO_GAIN_ID.equals(node.id())).findFirst().orElseThrow();
        assertEquals(0.23,shared.gain());
        assertTrue(shared.muted());
    }

    @Test void freeSwitchKeepsMultipleSelectedOutputsAndStillFeedsOnlyTheBassCopyIntoVibro() {
        Map<String,Object> config = DefaultConfig.create();
        GraphCommonVibro.save(config, replaceSlots(GraphCommonVibro.graph(config),
                Map.of(GraphCommonVibro.VIBRO_OUTPUT_ID,"vibro-device")));
        AudioGraph free = replaceSlots(route(config,"octine-free"),Map.of(
                "octine-free-monitor","monitor-device", "octine-free-headphones","headphones-device"));
        SwitchNode chosen = new SwitchNode("octine-free-switch","Switch · Свободный",GraphCommonVibro.CHANNELS,
                List.of("monitor","headphones","new-device"),List.of("in->monitor","in->headphones"));
        List<GraphNode> nodes = free.nodes().stream()
                .map(node->"octine-free-switch".equals(node.id())?chosen:node).toList();
        Map<String,Object> compiled = GraphPipeWireCompiler.compile(
                new AudioGraph(free.id(),free.name(),nodes,free.connections()),config);
        List<Map<String,Object>> routes = list(compiled.get("routes"));
        Set<Object> mainTargets = routes.stream().filter(route->GraphCommonVibro.MAIN_SOURCE_ID.equals(route.get("from")))
                .filter(route->((Number)route.get("volume")).doubleValue()>0)
                .map(route->route.get("to")).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("monitor-device","headphones-device","vibro-device"),mainTargets);
    }

    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value){
        return value instanceof List<?> list ? (List<Map<String,Object>>)list : List.of();
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> list(Object value){
        return value instanceof List<?> list ? (List<Map<String,Object>>)list : List.of();
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> nested(Map<String,Object> value,String...keys){
        Object current=value; for(String key:keys) current=((Map<String,Object>)current).get(key);
        return current instanceof Map<?,?> map ? (Map<String,Object>)map : Map.of();
    }
    @SuppressWarnings("unchecked") private static AudioGraph route(Map<String,Object> config,String routeId){
        return GraphCodec.fromMap(((List<Map<String,Object>>)config.get("route_library")).stream()
                .filter(item->routeId.equals(item.get("id"))).findFirst().orElseThrow());
    }
    private static AudioGraph replaceSlots(AudioGraph graph,Map<String,String> assignments){
        List<GraphNode> nodes=graph.nodes().stream().map(node->node instanceof DeviceSlotNode slot
                && assignments.containsKey(slot.id())
                ? new DeviceSlotNode(slot.id(),slot.name(),assignments.get(slot.id()),slot.inputs().getFirst().channels())
                : node).toList();
        return new AudioGraph(graph.id(),graph.name(),nodes,graph.connections());
    }
}
