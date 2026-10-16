package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** CRUD and guarded activation for routes, organizational groups, and inactive saved copies. */
public final class GraphRouteLibrary {
    private GraphRouteLibrary() {}

    public static String saveRoute(Map<String, Object> config, AudioGraph graph, String groupId) {
        requireValid(graph);
        List<Map<String, Object>> routes = mutableList(config, "route_library");
        Map<String, Object> encoded = new LinkedHashMap<>(GraphCodec.toMap(graph));
        encoded.put("group_id", groupId == null ? "" : groupId);
        int index = indexOf(routes, graph.id());
        if (index >= 0) routes.set(index, encoded); else routes.add(encoded);
        return graph.id();
    }

    public static void activate(Map<String, Object> config, String routeId) {
        if (indexOf(maps(config.get("route_library")), routeId) < 0)
            throw new IllegalArgumentException("Route does not exist: " + routeId);
        config.put("active_route", routeId);
    }

    public static void renameRoute(Map<String,Object> config,String routeId,String name) {
        if(name==null||name.isBlank())throw new IllegalArgumentException("Route name must not be blank");
        Map<String,Object> route=find(maps(config.get("route_library")),routeId);
        if(route==null)throw new IllegalArgumentException("Route does not exist: "+routeId);
        route.put("name",name);
    }

    public static AudioGraph duplicateRoute(Map<String, Object> config, String routeId, String newName) {
        Map<String, Object> source = find(maps(config.get("route_library")), routeId);
        if (source == null) throw new IllegalArgumentException("Route does not exist: " + routeId);
        AudioGraph original = GraphCodec.fromMap(source);
        String newRouteId = id("route");
        Map<String, String> nodeIds = new HashMap<>();
        original.nodes().forEach(node -> nodeIds.put(node.id(), id(node.type())));
        List<GraphNode> nodes = original.nodes().stream().map(node -> copyNode(node, nodeIds.get(node.id()))).toList();
        List<GraphConnection> edges = original.connections().stream().map(edge -> new GraphConnection(
                id("link"), nodeIds.get(edge.fromNode()), edge.fromPort(), nodeIds.get(edge.toNode()), edge.toPort(), edge.enabled(), edge.gain())).toList();
        AudioGraph copy = new AudioGraph(newRouteId, newName == null || newName.isBlank() ? original.name() + " (копия)" : newName, nodes, edges);
        saveRoute(config, copy, String.valueOf(source.getOrDefault("group_id", "")));
        Map<String,Object> layouts=mutableMap(config,"graph_ui_layout");Map<String,Object> sourceLayout=map(layouts.get(routeId));Map<String,Object> copiedLayout=new LinkedHashMap<>();
        sourceLayout.forEach((oldNode,value)->{String newNode=nodeIds.get(oldNode);if(newNode!=null)copiedLayout.put(newNode,deepValue(value));});
        if(!copiedLayout.isEmpty())layouts.put(newRouteId,copiedLayout);
        Map<String,Object> scales=mutableMap(config,"graph_ui_scale");Object scale=scales.get(routeId);if(scale instanceof Number)scales.put(newRouteId,scale);
        return copy;
    }

    public static void deleteRoute(Map<String, Object> config, String routeId, String replacementRouteId) {
        List<Map<String, Object>> routes = mutableList(config, "route_library");
        if (indexOf(routes, routeId) < 0) throw new IllegalArgumentException("Route does not exist: " + routeId);
        boolean active = routeId.equals(config.get("active_route"));
        if (active && (replacementRouteId == null || routeId.equals(replacementRouteId) || indexOf(routes, replacementRouteId) < 0))
            throw new IllegalArgumentException("Choose another existing route before deleting the active route");
        if (active) config.put("active_route", replacementRouteId);
        routes.removeIf(route -> routeId.equals(route.get("id")));
        mutableMap(config,"graph_ui_layout").remove(routeId);
        mutableMap(config,"graph_ui_scale").remove(routeId);
    }

    public static String createGroup(Map<String, Object> config, String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Group name must not be blank");
        String groupId = id("group");
        mutableList(config, "route_groups").add(Map.of("id", groupId, "name", name));
        return groupId;
    }

