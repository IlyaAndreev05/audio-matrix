package io.github.ilyaandreev05.audiomatrix.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Path;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphValidator;
import io.github.ilyaandreev05.audiomatrix.graph.GraphSubgraph;

public final class ConfigValidator {
    private ConfigValidator() {}

    public static List<String> validate(Map<String, Object> config) {
        List<String> errors = new ArrayList<>();
        if (number(config.get("schema_version"), -1) != 2) errors.add("schema_version must be 2");
        if (!"audio-matrix".equals(config.get("app"))) errors.add("app must be audio-matrix");
        required(config, "active_profile", String.class, errors);
        Map<String, Object> ui = object(config.get("ui"), "ui", errors);
        if (ui != null) {
            if (!Set.of("ru", "en").contains(ui.get("language"))) errors.add("ui.language must be ru or en");
            if (!Set.of("light", "dark", "system").contains(ui.get("theme"))) errors.add("ui.theme must be light, dark, or system");
        }
        Map<String, Object> mixer = object(config.get("mixer"), "mixer", errors);
        if (mixer != null) {
            checkVolume(mixer.get("global_volume"), "mixer.global_volume", errors);
            required(mixer, "global_mute", Boolean.class, errors);
            validateEq(mixer.get("global_eq"), "mixer.global_eq", errors);
        }
        Map<String, Object> engine = object(config.get("engine"), "engine", errors);
        if (engine != null && !"pipewire".equals(engine.get("backend"))) errors.add("engine.backend must be pipewire");
        if (engine != null && engine.containsKey("transition_ms")) {
            double transition = number(engine.get("transition_ms"), Double.NaN);
            if (!Double.isFinite(transition) || transition != Math.rint(transition) || transition < 0 || transition > 1000)
                errors.add("engine.transition_ms must be an integer between 0 and 1000");
        }
        List<Map<String, Object>> devices = list(config.get("devices"), "devices", errors);
        List<Map<String, Object>> applications = list(config.get("applications"), "applications", errors);
        List<Map<String, Object>> buses = list(config.get("buses"), "buses", errors);
        List<Map<String, Object>> routes = list(config.get("routes"), "routes", errors);
        List<Map<String, Object>> profiles = list(config.get("profiles"), "profiles", errors);
        List<Map<String, Object>> calibrations = list(config.get("calibrations"), "calibrations", errors);
        checkUniqueIds(devices, "devices", errors);
        checkUniqueIds(applications, "applications", errors);
        checkUniqueIds(buses, "buses", errors);
        checkUniqueIds(routes, "routes", errors);
        checkUniqueIds(profiles, "profiles", errors);
        checkUniqueIds(calibrations, "calibrations", errors);
        validateVolumes(devices, "devices", errors);
        validateVolumes(applications, "applications", errors);
        validateVolumes(buses, "buses", errors);
        Set<String> busIds = ids(buses);
        Set<String> profileIds = ids(profiles);
        Set<String> deviceIds = deviceIds(devices);
        Set<String> routeIds = ids(routes);
        for (int i = 0; i < devices.size(); i++) {
            Map<String, Object> selector = object(devices.get(i).get("selector"), "devices[" + i + "].selector", errors);
            if (selector != null) {
                if (!Set.of("name_equals", "name_contains", "description_contains").contains(selector.get("kind")))
                    errors.add("devices[" + i + "].selector.kind is unsupported");
                if (!(selector.get("value") instanceof String value) || value.isBlank()) errors.add("devices[" + i + "].selector.value must be non-empty");
            }
            if (devices.get(i).containsKey("name") && (!(devices.get(i).get("name") instanceof String name) || name.isBlank()))
                errors.add("devices[" + i + "].name must be a non-empty string");
            if (devices.get(i).containsKey("role")) {
                Object role = devices.get(i).get("role");
                if (!(role instanceof String value) || !Set.of("output", "source", "input", "speaker", "speakers",
                        "subwoofer", "haptic_output", "microphone", "accelerometer").contains(value))
                    errors.add("devices[" + i + "].role is unsupported");
            }
            if (!(devices.get(i).get("mute") instanceof Boolean)) errors.add("devices[" + i + "].mute must be boolean");
        }
        for (int i = 0; i < applications.size(); i++) {
            Map<String, Object> match = object(applications.get(i).get("match"), "applications[" + i + "].match", errors);
            if (match != null && !(match.get("process") instanceof String) && !(match.get("app_id") instanceof String))
                errors.add("applications[" + i + "].match needs process or app_id");
            if (applications.get(i).containsKey("bus") && !busIds.contains(String.valueOf(applications.get(i).get("bus"))))
                errors.add("applications[" + i + "].bus must reference a bus id");
            if (!(applications.get(i).get("mute") instanceof Boolean)) errors.add("applications[" + i + "].mute must be boolean");
        }
        for (int i = 0; i < buses.size(); i++) {
            if (buses.get(i).containsKey("name") && (!(buses.get(i).get("name") instanceof String name) || name.isBlank()))
                errors.add("buses[" + i + "].name must be a non-empty string");
            Object channels = buses.get(i).get("channels");
            if (!(channels instanceof String value) || !Set.of("mono", "stereo", "5.1", "7.1").contains(value)) errors.add("buses[" + i + "].channels is unsupported");
            if (buses.get(i).containsKey("role")) {
                Object role = buses.get(i).get("role");
                if (!(role instanceof String value) || !Set.of("general", "music", "effects", "subwoofer").contains(value))
                    errors.add("buses[" + i + "].role is unsupported");
            }
            if (!(buses.get(i).get("mute") instanceof Boolean)) errors.add("buses[" + i + "].mute must be boolean");
        }
        if (!profileIds.contains(String.valueOf(config.get("active_profile")))) errors.add("active_profile does not reference a profile id");
        for (int i = 0; i < routes.size(); i++) {
            Map<String, Object> route = routes.get(i);
            String from = String.valueOf(route.getOrDefault("from", ""));
            String to = String.valueOf(route.getOrDefault("to", ""));
            if (!busIds.contains(from)) errors.add("routes[" + i + "].from must reference a bus id");
            if (!busIds.contains(to) && !"system_default".equals(to) && !deviceIds.contains(to))
                errors.add("routes[" + i + "].to must reference a bus, device, or system_default");
            if (route.containsKey("name") && (!(route.get("name") instanceof String name) || name.isBlank()))
                errors.add("routes[" + i + "].name must be a non-empty string");
            checkVolume(route.getOrDefault("volume", 1.0), "routes[" + i + "].volume", errors);
            if (route.containsKey("gain_db")) {
                double gain = number(route.get("gain_db"), Double.NaN);
                if (!Double.isFinite(gain) || gain < -60 || gain > 24) errors.add("routes[" + i + "].gain_db must be between -60 and 24");
            }
            if (route.containsKey("delay_ms")) {
                double delay = number(route.get("delay_ms"), Double.NaN);
                if (!Double.isFinite(delay) || delay < 0 || delay > 2000) errors.add("routes[" + i + "].delay_ms must be between 0 and 2000");
            }
            if (route.containsKey("mute") && !(route.get("mute") instanceof Boolean)) errors.add("routes[" + i + "].mute must be boolean");
            if (route.containsKey("channel_map")) {
                Object channelMap = route.get("channel_map");
                if (!(channelMap instanceof String value) || !Set.of("stereo", "mono", "mono_sum", "left", "right", "swap_lr",
                        "center", "lfe", "rear_left", "rear_right", "rear_sum", "side_left", "side_right", "side_sum", "surround_sum", "all_sum").contains(value))
                    errors.add("routes[" + i + "].channel_map is unsupported");
                else if (!route.containsKey("channel_matrix")) {
                    String sourceBus = String.valueOf(route.getOrDefault("from", ""));
                    String layout = buses.stream().filter(bus -> sourceBus.equals(bus.get("id")))
                            .map(bus -> String.valueOf(bus.getOrDefault("channels", "stereo"))).findFirst().orElse("stereo");
                    if (!supportsChannelMap(value, layout))
                        errors.add("routes[" + i + "].channel_map requires source bus channels unavailable in " + layout);
                }
            }
            if (route.containsKey("channel_matrix")) {
                try {
                    ChannelMatrix matrix = ChannelMatrix.parse(route.get("channel_matrix"));
                    String sourceBusId = String.valueOf(route.getOrDefault("from", ""));
                    String sourceLayout = buses.stream().filter(bus -> sourceBusId.equals(bus.get("id")))
                            .map(bus -> String.valueOf(bus.getOrDefault("channels", "stereo"))).findFirst().orElse("stereo");
                    if (!matrix.inputs().equals(channelPositions(sourceLayout)))
                        errors.add("routes[" + i + "].channel_matrix.inputs must match the source bus channel layout " + sourceLayout);
                    Map<String, Object> targetBus = buses.stream().filter(bus -> to.equals(bus.get("id"))).findFirst().orElse(null);
                    if (targetBus != null) {
                        Set<String> targetPositions = Set.copyOf(channelPositions(String.valueOf(targetBus.getOrDefault("channels", "stereo"))));
                        if (!targetPositions.containsAll(matrix.outputs()))
                            errors.add("routes[" + i + "].channel_matrix.outputs must exist on the target bus");
                    }
                } catch (IllegalArgumentException error) {
                    errors.add("routes[" + i + "].channel_matrix is invalid: " + error.getMessage());
                }
            }
            if (route.containsKey("polarity")) {
                Object polarityValue = route.get("polarity");
                String channelMap = String.valueOf(route.getOrDefault("channel_map", "stereo"));
                if (!(polarityValue instanceof String polarity)
                        || !Set.of("normal", "invert", "invert_left", "invert_right").contains(polarity))
                    errors.add("routes[" + i + "].polarity must be normal, invert, invert_left, or invert_right");
                else if (Set.of("invert_left", "invert_right").contains(polarity)) {
                    Set<String> outputPositions;
                    try {
                        outputPositions = route.containsKey("channel_matrix")
                                ? Set.copyOf(ChannelMatrix.parse(route.get("channel_matrix")).outputs())
                                : monoChannelMap(channelMap) ? Set.of("MONO") : Set.of("FL", "FR");
                    } catch (IllegalArgumentException ignored) { outputPositions = Set.of(); }
                    String requested = "invert_left".equals(polarity) ? "FL" : "FR";
                    if (!outputPositions.contains(requested))
                        errors.add(route.containsKey("channel_matrix")
                                ? "routes[" + i + "].channel-specific polarity requires output channel " + requested
                                : "routes[" + i + "].channel-specific polarity requires a stereo channel map");
                }
            }
            if (route.containsKey("priority")) {
                double priority = number(route.get("priority"), Double.NaN);
                if (!Double.isFinite(priority) || priority != Math.rint(priority) || priority < 0 || priority > 1000)
                    errors.add("routes[" + i + "].priority must be an integer between 0 and 1000");
            }
            if (route.containsKey("fallback")) {
                Object fallback = route.get("fallback");
                if (!(fallback instanceof String fallbackId) || (!"system_default".equals(fallbackId) && !deviceIds.contains(fallbackId)))
                    errors.add("routes[" + i + "].fallback must reference an output device or system_default");
            }
            if (!(route.get("enabled") instanceof Boolean)) errors.add("routes[" + i + "].enabled must be boolean");
        }
        checkRouteCycles(routes, busIds, errors);
        Map<String, Object> dsp = object(config.get("dsp"), "dsp", errors);
        Map<String, Object> routeChains = Map.of();
        if (dsp != null) {
            validateEq(dsp.get("global_eq"), "dsp.global_eq", errors);
            routeChains = optionalObject(dsp.get("route_chains"), "dsp.route_chains", errors);
            Map<String, Object> deviceEq = optionalObject(dsp.get("devices"), "dsp.devices", errors);
            deviceEq.forEach((deviceId, eq) -> validateEq(eq, "dsp.devices." + deviceId, errors));
            routeChains.forEach((chainId, eq) -> validateEq(eq, "dsp.route_chains." + chainId, errors));
            Map<String, Object> limiter = optionalObject(dsp.get("limiter"), "dsp.limiter", errors);
            if (!limiter.isEmpty()) {
                if (!(limiter.get("enabled") instanceof Boolean)) errors.add("dsp.limiter.enabled must be boolean");
                bounded(limiter.get("ceiling_db"), -12, 0, "dsp.limiter.ceiling_db", errors);
                bounded(limiter.get("lookahead_ms"), 1, 10, "dsp.limiter.lookahead_ms", errors);
                bounded(limiter.get("release_ms"), 5, 500, "dsp.limiter.release_ms", errors);
            }
            if (!Boolean.TRUE.equals(limiter.get("enabled"))) {
                for (int i = 0; i < routes.size(); i++) {
                    String channelMap = String.valueOf(routes.get(i).getOrDefault("channel_map", "stereo"));
                    if (!Boolean.FALSE.equals(routes.get(i).get("enabled")) && !routes.get(i).containsKey("channel_matrix")
                            && channelMapMaySum(channelMap))
                        errors.add("routes[" + i + "].channel_map requires the output limiter because it sums channels");
                    if (!Boolean.FALSE.equals(routes.get(i).get("enabled")) && routes.get(i).containsKey("channel_matrix")) {
                        try {
                            if (ChannelMatrix.parse(routes.get(i).get("channel_matrix")).maximumRowGain() > 1.0 + 1e-9)
                                errors.add("routes[" + i + "].channel_matrix requires the output limiter because it can increase signal level");
                        } catch (IllegalArgumentException ignored) { }
                    }
                }
            }
        }
        for (int i = 0; i < buses.size(); i++) {
            Object ref = buses.get(i).get("filters_ref");
            validateRouteChainReference(ref, routeChains, "buses[" + i + "].filters_ref", errors);
        }
        for (int i = 0; i < applications.size(); i++) {
            Object ref = applications.get(i).get("filters_ref");
            if (ref != null) validateRouteChainReference(ref, routeChains, "applications[" + i + "].filters_ref", errors);
        }
        for (int i = 0; i < devices.size(); i++) {
            Object ref = devices.get(i).get("filters_ref");
            if (ref != null) validateRouteChainReference(ref, routeChains, "devices[" + i + "].filters_ref", errors);
        }
        for (int i = 0; i < routes.size(); i++) {
            Object ref = routes.get(i).get("filters_ref");
            if (ref != null) validateRouteChainReference(ref, routeChains, "routes[" + i + "].filters_ref", errors);
            String channelMap = String.valueOf(routes.get(i).getOrDefault("channel_map", "stereo"));
            Set<String> positions;
            try {
                positions = routes.get(i).containsKey("channel_matrix")
                        ? Set.copyOf(ChannelMatrix.parse(routes.get(i).get("channel_matrix")).outputs())
                        : monoChannelMap(channelMap) ? Set.of("MONO") : Set.of("FL", "FR");
            } catch (IllegalArgumentException ignored) {
                positions = Set.of();
            }
            validateRouteChainChannels(ref, routeChains, positions, "routes[" + i + "].filters_ref", errors);
            String fromBusId = String.valueOf(routes.get(i).getOrDefault("from", ""));
            for (int busIndex = 0; busIndex < buses.size(); busIndex++) {
                Map<String, Object> bus = buses.get(busIndex);
                if (fromBusId.equals(bus.get("id"))) {
                    Object busRef = bus.get("filters_ref");
                    validateRouteChainChannels(busRef, routeChains, positions,
                            "buses[" + busIndex + "].filters_ref for route " + i, errors);
                    break;
                }
            }
        }
        for (int i = 0; i < profiles.size(); i++) {
            Map<String, Object> profile = profiles.get(i);
            if (!Set.of("haptic", "subwoofer", "speaker", "hybrid", "multi-output", "off").contains(profile.get("mode")))
                errors.add("profiles[" + i + "].mode is unsupported");
            for (String busId : stringList(profile.get("active_buses")))
                if (!busIds.contains(busId)) errors.add("profiles[" + i + "] references missing bus " + busId);
            for (String routeId : stringList(profile.get("active_routes")))
                if (!routeIds.contains(routeId)) errors.add("profiles[" + i + "] references missing route " + routeId);
        }
        validateCalibrations(calibrations, errors);
        validateGraphLibrary(config, errors);
        return errors;
    }

