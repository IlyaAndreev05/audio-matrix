package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Profile workspace operations for graph routes, groups, and the single active route. */
public final class GraphProfileLibrary {
    private GraphProfileLibrary() {}

    /** Adds Octine presets without replacing or activating an existing custom profile. */
    public static void installOctineProfiles(Map<String,Object> config) {
        io.github.ilyaandreev05.audiomatrix.config.OctineProfileConfig.install(config);
    }

    /** Creates a new empty graph workspace while carrying over profile-level output preferences. */
    public static Map<String,Object> createProfile(Map<String,Object> config,String templateProfileId,String name) {
        Map<String,Object> source=find(maps(config.get("profiles")),templateProfileId);
        if(source==null)throw new IllegalArgumentException("Template profile does not exist: "+templateProfileId);
        Map<String,Object> created=deep(source);
        created.put("id","profile-"+UUID.randomUUID());
        created.put("name",name==null||name.isBlank()?"Новый профиль":name);
        created.put("selected_route_id","");
        created.put("active_routes",new ArrayList<>());
        created.put("active_buses",new ArrayList<>());
        mutableProfiles(config).add(created);
        return created;
    }

    public static Map<String,Object> duplicateProfile(Map<String,Object> config,String profileId,String name) {
        Map<String,Object> source=find(maps(config.get("profiles")),profileId);
        if(source==null)throw new IllegalArgumentException("Profile does not exist: "+profileId);
        String newProfileId="profile-"+UUID.randomUUID();
        Map<String,Object> duplicate=deep(source);
        duplicate.put("id",newProfileId);
        duplicate.put("name",name==null||name.isBlank()?String.valueOf(source.getOrDefault("name",profileId))+" (копия)":name);
        duplicate.put("selected_route_id","");
        mutableProfiles(config).add(duplicate);

        Map<String,String> groupIds=new LinkedHashMap<>();
        for(Map<String,Object> group:GraphRouteLibrary.groupsForProfile(config,profileId)) {
            String oldId=String.valueOf(group.get("id"));
            String newId=GraphRouteLibrary.createGroup(config,newProfileId,String.valueOf(group.get("name"))+" (копия)");
            groupIds.put(oldId,newId);
        }
        Map<String,String> routeIds=new LinkedHashMap<>();
        for(Map<String,Object> route:List.copyOf(GraphRouteLibrary.routesForProfile(config,profileId))) {
            String oldId=String.valueOf(route.get("id"));
            String oldGroup=String.valueOf(route.getOrDefault("group_id",""));
            String newGroup=groupIds.getOrDefault(oldGroup,"");
            AudioGraph copy=GraphRouteLibrary.duplicateRoute(config,oldId,String.valueOf(route.get("name"))+" (копия)",newProfileId,newGroup);
            routeIds.put(oldId,copy.id());
        }
        String selected=String.valueOf(source.getOrDefault("selected_route_id",""));
        duplicate.put("selected_route_id",routeIds.getOrDefault(selected,""));
        return duplicate;
    }

    /** Switches the sole active route to the profile's remembered route, or stops it if none exists. */
    public static String activateProfile(Map<String,Object> config,String profileId) {
        Map<String,Object> target=find(maps(config.get("profiles")),profileId);
        if(target==null)throw new IllegalArgumentException("Profile does not exist: "+profileId);
        String previousId=GraphRouteLibrary.activeProfileId(config);
        Map<String,Object> previous=find(maps(config.get("profiles")),previousId);
        String activeRoute=String.valueOf(config.getOrDefault("active_route",""));
        if(previous!=null&&!activeRoute.isBlank()&&belongsTo(config,activeRoute,previousId))previous.put("selected_route_id",activeRoute);
        String selected=String.valueOf(target.getOrDefault("selected_route_id",""));
        if(!belongsTo(config,selected,profileId))selected=GraphRouteLibrary.routesForProfile(config,profileId).stream()
                .map(route->String.valueOf(route.get("id"))).findFirst().orElse("");
        target.put("selected_route_id",selected);
        config.put("active_profile",profileId);
        config.put("active_route",selected);
        return selected;
    }