    public static void renameGroup(Map<String,Object> config,String groupId,String name) {
        if(name==null||name.isBlank())throw new IllegalArgumentException("Group name must not be blank");
        Map<String,Object> group=find(maps(config.get("route_groups")),groupId);
        if(group==null)throw new IllegalArgumentException("Group does not exist: "+groupId);
        group.put("name",name);
    }

    public static String duplicateGroup(Map<String,Object> config,String groupId,String name) {
        Map<String,Object> source=find(maps(config.get("route_groups")),groupId);
        if(source==null)throw new IllegalArgumentException("Group does not exist: "+groupId);
        String duplicateId=createGroup(config,name==null||name.isBlank()?String.valueOf(source.get("name"))+" (копия)":name);
        List<Map<String,Object>> routes=new ArrayList<>(maps(config.get("route_library")));
        for(Map<String,Object> route:routes)if(groupId.equals(route.get("group_id"))){
            AudioGraph copy=duplicateRoute(config,String.valueOf(route.get("id")),String.valueOf(route.get("name"))+" (копия)");
            find(maps(config.get("route_library")),copy.id()).put("group_id",duplicateId);
        }
        return duplicateId;
    }

    public static void deleteGroup(Map<String, Object> config, String groupId) {
        List<Map<String, Object>> groups = mutableList(config, "route_groups");
        if (indexOf(groups, groupId) < 0) throw new IllegalArgumentException("Group does not exist: " + groupId);
        groups.removeIf(group -> groupId.equals(group.get("id")));
        maps(config.get("route_library")).stream().filter(route -> groupId.equals(route.get("group_id"))).forEach(route -> route.put("group_id", ""));
    }

    public static String saveInactiveCopy(Map<String, Object> config, String routeId, String label) {
        Map<String, Object> route = find(maps(config.get("route_library")), routeId);
        if (route == null) throw new IllegalArgumentException("Route does not exist: " + routeId);
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("id", id("copy")); copy.put("name", label == null || label.isBlank() ? String.valueOf(route.get("name")) : label);
        copy.put("route", deep(route)); copy.put("active", false);
        copy.put("ui_layout",deep(map(mutableMap(config,"graph_ui_layout").get(routeId))));
        Object scale=mutableMap(config,"graph_ui_scale").get(routeId);if(scale instanceof Number)copy.put("ui_scale",scale);
        mutableList(config, "saved_copies").add(copy);
        return String.valueOf(copy.get("id"));
    }

    public static AudioGraph restoreSavedCopy(Map<String,Object> config,String copyId,String newName) {
        Map<String,Object> copy=find(maps(config.get("saved_copies")),copyId);
        if(copy==null)throw new IllegalArgumentException("Saved copy does not exist: "+copyId);
        Object value=copy.get("route");if(!(value instanceof Map<?,?> raw))throw new IllegalArgumentException("Saved copy has no route snapshot");
        Map<String,Object> snapshot=new LinkedHashMap<>();raw.forEach((key,item)->snapshot.put(String.valueOf(key),item));
        AudioGraph graph=GraphCodec.fromMap(snapshot);requireValid(graph);
        List<Map<String,Object>> routes=mutableList(config,"route_library");String temporaryId=id("restore-source");
        Map<String,Object> source=new LinkedHashMap<>(snapshot);source.put("id",temporaryId);routes.add(source);
        Map<String,Object> layouts=mutableMap(config,"graph_ui_layout");Map<String,Object> savedLayout=map(copy.get("ui_layout"));if(!savedLayout.isEmpty())layouts.put(temporaryId,deep(savedLayout));
        Map<String,Object> scales=mutableMap(config,"graph_ui_scale");if(copy.get("ui_scale") instanceof Number scale)scales.put(temporaryId,scale);
        try{return duplicateRoute(config,temporaryId,newName==null||newName.isBlank()?graph.name()+" (копия)":newName);}
        finally{routes.removeIf(route->temporaryId.equals(route.get("id")));layouts.remove(temporaryId);scales.remove(temporaryId);}
    }