    private static void validateGraphLibrary(Map<String,Object> config,List<String> errors) {
        List<Map<String,Object>> groups=optionalList(config.get("route_groups"),"route_groups",errors);
        List<Map<String,Object>> routes=optionalList(config.get("route_library"),"route_library",errors);
        List<Map<String,Object>> copies=optionalList(config.get("saved_copies"),"saved_copies",errors);
        List<Map<String,Object>> controls=optionalList(config.get("quick_controls"),"quick_controls",errors);
        checkUniqueIds(groups,"route_groups",errors);checkUniqueIds(routes,"route_library",errors);checkUniqueIds(copies,"saved_copies",errors);checkUniqueIds(controls,"quick_controls",errors);
        Set<String> groupIds=ids(groups),routeIds=ids(routes);String active=String.valueOf(config.getOrDefault("active_route",""));
        if(!active.isBlank()&&!routeIds.contains(active))errors.add("active_route does not reference a saved route");
        Map<String,AudioGraph> graphs=new java.util.LinkedHashMap<>();
        for(int i=0;i<routes.size();i++){
            Map<String,Object> route=routes.get(i);if(!(route.get("name") instanceof String name)||name.isBlank())errors.add("route_library["+i+"].name must not be blank");
            Object group=route.get("group_id");if(group!=null&&!String.valueOf(group).isBlank()&&!groupIds.contains(String.valueOf(group)))errors.add("route_library["+i+"].group_id must reference a route group");
            try{AudioGraph graph=GraphCodec.fromMap(route);for(String problem:GraphValidator.validate(graph))errors.add("route_library["+i+"]: "+problem);graphs.put(graph.id(),graph);}
            catch(RuntimeException invalid){errors.add("route_library["+i+"] is invalid: "+invalid.getMessage());}
        }
        for(int i=0;i<groups.size();i++)if(!(groups.get(i).get("name") instanceof String name)||name.isBlank())errors.add("route_groups["+i+"].name must not be blank");
        for(int i=0;i<copies.size();i++){
            Map<String,Object> copy=copies.get(i);if(!Boolean.FALSE.equals(copy.get("active")))errors.add("saved_copies["+i+"] must remain inactive");
            if(!(copy.get("route") instanceof Map<?,?> raw)){errors.add("saved_copies["+i+"].route must contain a route snapshot");continue;}
            Map<String,Object> route=new java.util.LinkedHashMap<>();raw.forEach((key,value)->route.put(String.valueOf(key),value));
            try{AudioGraph graph=GraphCodec.fromMap(route);for(String problem:GraphValidator.validate(graph))errors.add("saved_copies["+i+"]: "+problem);}
            catch(RuntimeException invalid){errors.add("saved_copies["+i+"] is invalid: "+invalid.getMessage());}
        }
        for(int i=0;i<controls.size();i++){
            Map<String,Object> control=controls.get(i);String routeId=String.valueOf(control.getOrDefault("route_id",active));AudioGraph graph=graphs.get(routeId);
            if(graph==null){errors.add("quick_controls["+i+"] references a missing route");continue;}
            String nodeId=String.valueOf(control.getOrDefault("node_id","")),parameter=String.valueOf(control.getOrDefault("parameter_id",""));
            GraphNode node=graph.nodes().stream().filter(item->item.id().equals(nodeId)).findFirst().orElse(null);
            if(node==null||!node.parameters().containsKey(parameter))errors.add("quick_controls["+i+"] references an unavailable node parameter");
        }
        validateGraphLayouts(config,graphs,routeIds,errors);
        validateGraphUiScales(config,routeIds,errors);
        validateGraphSubgraphs(config,graphs,routeIds,errors);
    }

