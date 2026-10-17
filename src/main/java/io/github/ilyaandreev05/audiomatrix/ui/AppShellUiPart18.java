package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.ThemeManager;
import io.github.ilyaandreev05.audiomatrix.audio.*;
import io.github.ilyaandreev05.audiomatrix.config.*;
import io.github.ilyaandreev05.audiomatrix.gnome.*;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.transform.Scale;
import javafx.stage.*;
import javafx.util.Duration;
import javafx.util.StringConverter;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

abstract class AppShellUiPart18 extends AppShellUiPart17 {
    protected AppShellUiPart18(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected VBox settingsPage() {
        VBox page = page(t("Язык, тема, проверка JSON и состояние источников.", "Language, theme, JSON validation, and source status."));
        VBox preferences = card(t("Внешний вид", "Appearance"));
        ComboBox<String> language = new ComboBox<>(); language.getItems().addAll("ru", "en"); language.setValue(setting(store.snapshot(), "ui", "language", "ru")); language.setAccessibleText(t("Язык интерфейса", "Interface language"));
        ComboBox<String> theme = new ComboBox<>(); theme.getItems().addAll("system", "light", "dark"); theme.setValue(setting(store.snapshot(), "ui", "theme", "system")); theme.setAccessibleText(t("Тема интерфейса", "Interface theme"));
        language.setOnAction(e -> updateUiSetting("language", language.getValue()));
        theme.setOnAction(e -> { updateUiSetting("theme", theme.getValue()); themes.apply(getScene(), theme.getValue()); });
        preferences.getChildren().addAll(label(t("Язык", "Language"), ""), language, label(t("Тема: системная, светлая или тёмная", "Theme: system, light, or dark"), ""), theme);
        page.getChildren().add(preferences);
        VBox pipewire = card(t("Диагностика PipeWire", "PipeWire diagnostics"));
        pipewire.getChildren().addAll(label(backend.backendMessage(), snapshot.connected() ? "accent" : "danger"),
                label("wpctl: " + (executable("wpctl") ? t("доступен", "available") : t("не найден", "not found")), ""),
                label("pw-dump: " + (executable("pw-dump") ? t("доступен", "available") : t("не найден", "not found")), ""),
                label("pw-link: " + (executable("pw-link") ? t("доступен", "available") : t("не найден", "not found")), ""),
                label("pactl: " + (executable("pactl") ? t("доступен", "available") : t("не найден; перенос потоков отключён", "not found; stream transfer disabled")), ""),
                label(t("В системе обнаружено ", "System discovery found ") + physicalSinks().size() + t(" выходов, ", " outputs, ") + snapshot.sources().size() + t(" входов и ", " inputs and ") + snapshot.streams().size() + t(" активных потоков.", " active streams."), ""));
        Button refresh = actionButton(t("Проверить заново", "Check again"), this::refreshBackend); pipewire.getChildren().add(refresh);
        page.getChildren().add(pipewire);
        VBox routeLifecycle = card(t("Маршруты при запуске", "Route startup"));
        CheckBox applyOnStart = new CheckBox(t("Применять сохранённые маршруты при запуске Audio Matrix", "Apply saved routes when Audio Matrix starts"));
        applyOnStart.setSelected(Boolean.TRUE.equals(map(store.snapshot().get("engine")).get("apply_on_start")));
        applyOnStart.setOnAction(event -> updateConfig(config -> map(config.get("engine")).put("apply_on_start", applyOnStart.isSelected())));
        routeLifecycle.getChildren().addAll(applyOnStart,
                label(t("Когда выключено, запуск приложения оставляет PipeWire-граф без изменений. Уже применённый граф повторно собирается после переподключения PipeWire, пока Audio Matrix работает.", "When disabled, launching the app leaves the PipeWire graph unchanged. An applied graph is rebuilt after PipeWire reconnects while Audio Matrix is running."), "muted"));
        page.getChildren().add(routeLifecycle);
        VBox gnome = card(t("GNOME Quick Settings", "GNOME Quick Settings"));
        gnome.getChildren().addAll(label(t("Установите скрипт scripts/install-gnome-quick-settings.sh, перезапустите сеанс GNOME и включите audio-matrix-quick-settings@ilyaandreev05 в GNOME Extensions.", "Run scripts/install-gnome-quick-settings.sh, sign out and back in, then enable audio-matrix-quick-settings@ilyaandreev05 in GNOME Extensions."), ""),
                label(t("Мост использует D-Bus сеанса GNOME; команды выполняет Audio Matrix. Регуляторы выходов и приложений сохраняются в config.json; действия меняют PipeWire через текущую сессию.", "The GNOME session D-Bus bridge sends commands to Audio Matrix. Output and application controls are saved in config.json and change PipeWire through the current session."), "muted")); page.getChildren().add(gnome);
        return page;
    }

    protected CompletableFuture<Void> handleGnomeCommand(GnomeControlBridge.Command command) {
        return switch (command.name()) {
            case "open" -> { Platform.runLater(() -> { if (getScene() != null && getScene().getWindow() instanceof javafx.stage.Stage stage) { stage.setIconified(false); stage.toFront(); stage.requestFocus(); } }); yield CompletableFuture.completedFuture(null); }
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

    protected CompletableFuture<Void> setGnomeQuickControl(String value,boolean toggle){
        String[] fields=value.split("\\t",2);if(fields.length<1||fields[0].isBlank())return CompletableFuture.failedFuture(new IllegalArgumentException("Quick control id is missing"));
        return updateRouteConfigCommand(t("Изменён параметр Audio Matrix", "Audio Matrix control changed"),config->{
            Map<String,Object> control=maps(config.get("quick_controls")).stream().filter(item->fields[0].equals(item.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("Quick control no longer exists"));
            String routeId=string(control.get("route_id"),string(config.get("active_route"),""));if(!routeId.equals(config.get("active_route")))throw new IllegalArgumentException("Quick control is not on the active route");
            String nodeId=string(control.get("node_id"),""),parameter=string(control.get("parameter_id"),"");Map<String,Object> route=maps(config.get("route_library")).stream().filter(item->routeId.equals(item.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("Active route no longer exists"));
            Map<String,Object> encodedNode=maps(route.get("nodes")).stream().filter(item->nodeId.equals(item.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("Quick control node no longer exists"));Map<String,Object> parameters=map(encodedNode.get("parameters"));encodedNode.put("parameters",parameters);Object current=parameters.get(parameter),next;
            if(toggle){if(!(current instanceof Boolean flag))throw new IllegalArgumentException("This parameter is not a toggle");next=!flag;}else{if(fields.length<2)throw new IllegalArgumentException("Control value is missing");double level;try{level=Double.parseDouble(fields[1]);}catch(NumberFormatException invalid){throw new IllegalArgumentException("Control value is invalid");}if(!Double.isFinite(level))throw new IllegalArgumentException("Control value is invalid");next=Math.max(0,Math.min(1,level));}
            parameters.put(parameter,next);AudioGraph graph=GraphCodec.fromMap(route);List<String> issues=GraphValidator.validate(graph);if(!issues.isEmpty())throw new IllegalArgumentException(String.join("; ",issues));GraphRouteLibrary.saveRoute(config,graph,string(route.get("group_id"),""));
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

    protected CompletableFuture<Void> setGnomeOutputLevel(String value,boolean toggleMute){String[] fields=value.split("\\t",2);if(fields.length<1||fields[0].isBlank())return CompletableFuture.failedFuture(new IllegalArgumentException("Output name is missing"));AudioNode node=physicalSinks().stream().filter(sink->sink.name().equals(fields[0])).findFirst().orElse(null);if(node==null)return CompletableFuture.failedFuture(new IllegalArgumentException("Output is unavailable"));if(toggleMute){boolean muted=!node.muted();return backend.setMute(node.id(),muted).thenCompose(ignored->store.updateAsync(config->{Map<String,Object> device=ensureDevice(config,node);device.put("mute",muted);})).thenRun(this::refreshBackend);}if(fields.length<2)return CompletableFuture.failedFuture(new IllegalArgumentException("Output volume is missing"));double level;try{level=Double.parseDouble(fields[1]);}catch(NumberFormatException invalid){return CompletableFuture.failedFuture(new IllegalArgumentException("Output volume is invalid"));}if(!Double.isFinite(level))return CompletableFuture.failedFuture(new IllegalArgumentException("Output volume is invalid"));double target=Math.max(0,Math.min(1,level));return backend.setVolume(node.id(),target).thenCompose(ignored->store.updateAsync(config->{Map<String,Object> device=ensureDevice(config,node);device.put("volume",target);})).thenRun(this::refreshBackend);}

    protected CompletableFuture<Void> setGnomeApplicationLevel(String value,boolean toggleMute){String[] fields=value.split("\\t",2);if(fields.length<1||fields[0].isBlank())return CompletableFuture.failedFuture(new IllegalArgumentException("Application id is missing"));String key=fields[0];List<AudioNode> streams=snapshot.streams().stream().filter(stream->{String app=stream.applicationId()==null?"":stream.applicationId();String process=stream.processName()==null?"":stream.processName();return key.equals("app:"+app)||key.equals("process:"+process)||key.equals("stream:"+stream.name());}).toList();if(streams.isEmpty())return CompletableFuture.failedFuture(new IllegalArgumentException("Application stream is unavailable"));boolean muteTarget=toggleMute&&streams.stream().anyMatch(stream->!stream.muted());double level=0;if(!toggleMute){if(fields.length<2)return CompletableFuture.failedFuture(new IllegalArgumentException("Application volume is missing"));try{level=Double.parseDouble(fields[1]);}catch(NumberFormatException invalid){return CompletableFuture.failedFuture(new IllegalArgumentException("Application volume is invalid"));}if(!Double.isFinite(level))return CompletableFuture.failedFuture(new IllegalArgumentException("Application volume is invalid"));}final double targetLevel=Math.max(0,Math.min(1,level));List<CompletableFuture<Void>> work=new ArrayList<>();for(AudioNode stream:streams)work.add(toggleMute?backend.setMute(stream.id(),muteTarget):backend.setVolume(stream.id(),targetLevel));return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).thenCompose(ignored->key.startsWith("stream:")?CompletableFuture.completedFuture(null):store.updateAsync(config->{Map<String,Object> rule=ensureGnomeApplicationRule(config,key);if(toggleMute)rule.put("mute",muteTarget);else rule.put("volume",targetLevel);})).thenRun(this::refreshBackend);}

    protected CompletableFuture<Void> setGnomeStreamLevel(String value,boolean toggleMute){String[] fields=value.split("\\t",2);if(fields.length<1)return CompletableFuture.failedFuture(new IllegalArgumentException("Stream id is missing"));int id;try{id=Integer.parseInt(fields[0]);}catch(NumberFormatException error){return CompletableFuture.failedFuture(new IllegalArgumentException("Stream id is invalid"));}AudioNode stream=snapshot.streams().stream().filter(node->node.id()==id).findFirst().orElse(null);if(stream==null)return CompletableFuture.failedFuture(new IllegalArgumentException("Stream is unavailable"));if(toggleMute)return backend.setMute(id,!stream.muted()).thenRun(this::refreshBackend);if(fields.length<2)return CompletableFuture.failedFuture(new IllegalArgumentException("Stream volume is missing"));double level;try{level=Double.parseDouble(fields[1]);}catch(NumberFormatException error){return CompletableFuture.failedFuture(new IllegalArgumentException("Stream volume is invalid"));}if(!Double.isFinite(level))return CompletableFuture.failedFuture(new IllegalArgumentException("Stream volume is invalid"));return backend.setVolume(id,Math.max(0,Math.min(1,level))).thenRun(this::refreshBackend);}

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

    protected AudioNode routeOutputNode(Map<String, Object> config, Map<String, Object> route) {
        String target = string(route.get("to"), "");
        if (maps(config.get("buses")).stream().anyMatch(bus -> target.equals(bus.get("id")))) return null;
        if ("system_default".equals(target))
            return physicalSinks().stream().filter(node -> node.id() == snapshot.defaultSinkId()).findFirst().orElse(null);
        return DeviceSelectorMatcher.resolve(physicalSinks(), target, maps(config.get("devices")));
    }

    protected AudioNode activeOutputNode() {
        AudioNode configured = configuredSink();
        if (configured != null) return configured;
        return physicalSinks().stream().filter(node -> node.id() == snapshot.defaultSinkId()).findFirst()
                .orElseGet(() -> physicalSinks().stream().findFirst().orElse(null));
    }

    protected void updateMeterTarget() {
        String previousName = selectedSink == null ? "" : selectedSink.name();
        selectedSink = physicalSinks().stream().filter(node -> node.name().equals(previousName)).findFirst().orElse(null);
        if (selectedSink == null) selectedSink = configuredSink();
        if (selectedSink == null) selectedSink = physicalSinks().stream().filter(node -> node.id() == snapshot.defaultSinkId()).findFirst()
                .orElseGet(() -> physicalSinks().stream().findFirst().orElse(null));
        backend.monitorOutput(currentPage == Page.DSP ? selectedSink : null);
        if (selectedInputSource != null) {
            String inputName = selectedInputSource.name();
            selectedInputSource = snapshot.sources().stream().filter(node -> node.name().equals(inputName)).findFirst().orElse(null);
            if (inputMeterEnabled && selectedInputSource == null) inputMeterEnabled = false;
            backend.monitorInput(currentPage == Page.DSP && inputMeterEnabled ? selectedInputSource : null);
        }
    }

    protected void publishGnomeState() {
        gnomeBridge.publishState(snapshot, store.snapshot());
    }

    protected void applyRoutes() {
        Map<String, Object> config = store.snapshot();
        autoApplySuppressedUntilRestart = false;
        setNotice(t("Обновляю состояние PipeWire и применяю граф…", "Refreshing PipeWire and applying the graph…"), false);
        backend.refresh().thenAccept(current -> {
            snapshot = current;
            updateMeterTarget();
            CompletableFuture<PipeWireRouteManager.ApplyReport> apply = requestRouteApply(config, current, true, false);
            if (apply == null) {
                Platform.runLater(() -> setNotice(t("Граф уже обновляется; повторный запрос объединён с текущим.",
                        "The graph is already updating; this request was coalesced."), false));
                return;
            }
            apply.whenComplete((report, error) -> Platform.runLater(() -> {
                RouteApplyGate.Completion completion = finishRouteApply(error == null);
                if (error != null) {
                    routesRequested = true;
                    setNotice(t("Граф PipeWire не применён: ", "PipeWire graph was not applied: ") + rootMessage(error), true);
                } else {
                    routesRequested = routeManager.active();
                    autoApplySuppressedUntilRestart = false;
                    String summary = t("Создано шин: ", "Buses: ") + report.buses()
                            + t(", маршрутов: ", ", routes: ") + report.routes()
                            + t(", потоков: ", ", streams: ") + report.streams();
                    if (!report.warnings().isEmpty()) summary += " · " + String.join("; ", report.warnings());
                    setNotice(summary, false);
                }
                retryPendingRouteApply(completion);
                showPage(currentPage);
            }));
        });
    }

    protected void clearRoutes() {
        routesRequested = false;
        dspReferenceMode = false;
        autoApplySuppressedUntilRestart = true;
        routeManager.clear().whenComplete((ignored, error) -> Platform.runLater(() -> {
            routeApplyGate.reset();
            if (error != null) setNotice(t("Не удалось сбросить граф: ", "Could not clear the graph: ") + rootMessage(error), true);
            else setNotice(t("Маршруты сброшены; исходные связи потоков восстановлены.", "Routes cleared; original stream links restored."), false);
            showPage(currentPage);
        }));
    }
}
