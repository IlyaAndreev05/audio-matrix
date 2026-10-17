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
        Map<String,Object> existing=find(maps(config.get("route_library")),graph.id());
        String profileId=existing==null?activeProfileId(config):String.valueOf(existing.getOrDefault("profile_id",activeProfileId(config)));
        return saveRoute(config,graph,groupId,profileId);
    }

    public static String saveRoute(Map<String,Object> config,AudioGraph graph,String groupId,String profileId) {
        requireValid(graph);
        requireProfile(config,profileId);
        if(groupId!=null&&!groupId.isBlank())requireGroupInProfile(config,groupId,profileId);
        List<Map<String, Object>> routes = mutableList(config, "route_library");
        Map<String, Object> encoded = new LinkedHashMap<>(GraphCodec.toMap(graph));
        encoded.put("group_id", groupId == null ? "" : groupId);
        encoded.put("profile_id",profileId);
        int index = indexOf(routes, graph.id());
        if (index >= 0) routes.set(index, encoded); else routes.add(encoded);
        return graph.id();
    }

    public static void activate(Map<String, Object> config, String routeId) {
        Map<String,Object> route=find(maps(config.get("route_library")),routeId);
        if (route == null)
            throw new IllegalArgumentException("Route does not exist: " + routeId);
        String profileId=activeProfileId(config);
        if(!profileId.equals(route.get("profile_id")))throw new IllegalArgumentException("Only routes in the active profile can be activated");
        config.put("active_route", routeId);
        find(maps(config.get("profiles")),profileId).put("selected_route_id",routeId);
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
        return duplicateRoute(config,routeId,newName,String.valueOf(source.get("profile_id")),String.valueOf(source.getOrDefault("group_id","")));
    }

    public static AudioGraph duplicateRoute(Map<String,Object> config,String routeId,String newName,String targetProfileId,String targetGroupId) {
        Map<String, Object> source = find(maps(config.get("route_library")), routeId);
        if (source == null) throw new IllegalArgumentException("Route does not exist: " + routeId);
        requireProfile(config,targetProfileId);
        if(targetGroupId!=null&&!targetGroupId.isBlank())requireGroupInProfile(config,targetGroupId,targetProfileId);
        AudioGraph original = GraphCodec.fromMap(source);
        String newRouteId = id("route");
        Map<String, String> nodeIds = new HashMap<>();
        original.nodes().forEach(node -> nodeIds.put(node.id(), id(node.type())));
        List<GraphNode> nodes = original.nodes().stream().map(node -> copyNode(node, nodeIds.get(node.id()))).toList();
        List<GraphConnection> edges = original.connections().stream().map(edge -> new GraphConnection(
                id("link"), nodeIds.get(edge.fromNode()), edge.fromPort(), nodeIds.get(edge.toNode()), edge.toPort(), edge.enabled(), edge.gain())).toList();
        AudioGraph copy = new AudioGraph(newRouteId, newName == null || newName.isBlank() ? original.name() + " (копия)" : newName, nodes, edges);
        saveRoute(config, copy, targetGroupId,targetProfileId);
        Map<String,Object> layouts=mutableMap(config,"graph_ui_layout");Map<String,Object> sourceLayout=map(layouts.get(routeId));Map<String,Object> copiedLayout=new LinkedHashMap<>();
        sourceLayout.forEach((oldNode,value)->{String newNode=nodeIds.get(oldNode);if(newNode!=null)copiedLayout.put(newNode,deepValue(value));});
        if(!copiedLayout.isEmpty())layouts.put(newRouteId,copiedLayout);
        Map<String,Object> scales=mutableMap(config,"graph_ui_scale");Object scale=scales.get(routeId);if(scale instanceof Number)scales.put(newRouteId,scale);
        GraphSubgraphLibrary.duplicateRoute(config, routeId, newRouteId, nodeIds);
        duplicateQuickControls(config,routeId,newRouteId,nodeIds);
        return copy;
    }

    public static void deleteRoute(Map<String, Object> config, String routeId, String replacementRouteId) {
        List<Map<String, Object>> routes = mutableList(config, "route_library");
        if (indexOf(routes, routeId) < 0) throw new IllegalArgumentException("Route does not exist: " + routeId);
        boolean active = routeId.equals(config.get("active_route"));
        Map<String,Object> deleted=find(routes,routeId);
        String profileId=String.valueOf(deleted.get("profile_id"));
        Map<String,Object> replacement=find(routes,replacementRouteId);
        if (active && (replacementRouteId == null || routeId.equals(replacementRouteId) || replacement == null
                || !profileId.equals(replacement.get("profile_id"))))
            throw new IllegalArgumentException("Choose another existing route before deleting the active route");
        if (active) {
            config.put("active_route", replacementRouteId);
            find(maps(config.get("profiles")),profileId).put("selected_route_id",replacementRouteId);
        }
        routes.removeIf(route -> routeId.equals(route.get("id")));
        mutableList(config,"quick_controls").removeIf(control->routeId.equals(control.get("route_id")));
        mutableMap(config,"graph_ui_layout").remove(routeId);
        mutableMap(config,"graph_ui_scale").remove(routeId);
        mutableMap(config,"graph_ui_subgraphs").remove(routeId);
        if(!active){
            Map<String,Object> owner=find(maps(config.get("profiles")),profileId);
            if(owner!=null&&routeId.equals(owner.get("selected_route_id")))owner.put("selected_route_id",routes.stream()
                    .filter(route->profileId.equals(route.get("profile_id"))).map(route->String.valueOf(route.get("id"))).findFirst().orElse(""));
        }
    }

    public static String createGroup(Map<String, Object> config, String name) {
        return createGroup(config,activeProfileId(config),name);
    }

    public static String createGroup(Map<String,Object> config,String profileId,String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Group name must not be blank");
        requireProfile(config,profileId);
        String groupId = id("group");
        Map<String,Object> group=new LinkedHashMap<>();group.put("id",groupId);group.put("name",name);group.put("profile_id",profileId);
        mutableList(config, "route_groups").add(group);
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
        String profileId=String.valueOf(source.get("profile_id"));
        String duplicateId=createGroup(config,profileId,name==null||name.isBlank()?String.valueOf(source.get("name"))+" (копия)":name);
        List<Map<String,Object>> routes=new ArrayList<>(maps(config.get("route_library")));
        for(Map<String,Object> route:routes)if(groupId.equals(route.get("group_id"))&&profileId.equals(route.get("profile_id"))){
            duplicateRoute(config,String.valueOf(route.get("id")),String.valueOf(route.get("name"))+" (копия)",profileId,duplicateId);
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
        copy.put("route", deep(route)); copy.put("profile_id",route.get("profile_id")); copy.put("active", false);
        copy.put("ui_layout",deep(map(mutableMap(config,"graph_ui_layout").get(routeId))));
        Object scale=mutableMap(config,"graph_ui_scale").get(routeId);if(scale instanceof Number)copy.put("ui_scale",scale);
        copy.put("ui_subgraphs",GraphSubgraphLibrary.encoded(GraphSubgraphLibrary.forRoute(config,routeId)));
        copy.put("quick_controls",maps(config.get("quick_controls")).stream().filter(control->routeId.equals(control.get("route_id"))).map(GraphRouteLibrary::deep).toList());
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
        String profileId=String.valueOf(copy.getOrDefault("profile_id",snapshot.getOrDefault("profile_id",activeProfileId(config))));
        if(find(maps(config.get("profiles")),profileId)==null)profileId=activeProfileId(config);
        String groupId=String.valueOf(snapshot.getOrDefault("group_id",""));
        Map<String,Object> group=find(maps(config.get("route_groups")),groupId);
        if(group==null||!profileId.equals(group.get("profile_id")))groupId="";
        Map<String,Object> source=new LinkedHashMap<>(snapshot);source.put("id",temporaryId);source.put("profile_id",profileId);source.put("group_id",groupId);routes.add(source);
        Map<String,Object> layouts=mutableMap(config,"graph_ui_layout");Map<String,Object> savedLayout=map(copy.get("ui_layout"));if(!savedLayout.isEmpty())layouts.put(temporaryId,deep(savedLayout));
        Map<String,Object> scales=mutableMap(config,"graph_ui_scale");if(copy.get("ui_scale") instanceof Number scale)scales.put(temporaryId,scale);
        Map<String,Object> subgraphs=mutableMap(config,"graph_ui_subgraphs");Object savedSubgraphs=copy.get("ui_subgraphs");if(savedSubgraphs instanceof List<?> list)subgraphs.put(temporaryId,deepValue(list));
        List<Map<String,Object>> controls=mutableList(config,"quick_controls");
        if(copy.get("quick_controls") instanceof List<?> savedControls)for(Object rawControl:savedControls)if(rawControl instanceof Map<?,?> rawMap){
            Map<String,Object> control=new LinkedHashMap<>();rawMap.forEach((key,item)->control.put(String.valueOf(key),deepValue(item)));
            control.put("id",id("quick"));control.put("route_id",temporaryId);controls.add(control);
        }
        try{return duplicateRoute(config,temporaryId,newName==null||newName.isBlank()?graph.name()+" (копия)":newName,profileId,groupId);}
        finally{routes.removeIf(route->temporaryId.equals(route.get("id")));layouts.remove(temporaryId);scales.remove(temporaryId);subgraphs.remove(temporaryId);controls.removeIf(control->temporaryId.equals(control.get("route_id")));}
    }

    public static void deleteSavedCopy(Map<String,Object> config,String copyId) {
        List<Map<String,Object>> copies=mutableList(config,"saved_copies");if(indexOf(copies,copyId)<0)throw new IllegalArgumentException("Saved copy does not exist: "+copyId);
        copies.removeIf(copy->copyId.equals(copy.get("id")));
    }

    public static String activeProfileId(Map<String,Object> config){
        String active=String.valueOf(config.getOrDefault("active_profile",""));
        return find(maps(config.get("profiles")),active)==null?maps(config.get("profiles")).stream().map(profile->String.valueOf(profile.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("No profile exists")):active;
    }

    public static List<Map<String,Object>> routesForProfile(Map<String,Object> config,String profileId){
        return maps(config.get("route_library")).stream().filter(route->profileId.equals(route.get("profile_id"))).toList();
    }

    public static List<Map<String,Object>> groupsForProfile(Map<String,Object> config,String profileId){
        return maps(config.get("route_groups")).stream().filter(group->profileId.equals(group.get("profile_id"))).toList();
    }

    private static void requireProfile(Map<String,Object> config,String profileId){
        if(find(maps(config.get("profiles")),profileId)==null)throw new IllegalArgumentException("Profile does not exist: "+profileId);
    }

    private static void requireGroupInProfile(Map<String,Object> config,String groupId,String profileId){
        Map<String,Object> group=find(maps(config.get("route_groups")),groupId);
        if(group==null||!profileId.equals(group.get("profile_id")))throw new IllegalArgumentException("Route group does not belong to profile "+profileId);
    }

    private static void duplicateQuickControls(Map<String,Object> config,String fromRoute,String toRoute,Map<String,String> nodeIds){
        List<Map<String,Object>> controls=mutableList(config,"quick_controls");
        List<Map<String,Object>> copies=new ArrayList<>();
        for(Map<String,Object> control:List.copyOf(controls))if(fromRoute.equals(control.get("route_id"))){
            String nodeId=String.valueOf(control.get("node_id"));String newNodeId=nodeIds.get(nodeId);if(newNodeId==null)continue;
            Map<String,Object> copy=deep(control);copy.put("id",id("quick"));copy.put("route_id",toRoute);copy.put("node_id",newNodeId);copies.add(copy);
        }
        controls.addAll(copies);
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
    private static Map<String,Object> find(List<Map<String,Object>> list,String id) { return id==null?null:list.stream().filter(item->id.equals(item.get("id"))).findFirst().orElse(null); }
    @SuppressWarnings("unchecked") private static Map<String,Object> deep(Map<String,Object> value) {
        Map<String,Object> result=new LinkedHashMap<>();value.forEach((key,item)->result.put(key,deepValue(item)));return result;
    }
    private static Object deepValue(Object value) {
        if(value instanceof Map<?,?> map){Map<String,Object> result=new LinkedHashMap<>();map.forEach((key,item)->result.put(String.valueOf(key),deepValue(item)));return result;}
        if(value instanceof List<?> list){List<Object> result=new ArrayList<>();list.forEach(item->result.add(deepValue(item)));return result;}
        return value;
    }
}