    private static void validateGraphSubgraphs(Map<String,Object> config,Map<String,AudioGraph> graphs,Set<String> routeIds,List<String> errors){
        Object raw=config.get("graph_ui_subgraphs");if(raw==null)return;
        if(!(raw instanceof Map<?,?> byRoute)){errors.add("graph_ui_subgraphs must be an object");return;}
        for(Map.Entry<?,?> entry:byRoute.entrySet()){
            String routeId=String.valueOf(entry.getKey());AudioGraph graph=graphs.get(routeId);
            if(!routeIds.contains(routeId)||graph==null){errors.add("graph_ui_subgraphs references a missing route: "+routeId);continue;}
            validateSubgraphList(entry.getValue(),graph,"graph_ui_subgraphs["+routeId+"]",errors);
        }
        List<Map<String,Object>> copies=optionalList(config.get("saved_copies"),"saved_copies",new ArrayList<>());
        for(int i=0;i<copies.size();i++){
            Map<String,Object> copy=copies.get(i);if(!(copy.get("ui_subgraphs") instanceof List<?> groups))continue;
            Object rawRoute=copy.get("route");if(!(rawRoute instanceof Map<?,?> route))continue;
            try{Map<String,Object> decoded=new java.util.LinkedHashMap<>();route.forEach((key,value)->decoded.put(String.valueOf(key),value));
                validateSubgraphList(groups,GraphCodec.fromMap(decoded),"saved_copies["+i+"].ui_subgraphs",errors);
            }catch(RuntimeException invalid){errors.add("saved_copies["+i+"].ui_subgraphs cannot be checked: "+invalid.getMessage());}
        }
    }

