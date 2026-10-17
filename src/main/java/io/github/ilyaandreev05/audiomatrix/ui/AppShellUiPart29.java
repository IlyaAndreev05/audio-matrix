package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.ThemeManager;
import io.github.ilyaandreev05.audiomatrix.audio.*;
import io.github.ilyaandreev05.audiomatrix.config.*;
import io.github.ilyaandreev05.audiomatrix.gnome.*;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import javafx.application.Platform;
import java.util.*;
import java.util.concurrent.*;

abstract class AppShellUiPart29 extends AppShellUiPart28 {
    protected AppShellUiPart29(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected CompletableFuture<Void> handleGnomeCommand(GnomeControlBridge.Command command) {
        if (GnomeCommandRevision.isStale(command, store.revision()))
            return CompletableFuture.failedFuture(new IllegalStateException("GNOME command is based on an old Audio Matrix revision"));
        return switch (command.name()) {
            case "open" -> { Platform.runLater(() -> { if (getScene() != null && getScene().getWindow() instanceof javafx.stage.Stage stage) { stage.setIconified(false); stage.toFront(); stage.requestFocus(); } }); yield CompletableFuture.completedFuture(null); }
            case "select_route" -> updateRouteConfigCommand(t("Выбран маршрут из GNOME", "Route selected from GNOME"), config -> GraphRouteLibrary.activate(config, command.value()));
            case "set_quick_group_collapsed" -> setGnomeQuickGroupCollapsed(command.value());
            case "select_output" -> { Platform.runLater(() -> setNotice(t("Выходы выбираются в графе маршрутов.", "Output routing is selected in the route graph."), false)); yield CompletableFuture.completedFuture(null); }
            case "toggle_mute" -> { AudioNode node=activeOutputNode(); if(node==null)yield CompletableFuture.failedFuture(new IllegalStateException("No active physical output")); boolean muted=!node.muted(); yield backend.setMute(node.id(),muted).thenCompose(ignored->store.updateAsync(config->{Map<String,Object> device=ensureDevice(config,node);device.put("mute",muted);})).thenRun(this::refreshBackend); }
            case "set_output_volume" -> setGnomeOutputLevel(command.value(),false);
            case "toggle_output_mute" -> setGnomeOutputLevel(command.value(),true);
            case "set_app_volume" -> setGnomeApplicationLevel(command.value(),false);
            case "toggle_app_mute" -> setGnomeApplicationLevel(command.value(),true);
            case "set_stream_volume" -> setGnomeStreamLevel(command.value(),false);
            case "toggle_stream_mute" -> setGnomeStreamLevel(command.value(),true);
            case "set_quick_control" -> setGnomeQuickControl(command.value(),false);
            case "toggle_quick_control" -> setGnomeQuickControl(command.value(),true);
            case "set_quick_control_choice" -> setGnomeQuickControlChoice(command.value());
            case "set_mode" -> Set.of("haptic","subwoofer","speaker","hybrid","multi-output","off").contains(command.value())
                    ? updateRouteConfigCommand(t("Изменён режим выхода из GNOME", "GNOME output mode changed"),config->activeProfile(config).put("mode",command.value()))
                    : CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported output mode"));
            case "set_channel_map" -> setGnomeChannelMap(command.value());
            default -> CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported command: "+command.name()));
        };
    }

    protected CompletableFuture<Void> setGnomeQuickGroupCollapsed(String value) {
        String[] fields = value.split("\\t", 2);
        if (fields.length != 2 || !("true".equals(fields[1]) || "false".equals(fields[1])))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Quick settings group state is malformed"));
        return updateRouteConfigCommand(t("Изменено раскрытие группы GNOME", "GNOME group collapsed state changed"), config -> {
            Map<String, Object> group = QuickSettingsConfig.groups(config).stream()
                    .filter(candidate -> fields[0].equals(candidate.get("id"))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Quick settings group no longer exists"));
            group.put("collapsed", Boolean.parseBoolean(fields[1]));
        });
    }

    protected CompletableFuture<Void> setGnomeQuickControl(String value,boolean toggle){
        String[] fields=value.split("\\t",2);if(fields.length<1||fields[0].isBlank())return CompletableFuture.failedFuture(new IllegalArgumentException("Quick control id is missing"));
        return updateRouteConfigCommand(t("Изменён параметр Audio Matrix", "Audio Matrix control changed"),config->{
            Map<String,Object> control=maps(config.get("quick_controls")).stream().filter(item->fields[0].equals(item.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("Quick control no longer exists"));
            String routeId=string(control.get("route_id"),string(config.get("active_route"),""));if(!routeId.equals(config.get("active_route")))throw new IllegalArgumentException("Quick control is not on the active route");
            String nodeId=string(control.get("node_id"),""),parameter=string(control.get("parameter_id"),"");Map<String,Object> route=maps(config.get("route_library")).stream().filter(item->routeId.equals(item.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("Active route no longer exists"));
            Map<String,Object> encodedNode=maps(route.get("nodes")).stream().filter(item->nodeId.equals(item.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("Quick control node no longer exists"));Map<String,Object> parameters=map(encodedNode.get("parameters"));encodedNode.put("parameters",parameters);boolean gainMute=toggle&&"gain".equals(encodedNode.get("type"))&&"gain".equals(parameter);String targetParameter=gainMute?"muted":parameter;Object current=parameters.get(targetParameter),next;
            if(toggle){if(!(current instanceof Boolean flag))throw new IllegalArgumentException("This parameter is not a toggle");next=!flag;}else{if(fields.length<2)throw new IllegalArgumentException("Control value is missing");double level;try{level=Double.parseDouble(fields[1]);}catch(NumberFormatException invalid){throw new IllegalArgumentException("Control value is invalid");}if(!Double.isFinite(level))throw new IllegalArgumentException("Control value is invalid");next=Math.max(0,Math.min(1,level));}
            parameters.put(targetParameter,next);if(!toggle&&"gain".equals(encodedNode.get("type"))&&"gain".equals(parameter))parameters.put("muted",false);AudioGraph graph=GraphCodec.fromMap(route);List<String> issues=GraphValidator.validate(graph);if(!issues.isEmpty())throw new IllegalArgumentException(String.join("; ",issues));GraphRouteLibrary.saveRoute(config,graph,string(route.get("group_id"),""));
        });
    }

    protected CompletableFuture<Void> setGnomeQuickControlChoice(String value){
        String[] fields=value.split("\\t",2);if(fields.length!=2)return CompletableFuture.failedFuture(new IllegalArgumentException("Control choice is malformed"));
        return updateRouteConfigCommand(t("Изменён выбор маршрута в Audio Matrix", "Audio Matrix route choice changed"),config->{
            Map<String,Object> control=maps(config.get("quick_controls")).stream().filter(item->fields[0].equals(item.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("Quick control no longer exists"));if(!"active_links".equals(control.get("parameter_id")))throw new IllegalArgumentException("Quick control is not a route selector");
            String routeId=string(control.get("route_id"),string(config.get("active_route"),""));if(!routeId.equals(config.get("active_route")))throw new IllegalArgumentException("Quick control is not on the active route");Map<String,Object> route=maps(config.get("route_library")).stream().filter(item->routeId.equals(item.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("Active route no longer exists"));
            AudioGraph graph=GraphCodec.fromMap(route);RouterNode router=graph.nodes().stream().filter(node->node.id().equals(control.get("node_id"))&&node instanceof RouterNode).map(RouterNode.class::cast).findFirst().orElseThrow(()->new IllegalArgumentException("Route selector no longer exists"));String[] endpoints=fields[1].split("->",-1);boolean wiredInput=endpoints.length==2&&graph.connections().stream().anyMatch(edge->edge.enabled()&&edge.toNode().equals(router.id())&&edge.toPort().equals(endpoints[0]));boolean wiredOutput=endpoints.length==2&&graph.connections().stream().anyMatch(edge->edge.enabled()&&edge.fromNode().equals(router.id())&&edge.fromPort().equals(endpoints[1]));if(endpoints.length!=2||router.inputs().stream().noneMatch(port->port.id().equals(endpoints[0]))||router.outputs().stream().noneMatch(port->port.id().equals(endpoints[1]))||!wiredInput||!wiredOutput)throw new IllegalArgumentException("Route choice is invalid or disconnected");
            List<String> links=new ArrayList<>(router.activeLinks());if(router.multiple()){if(links.contains(fields[1]))links.remove(fields[1]);else links.add(fields[1]);}else{links.clear();links.add(fields[1]);}Map<String,Object> encodedNode=maps(route.get("nodes")).stream().filter(item->router.id().equals(item.get("id"))).findFirst().orElseThrow();map(encodedNode.get("parameters")).put("active_links",links);GraphRouteLibrary.saveRoute(config,GraphCodec.fromMap(route),string(route.get("group_id"),""));
        });
    }

    protected CompletableFuture<Void> setGnomeOutputLevel(String value, boolean toggleMute) {
        String[] fields = value.split("\\t", 2);
        if (fields[0].isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("Output name is missing"));
        AudioNode node = GnomeOutputState.resolve(physicalSinks(), fields[0], maps(store.snapshot().get("devices")));
        if (node == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Output is unavailable"));
        if (toggleMute) {
            boolean muted = !node.muted();
            return backend.setMute(node.id(), muted).thenCompose(ignored -> store.updateAsync(config -> {
                ensureDevice(config, node).put("mute", muted);
            })).thenRun(this::refreshBackend);
        }
        if (fields.length < 2) return CompletableFuture.failedFuture(new IllegalArgumentException("Output volume is missing"));
        double level;
        try { level = Double.parseDouble(fields[1]); }
        catch (NumberFormatException invalid) { return CompletableFuture.failedFuture(new IllegalArgumentException("Output volume is invalid")); }
        if (!Double.isFinite(level)) return CompletableFuture.failedFuture(new IllegalArgumentException("Output volume is invalid"));
        double target = Math.max(0, Math.min(1, level));
        CompletableFuture<Void> unmute = node.muted() ? backend.setMute(node.id(), false) : CompletableFuture.completedFuture(null);
        return unmute.thenCompose(ignored -> backend.setVolume(node.id(), target)).thenCompose(ignored -> store.updateAsync(config -> {
            Map<String, Object> device = ensureDevice(config, node);
            device.put("volume", target);
            device.put("mute", false);
        })).thenRun(this::refreshBackend);
    }

    protected CompletableFuture<Void> setGnomeApplicationLevel(String value, boolean toggleMute) {
        String[] fields = value.split("\\t", 2);
        if (fields[0].isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("Application id is missing"));
        String key = fields[0];
        List<AudioNode> streams = snapshot.streams().stream().filter(stream -> matchesApplication(key, stream)).toList();
        if (streams.isEmpty()) return CompletableFuture.failedFuture(new IllegalArgumentException("Application stream is unavailable"));
        boolean muteTarget = toggleMute && streams.stream().anyMatch(stream -> !stream.muted());
        double targetLevel = 0;
        if (!toggleMute) {
            if (fields.length < 2) return CompletableFuture.failedFuture(new IllegalArgumentException("Application volume is missing"));
            try { targetLevel = Double.parseDouble(fields[1]); }
            catch (NumberFormatException invalid) { return CompletableFuture.failedFuture(new IllegalArgumentException("Application volume is invalid")); }
            if (!Double.isFinite(targetLevel)) return CompletableFuture.failedFuture(new IllegalArgumentException("Application volume is invalid"));
            targetLevel = Math.max(0, Math.min(1, targetLevel));
        }
        final double requestedLevel = targetLevel;
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (AudioNode stream : streams)
            work.add(toggleMute ? backend.setMute(stream.id(), muteTarget) : setUnmutedVolume(stream, requestedLevel));
        return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new))
                .thenCompose(ignored -> key.startsWith("stream:") ? CompletableFuture.completedFuture(null)
                        : store.updateAsync(config -> saveApplicationLevel(config, key, toggleMute, muteTarget, requestedLevel)))
                .thenRun(this::refreshBackend);
    }

    private boolean matchesApplication(String key, AudioNode stream) {
        String app = stream.applicationId() == null ? "" : stream.applicationId();
        String process = stream.processName() == null ? "" : stream.processName();
        return key.equals("app:" + app) || key.equals("process:" + process) || key.equals("stream:" + stream.name());
    }

    private CompletableFuture<Void> setUnmutedVolume(AudioNode stream, double level) {
        CompletableFuture<Void> unmute = stream.muted() ? backend.setMute(stream.id(), false) : CompletableFuture.completedFuture(null);
        return unmute.thenCompose(ignored -> backend.setVolume(stream.id(), level));
    }

    private void saveApplicationLevel(Map<String, Object> config, String key, boolean mute, boolean muteTarget, double level) {
        Map<String, Object> rule = ensureGnomeApplicationRule(config, key);
        if (mute) rule.put("mute", muteTarget);
        else {
            rule.put("volume", level);
            rule.put("mute", false);
        }
    }

    protected CompletableFuture<Void> setGnomeStreamLevel(String value, boolean toggleMute) {
        String[] fields = value.split("\\t", 2);
        if (fields.length < 1) return CompletableFuture.failedFuture(new IllegalArgumentException("Stream id is missing"));
        int id;
        try { id = Integer.parseInt(fields[0]); }
        catch (NumberFormatException error) { return CompletableFuture.failedFuture(new IllegalArgumentException("Stream id is invalid")); }
        AudioNode stream = snapshot.streams().stream().filter(node -> node.id() == id).findFirst().orElse(null);
        if (stream == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Stream is unavailable"));
        if (toggleMute) return backend.setMute(id, !stream.muted()).thenRun(this::refreshBackend);
        if (fields.length < 2) return CompletableFuture.failedFuture(new IllegalArgumentException("Stream volume is missing"));
        double level;
        try { level = Double.parseDouble(fields[1]); }
        catch (NumberFormatException error) { return CompletableFuture.failedFuture(new IllegalArgumentException("Stream volume is invalid")); }
        if (!Double.isFinite(level)) return CompletableFuture.failedFuture(new IllegalArgumentException("Stream volume is invalid"));
        return setUnmutedVolume(stream, Math.max(0, Math.min(1, level))).thenRun(this::refreshBackend);
    }

    protected Map<String,Object> ensureGnomeApplicationRule(Map<String,Object> config,String groupId){String field,value;if(groupId.startsWith("app:")){field="app_id";value=groupId.substring(4);}else if(groupId.startsWith("process:")){field="process";value=groupId.substring(8);}else throw new IllegalArgumentException("A transient stream cannot be saved as an application group");List<Map<String,Object>> applications=maps(config.get("applications"));Map<String,Object> existing=applications.stream().filter(rule->value.equals(map(rule.get("match")).get(field))).findFirst().orElse(null);if(existing!=null)return existing;Map<String,Object> rule=DefaultConfigMap.map("id","gnome-"+slug(value),"match",DefaultConfigMap.map(field,value),"volume",1.0,"mute",false,"bus","main","preferred_output","system_default");applications.add(rule);return rule;}

    protected CompletableFuture<Void> setGnomeChannelMap(String value) {
        String[] fields = value.split("\\t", 2);
        if (fields.length != 2 || !Set.of("stereo", "mono", "mono_sum", "left", "right", "swap_lr",
                "center", "lfe", "rear_left", "rear_right", "rear_sum", "side_left", "side_right", "side_sum", "surround_sum", "all_sum").contains(fields[1])) return CompletableFuture.failedFuture(new IllegalArgumentException("Channel map is invalid"));
        String routeId = fields[0];
        Map<String, Object> current = store.snapshot();
        Map<String, Object> profile = activeProfile(current);
        if (!stringList(profile.get("active_routes")).contains(routeId)) return CompletableFuture.failedFuture(new IllegalArgumentException("Channel map route is not active"));
        Map<String, Object> route = maps(current.get("routes")).stream()
                .filter(candidate -> routeId.equals(candidate.get("id")) && !Boolean.FALSE.equals(candidate.get("enabled")))
                .findFirst().orElse(null);
        AudioNode selected = activeOutputNode();
        AudioNode target = route == null ? null : routeOutputNode(current, route);
        if (selected == null || target == null || !selected.name().equals(target.name())) return CompletableFuture.failedFuture(new IllegalArgumentException("Channel map route is not on the selected output"));
        return updateRouteConfigCommand(t("Изменена карта каналов маршрута ", "Changed channel map for route ") + routeId, config -> maps(config.get("routes")).stream()
                .filter(candidate -> routeId.equals(candidate.get("id")) && !Boolean.FALSE.equals(candidate.get("enabled")))
                .findFirst().ifPresent(candidate -> candidate.put("channel_map", fields[1])));
    }

}