    public static void deleteSavedCopy(Map<String,Object> config,String copyId) {
        List<Map<String,Object>> copies=mutableList(config,"saved_copies");if(indexOf(copies,copyId)<0)throw new IllegalArgumentException("Saved copy does not exist: "+copyId);
        copies.removeIf(copy->copyId.equals(copy.get("id")));
    }

    private static GraphNode copyNode(GraphNode node, String id) {
        List<String> channels = node.outputs().isEmpty() ? node.inputs().getFirst().channels() : node.outputs().getFirst().channels();
        return switch (node.type()) {
            case "source" -> new SourceNode(id, node.name(), ((SourceNode) node).selector(), channels);
            case "output" -> new OutputNode(id, node.name(), ((OutputNode) node).deviceId(), channels, ((OutputNode) node).channelMatrix());
            case "gain" -> new GainNode(id, node.name(), channels, ((GainNode) node).gain(), ((GainNode) node).muted());
            case "gate" -> new GateNode(id, node.name(), channels, ((GateNode) node).open());
            case "router" -> { RouterNode router=(RouterNode)node; yield new RouterNode(id,node.name(),channels,channels,router.activeLinks(),router.multiple()); }
            case "switch" -> new SwitchNode(id,node.name(),channels,node.outputs().stream().map(AudioPort::id).toList(),((RouterNode)node).activeLinks());
            case "multi_switch" -> new MultiSwitchNode(id,node.name(),channels,node.outputs().stream().map(AudioPort::id).toList(),((RouterNode)node).activeLinks());
            case "selector" -> new SelectorNode(id,node.name(),channels,node.inputs().stream().map(AudioPort::id).toList(),((RouterNode)node).activeLinks());
            case "matrix" -> new MatrixNode(id,node.name(),channels,node.inputs().stream().map(AudioPort::id).toList(),node.outputs().stream().map(AudioPort::id).toList(),((RouterNode)node).activeLinks());
            case "mixer" -> new MixerNode(id,node.name(),channels,((MixerNode)node).inputGains());
            case "filter" -> { FilterNode filter=(FilterNode)node;Map<String,Object> parameters=new LinkedHashMap<>(filter.parameters());parameters.remove("processor");parameters.remove("enabled");yield new FilterNode(id,node.name(),channels,filter.processor(),filter.enabled(),parameters); }
            default -> throw new IllegalArgumentException("Unsupported graph node type: " + node.type());
        };
    }

    private static void requireValid(AudioGraph graph) {
        List<String> errors = GraphValidator.validate(graph);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
    }
    private static String id(String prefix) { return prefix + "-" + UUID.randomUUID(); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> mutableList(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (!(value instanceof List<?>)) { List<Map<String,Object>> created=new ArrayList<>();config.put(key,created);return created; }
        return (List<Map<String,Object>>)value;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> mutableMap(Map<String,Object> config,String key){Object value=config.get(key);if(value instanceof Map<?,?> map)return (Map<String,Object>)map;Map<String,Object> created=new LinkedHashMap<>();config.put(key,created);return created;}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){if(value instanceof Map<?,?> map)return (Map<String,Object>)map;return Map.of();}
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object value) { return value instanceof List<?> list ? (List<Map<String,Object>>)list : List.of(); }
    private static int indexOf(List<Map<String,Object>> list,String id) { for(int i=0;i<list.size();i++)if(id.equals(list.get(i).get("id")))return i;return -1; }
    private static Map<String,Object> find(List<Map<String,Object>> list,String id) { return list.stream().filter(item->id.equals(item.get("id"))).findFirst().orElse(null); }
    @SuppressWarnings("unchecked") private static Map<String,Object> deep(Map<String,Object> value) {
        Map<String,Object> result=new LinkedHashMap<>();value.forEach((key,item)->result.put(key,deepValue(item)));return result;
    }
    private static Object deepValue(Object value) {
        if(value instanceof Map<?,?> map){Map<String,Object> result=new LinkedHashMap<>();map.forEach((key,item)->result.put(String.valueOf(key),deepValue(item)));return result;}
        if(value instanceof List<?> list){List<Object> result=new ArrayList<>();list.forEach(item->result.add(deepValue(item)));return result;}
        return value;
    }
}