    private static void validateSubgraphList(Object raw,AudioGraph graph,String path,List<String> errors){
        if(!(raw instanceof List<?> list)){errors.add(path+" must be an array");return;}
        Set<String> graphNodes=graph.nodes().stream().map(GraphNode::id).collect(java.util.stream.Collectors.toSet());
        Set<String> groupIds=new HashSet<>(),claimedNodes=new HashSet<>();
        for(int i=0;i<list.size();i++){
            if(!(list.get(i) instanceof Map<?,?> map)){errors.add(path+"["+i+"] must be an object");continue;}
            try{
                GraphSubgraph group=GraphSubgraph.fromMap(map);
                if(!groupIds.add(group.id()))errors.add(path+" contains duplicate id "+group.id());
                for(String nodeId:group.nodeIds()){
                    if(!graphNodes.contains(nodeId))errors.add(path+"["+i+"] references a missing node "+nodeId);
                    if(!claimedNodes.add(nodeId))errors.add(path+" node belongs to more than one subgraph: "+nodeId);
                }
            }catch(RuntimeException invalid){errors.add(path+"["+i+"] is invalid: "+invalid.getMessage());}
        }
    }

    private static void validateGraphLayouts(Map<String,Object> config,Map<String,AudioGraph> graphs,Set<String> routeIds,List<String> errors){
        Object value=config.get("graph_ui_layout");if(value==null)return;
        if(!(value instanceof Map<?,?> layouts)){errors.add("graph_ui_layout must be an object");return;}
        for(Map.Entry<?,?> entry:layouts.entrySet()){
            String routeId=String.valueOf(entry.getKey());AudioGraph graph=graphs.get(routeId);
            if(!routeIds.contains(routeId)||graph==null){errors.add("graph_ui_layout references a missing route: "+routeId);continue;}
            if(!(entry.getValue() instanceof Map<?,?> nodeLayouts)){errors.add("graph_ui_layout["+routeId+"] must be an object");continue;}
            Set<String> nodeIds=graph.nodes().stream().map(GraphNode::id).collect(java.util.stream.Collectors.toSet());
            for(Map.Entry<?,?> nodeEntry:nodeLayouts.entrySet()){
                String nodeId=String.valueOf(nodeEntry.getKey());if(!nodeIds.contains(nodeId)){errors.add("graph_ui_layout["+routeId+"] references a missing node: "+nodeId);continue;}
                if(!(nodeEntry.getValue() instanceof Map<?,?> point)){errors.add("graph_ui_layout["+routeId+"]."+nodeId+" must be an object");continue;}
                for(String axis:List.of("x","y")){Object coordinate=point.get(axis);if(!(coordinate instanceof Number number)||!Double.isFinite(number.doubleValue())||number.doubleValue()<0||number.doubleValue()>10000)errors.add("graph_ui_layout["+routeId+"]."+nodeId+"."+axis+" must be between 0 and 10000");}
                if(point.size()!=2)errors.add("graph_ui_layout["+routeId+"]."+nodeId+" must only contain x and y");
            }
        }
    }