    /**
     * Deletes a profile and its graph workspace. Deleting the active profile requires a
     * replacement profile and either one of its routes or an explicit stopGraph choice.
     */
    public static void deleteProfile(Map<String,Object> config,String profileId,String replacementProfileId,
                                     String replacementRouteId,boolean stopGraph) {
        List<Map<String,Object>> profiles=mutableProfiles(config);
        Map<String,Object> deleted=find(profiles,profileId);
        if(deleted==null)throw new IllegalArgumentException("Profile does not exist: "+profileId);
        if(profiles.size()<=1)throw new IllegalArgumentException("The last profile cannot be deleted");
        boolean active=profileId.equals(config.get("active_profile"));
        if(active) {
            if(replacementProfileId==null||replacementProfileId.isBlank()||profileId.equals(replacementProfileId))
                throw new IllegalArgumentException("Choose another profile before deleting the active profile");
            Map<String,Object> replacement=find(profiles,replacementProfileId);
            if(replacement==null)throw new IllegalArgumentException("Replacement profile does not exist");
            if(stopGraph) {
                config.put("active_profile",replacementProfileId);
                config.put("active_route","");
            } else {
                if(!belongsTo(config,replacementRouteId,replacementProfileId))
                    throw new IllegalArgumentException("Choose a route in the replacement profile or explicitly stop the graph");
                config.put("active_profile",replacementProfileId);
                config.put("active_route",replacementRouteId);
                replacement.put("selected_route_id",replacementRouteId);
            }
        }
        List<String> routeIds=GraphRouteLibrary.routesForProfile(config,profileId).stream().map(route->String.valueOf(route.get("id"))).toList();
        for(String routeId:routeIds)GraphRouteLibrary.deleteRoute(config,routeId,null);
        List<String> groupIds=GraphRouteLibrary.groupsForProfile(config,profileId).stream().map(group->String.valueOf(group.get("id"))).toList();
        for(String groupId:groupIds)GraphRouteLibrary.deleteGroup(config,groupId);
        profiles.removeIf(profile->profileId.equals(profile.get("id")));
    }

    private static boolean belongsTo(Map<String,Object> config,String routeId,String profileId) {
        if(routeId==null||routeId.isBlank())return false;
        return GraphRouteLibrary.routesForProfile(config,profileId).stream().anyMatch(route->routeId.equals(route.get("id")));
    }

    @SuppressWarnings("unchecked") private static List<Map<String,Object>> mutableProfiles(Map<String,Object> config) {
        Object value=config.get("profiles");
        if(!(value instanceof List<?> list))throw new IllegalArgumentException("No profiles exist");
        List<Map<String,Object>> writable=new ArrayList<>((List<Map<String,Object>>)list);
        config.put("profiles",writable);
        return writable;
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value) {
        return value instanceof List<?> list?(List<Map<String,Object>>)list:List.of();
    }
    private static Map<String,Object> find(List<Map<String,Object>> list,String id) {
        return list.stream().filter(item->id!=null&&id.equals(item.get("id"))).findFirst().orElse(null);
    }
    private static Map<String,Object> deep(Map<String,Object> value) {
        Map<String,Object> result=new LinkedHashMap<>();value.forEach((key,item)->result.put(key,deepValue(item)));return result;
    }
    private static Object deepValue(Object value) {
        if(value instanceof Map<?,?> map){Map<String,Object> result=new LinkedHashMap<>();map.forEach((key,item)->result.put(String.valueOf(key),deepValue(item)));return result;}
        if(value instanceof List<?> list){List<Object> result=new ArrayList<>();list.forEach(item->result.add(deepValue(item)));return result;}
        return value;
    }
}