    private static void validateGraphUiScales(Map<String,Object> config,Set<String> routeIds,List<String> errors){
        Object value=config.get("graph_ui_scale");if(value==null)return;
        if(!(value instanceof Map<?,?> scales)){errors.add("graph_ui_scale must be an object");return;}
        for(Map.Entry<?,?> entry:scales.entrySet()){
            String routeId=String.valueOf(entry.getKey());Object scale=entry.getValue();
            if(!routeIds.contains(routeId)){errors.add("graph_ui_scale references a missing route: "+routeId);continue;}
            if(!(scale instanceof Number number)||!Double.isFinite(number.doubleValue())||number.doubleValue()<0.5||number.doubleValue()>2.0)
                errors.add("graph_ui_scale["+routeId+"] must be between 0.5 and 2.0");
        }
    }

    private static List<Map<String,Object>> optionalList(Object value,String path,List<String> errors){
        if(value==null)return new ArrayList<>();if(!(value instanceof List<?> list)){errors.add(path+" must be an array");return new ArrayList<>();}
        List<Map<String,Object>> result=new ArrayList<>();for(int i=0;i<list.size();i++)if(list.get(i) instanceof Map<?,?> map){Map<String,Object>item=new java.util.LinkedHashMap<>();map.forEach((key,entry)->item.put(String.valueOf(key),entry));result.add(item);}else errors.add(path+"["+i+"] must be an object");return result;
    }

    private static void validateRouteChainReference(Object ref, Map<String, Object> routeChains, String path, List<String> errors) {
        if (ref == null) return;
        if (!(ref instanceof String text) || !text.startsWith("dsp.route_chains.") || text.length() == "dsp.route_chains.".length())
            errors.add(path + " must reference dsp.route_chains.<id>");
        else if (!routeChains.containsKey(text.substring("dsp.route_chains.".length())))
            errors.add(path + " references a missing route chain");
    }

    private static void validateRouteChainChannels(Object ref, Map<String, Object> routeChains, Set<String> positions,
                                                   String path, List<String> errors) {
        if (!(ref instanceof String text) || !text.startsWith("dsp.route_chains.")) return;
        Object chainValue = routeChains.get(text.substring("dsp.route_chains.".length()));
        if (!(chainValue instanceof Map<?, ?> chain) || !(chain.get("filters") instanceof List<?> filters)) return;
        for (Object filterValue : filters) {
            if (!(filterValue instanceof Map<?, ?> filter) || !(filter.get("channels") instanceof List<?> channels)) continue;
            for (Object channel : channels) if (!positions.contains(channel)) {
                errors.add(path + " has a channel-scoped filter that is unavailable on " + String.join("/", positions));
                break;
            }
        }
    }

    private static boolean monoChannelMap(String channelMap) {
        return Set.of("mono", "mono_sum", "left", "right", "center", "lfe", "rear_left", "rear_right",
                "rear_sum", "side_left", "side_right", "side_sum", "surround_sum", "all_sum").contains(channelMap);
    }

    private static boolean channelMapMaySum(String channelMap) {
        return Set.of("mono", "mono_sum", "rear_sum", "side_sum", "surround_sum", "all_sum").contains(channelMap);
    }

    private static boolean supportsChannelMap(String channelMap, String layout) {
        return switch (channelMap) {
            case "center", "lfe", "rear_left", "rear_right", "rear_sum", "surround_sum" -> Set.of("5.1", "7.1").contains(layout);
            case "side_left", "side_right", "side_sum" -> "7.1".equals(layout);
            default -> true;
        };
    }

    private static List<String> channelPositions(String layout) {
        return switch (layout) {
            case "mono" -> List.of("MONO");
            case "5.1" -> List.of("FL", "FR", "FC", "LFE", "RL", "RR");
            case "7.1" -> List.of("FL", "FR", "FC", "LFE", "RL", "RR", "SL", "SR");
            default -> List.of("FL", "FR");
        };
    }

    private static void checkRouteCycles(List<Map<String, Object>> routes, Set<String> busIds, List<String> errors) {
        Map<String, Set<String>> graph = new java.util.HashMap<>();
        for (String bus : busIds) graph.put(bus, new HashSet<>());
        for (Map<String, Object> route : routes) {
            if (Boolean.FALSE.equals(route.get("enabled"))) continue;
            String from = String.valueOf(route.getOrDefault("from", ""));
            String to = String.valueOf(route.getOrDefault("to", ""));
            if (graph.containsKey(from) && graph.containsKey(to)) graph.get(from).add(to);
        }
        Set<String> visiting = new HashSet<>(), visited = new HashSet<>();
        for (String bus : busIds) if (hasCycle(bus, graph, visiting, visited)) {
            errors.add("routes contain a feedback cycle"); return;
        }
    }
    private static boolean hasCycle(String node, Map<String, Set<String>> graph, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(node)) return true;
        if (!visited.add(node)) return false;
        visiting.add(node);
        for (String next : graph.getOrDefault(node, Set.of())) if (hasCycle(next, graph, visiting, visited)) return true;
        visiting.remove(node); return false;
    }
    private static void validateCalibrations(List<Map<String, Object>> calibrations, List<String> errors) {
        for (int i = 0; i < calibrations.size(); i++) {
            Map<String, Object> calibration = calibrations.get(i);
            if (!Set.of("manual", "microphone", "accelerometer", "combined").contains(calibration.get("method")))
                errors.add("calibrations[" + i + "].method is unsupported");
            if (!(calibration.get("verified") instanceof Boolean)) errors.add("calibrations[" + i + "].verified must be boolean");
            Object measurementKind = calibration.get("measurement_kind");
            if (measurementKind != null && !(measurementKind instanceof String))
                errors.add("calibrations[" + i + "].measurement_kind must be a string or null");
            else if (measurementKind instanceof String kind && !"impulse_decay".equals(kind))
                errors.add("calibrations[" + i + "].measurement_kind is unsupported");
            Object rollbackRef = calibration.get("rollback_filters_ref");
            if (rollbackRef != null && (!(rollbackRef instanceof String reference)
                    || !reference.startsWith("dsp.route_chains.") || reference.length() <= "dsp.route_chains.".length()))
                errors.add("calibrations[" + i + "].rollback_filters_ref must be a route-chain reference or null");
            for (String field : Set.of("sensor_source", "sensor_source_description")) {
                Object source = calibration.get(field);
                if (source != null && !(source instanceof String)) errors.add("calibrations[" + i + "]." + field + " must be a string or null");
            }
            for (String field : Set.of("measurement_file", "response_curve_file", "decay_curve_file")) {
                Object path = calibration.get(field);
                if (path != null && (!(path instanceof String text) || !relativeMeasurementPath(text)))
                    errors.add("calibrations[" + i + "]." + field + " must be a safe relative path or null");
            }
            for (String field : Set.of("impulse_offset_ms", "response_window_ms", "impulse_peak_offset_ms", "dominant_frequency_hz",
                    "quality_factor", "decay_t20_ms", "decay_t60_projected_ms", "noise_rms_pcm")) {
                Object value = calibration.get(field);
                if (value != null && (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())))
                    errors.add("calibrations[" + i + "]." + field + " must be a finite number or null");
            }
            calibrationRange(calibration, "impulse_offset_ms", 20, 60_000, i, errors);
            calibrationRange(calibration, "response_window_ms", 200, 10_000, i, errors);
            calibrationRange(calibration, "impulse_peak_offset_ms", 0, 60_000, i, errors);
            calibrationRange(calibration, "dominant_frequency_hz", 20, 1000, i, errors);
            calibrationRange(calibration, "quality_factor", 0, 1_000_000, i, errors);
            calibrationRange(calibration, "decay_t20_ms", 0, 600_000, i, errors);
            calibrationRange(calibration, "decay_t60_projected_ms", 0, 600_000, i, errors);
            calibrationRange(calibration, "noise_rms_pcm", 0, 1, i, errors);
        }
    }

    private static void calibrationRange(Map<String, Object> calibration, String field, double minimum, double maximum,
                                         int index, List<String> errors) {
        Object value = calibration.get(field);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) return;
        double numeric = number.doubleValue();
        if (numeric < minimum || numeric > maximum || (minimum == 0 && numeric == 0
                && Set.of("quality_factor", "decay_t20_ms", "decay_t60_projected_ms").contains(field)))
            errors.add("calibrations[" + index + "]." + field + " is outside the supported range");
    }

    private static boolean relativeMeasurementPath(String value) {
        if (value.isBlank() || value.contains("\\") || value.contains(":") || value.startsWith("/")) return false;
        try {
            Path path = Path.of(value).normalize();
            return !path.isAbsolute() && !path.startsWith("..") && path.toString().equals(value);
        } catch (RuntimeException invalid) { return false; }
    }

    private static void validateEq(Object value, String path, List<String> errors) {
        if (value == null) return;
        Map<String, Object> eq = object(value, path, errors);
        if (eq == null) return;
        if (!(eq.get("enabled") instanceof Boolean)) errors.add(path + ".enabled must be boolean");
        Object filtersValue = eq.get("filters");
        if (filtersValue == null) { errors.add(path + ".filters must be an array"); return; }
        if (!(filtersValue instanceof List<?> filters)) { errors.add(path + ".filters must be an array"); return; }
        Set<String> types = Set.of("peaking", "notch", "low_shelf", "high_shelf", "high_pass", "low_pass", "band_pass");
        for (int i = 0; i < filters.size(); i++) {
            if (!(filters.get(i) instanceof Map<?, ?> filter)) { errors.add(path + ".filters[" + i + "] must be an object"); continue; }
            if (!types.contains(filter.get("type"))) errors.add(path + ".filters[" + i + "].type is unsupported");
            double frequency = number(filter.get("frequency_hz"), -1);
            if (!(frequency > 0 && frequency <= 96000)) errors.add(path + ".filters[" + i + "].frequency_hz must be > 0 and <= 96000");
            if (filter.containsKey("gain_db")) {
                double gain = number(filter.get("gain_db"), Double.NaN);
                if (!Double.isFinite(gain) || gain < -24 || gain > 24) errors.add(path + ".filters[" + i + "].gain_db must be between -24 and 24");
            }
            if (filter.containsKey("q")) {
                double q = number(filter.get("q"), Double.NaN);
                if (!Double.isFinite(q) || q <= 0 || q > 20) errors.add(path + ".filters[" + i + "].q must be > 0 and <= 20");
            }
            if (filter.containsKey("enabled") && !(filter.get("enabled") instanceof Boolean))
                errors.add(path + ".filters[" + i + "].enabled must be boolean");
            if (filter.containsKey("channels")) {
                Object channelsValue = filter.get("channels");
                if (!(channelsValue instanceof List<?> channels) || channels.isEmpty()
                        || channels.stream().anyMatch(channel -> !(channel instanceof String name) || !Set.of("FL", "FR", "MONO").contains(name)))
                    errors.add(path + ".filters[" + i + "].channels must be a non-empty array of FL, FR, or MONO");
            }
            if (filter.containsKey("slope_db_oct")) {
                double slope = number(filter.get("slope_db_oct"), Double.NaN);
                if (!Double.isFinite(slope) || !Set.of(6.0, 12.0, 18.0, 24.0, 36.0, 48.0).contains(slope))
                    errors.add(path + ".filters[" + i + "].slope_db_oct must be one of 6, 12, 18, 24, 36, or 48");
                if (!"high_pass".equals(filter.get("type")) && !"low_pass".equals(filter.get("type")))
                    errors.add(path + ".filters[" + i + "].slope_db_oct is only supported for high_pass or low_pass filters");
            }
        }
    }

    private static void validateVolumes(List<Map<String, Object>> items, String path, List<String> errors) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).containsKey("volume"))
            checkVolume(items.get(i).get("volume"), path + "[" + i + "].volume", errors);
    }
    private static void bounded(Object value, double minimum, double maximum, String path, List<String> errors) {
        double number = number(value, Double.NaN);
        if (!Double.isFinite(number) || number < minimum || number > maximum)
            errors.add(path + " must be between " + minimum + " and " + maximum);
    }
    private static void checkVolume(Object value, String path, List<String> errors) {
        double volume = number(value, Double.NaN);
        if (!Double.isFinite(volume) || volume < 0 || volume > 1) errors.add(path + " must be a number in the range 0.0–1.0");
    }
    private static void checkUniqueIds(List<Map<String, Object>> items, String path, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            Object id = items.get(i).get("id");
            if (!(id instanceof String text) || text.isBlank()) errors.add(path + "[" + i + "].id must be a non-empty string");
            else if (!seen.add(text)) errors.add(path + " contains duplicate id " + text);
        }
    }
    private static Set<String> ids(List<Map<String, Object>> items) {
        Set<String> result = new HashSet<>(); for (Map<String, Object> item : items) if (item.get("id") instanceof String id) result.add(id); return result;
    }
    private static Set<String> deviceIds(List<Map<String, Object>> items) { return ids(items); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> list(Object value, String path, List<String> errors) {
        if (!(value instanceof List<?> source)) { errors.add(path + " must be an array"); return List.of(); }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i) instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
            else errors.add(path + "[" + i + "] must be an object");
        }
        return result;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value, String path, List<String> errors) {
        if (value == null) { errors.add(path + " is required"); return null; }
        if (!(value instanceof Map<?, ?>)) { errors.add(path + " must be an object"); return null; }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> optionalObject(Object value, String path, List<String> errors) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map)) { errors.add(path + " must be an object"); return Map.of(); }
        return (Map<String, Object>) map;
    }
    private static void required(Map<String, Object> map, String key, Class<?> type, List<String> errors) {
        if (!type.isInstance(map.get(key))) errors.add(key + " must be " + type.getSimpleName());
    }
    private static double number(Object value, double fallback) { return value instanceof Number n ? n.doubleValue() : fallback; }
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
