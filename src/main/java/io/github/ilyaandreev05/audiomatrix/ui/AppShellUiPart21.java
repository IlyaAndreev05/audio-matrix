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

abstract class AppShellUiPart21 extends AppShellUiPart20 {
    protected AppShellUiPart21(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected void applyConfiguredDeviceRules(PipeWireBackend.Snapshot current,Map<String,Object> config){
        if(!current.connected())return;
        Set<Integer> liveIds=current.sinks().stream().map(AudioNode::id).collect(java.util.stream.Collectors.toSet());appliedDeviceRules.keySet().removeIf(id->!liveIds.contains(id));
        for(AudioNode sink:current.sinks()){
            Map<String,Object> device=matchingDevice(config,sink);if(device==null){appliedDeviceRules.remove(sink.id());continue;}
            double volume=Math.max(0,Math.min(1,number(device.get("volume"),1.0)));boolean muted=Boolean.TRUE.equals(device.get("mute"));
            String signature=String.valueOf(device.get("id"))+"|"+map(device.get("selector"))+"|"+volume+"|"+muted;if(signature.equals(appliedDeviceRules.get(sink.id())))continue;appliedDeviceRules.put(sink.id(),signature);
            List<CompletableFuture<Void>> changes=new ArrayList<>();if(!Double.isFinite(sink.volume())||Math.abs(sink.volume()-volume)>.01)changes.add(backend.setVolume(sink.id(),volume));if(sink.muted()!=muted)changes.add(backend.setMute(sink.id(),muted));
            CompletableFuture.allOf(changes.toArray(CompletableFuture<?>[]::new)).whenComplete((ignored,error)->{if(error!=null&&appliedDeviceRules.remove(sink.id(),signature))Platform.runLater(()->setNotice(t("Не удалось применить правило устройства ","Could not apply device rule for ")+nodeDisplayName(sink)+": "+rootMessage(error),true));});
        }
    }

    protected void applyConfiguredStreamRules(PipeWireBackend.Snapshot current, Map<String, Object> config) {
        streamRuleApplier.apply(current, maps(config.get("applications")), (stream, error) ->
                Platform.runLater(() -> setNotice(t("Не удалось применить правило потока ", "Could not apply stream rule for ")
                        + stream.applicationName() + ": " + rootMessage(error), true)));
    }

    protected Map<String, Object> ensureDevice(Map<String, Object> config, AudioNode node) {
        Map<String,Object> existing=matchingDevice(config,node);if(existing!=null)return existing;
        List<Map<String,Object>> devices=maps(config.get("devices"));String id="device-"+UUID.randomUUID().toString().substring(0,8);
        Map<String,Object> item=DefaultConfigMap.map("id",id,"selector",DefaultConfigMap.map("kind","name_equals","value",node.name()),"volume",finite(node.volume())?Math.max(0,Math.min(1,node.volume())):1.0,"mute",node.muted());devices.add(item);return item;
    }

    protected Map<String,Object> matchingDevice(Map<String,Object> config,AudioNode node){
        List<Map<String,Object>> matches=maps(config.get("devices")).stream().filter(device->{List<AudioNode> resolved=matchingNodes(device);return resolved.size()==1&&resolved.getFirst().id()==node.id()&&resolved.getFirst().kind().equals(node.kind());}).toList();
        return matches.size()==1?matches.getFirst():null;
    }

    protected AudioNode resolveDevice(Map<String,Object> config,Map<String,Object> device){List<AudioNode> resolved=matchingNodes(device);return resolved.size()==1?resolved.getFirst():null;}

    protected String nodeDisplayName(AudioNode node){Map<String,Object> device=matchingDevice(store.snapshot(),node);String name=device==null?"":string(device.get("name"),"");return name.isBlank()?node.description():name;}

    protected void editDeviceSelector(Map<String,Object> savedDevice,AudioNode node){
        Map<String,Object> config=store.snapshot();Map<String,Object> current=savedDevice==null?null:maps(config.get("devices")).stream().filter(device->String.valueOf(savedDevice.get("id")).equals(device.get("id"))).findFirst().orElse(null);
        String id=current==null?"":String.valueOf(current.get("id"));Map<String,Object> oldSelector=current==null?DefaultConfigMap.map("kind","name_equals","value",node==null?"":node.name()):map(current.get("selector"));
        Dialog<Map<String,Object>> dialog=new Dialog<>();if(getScene()!=null)dialog.initOwner(getScene().getWindow());dialog.setTitle(t("Имя и selector устройства","Device name and selector"));dialog.setHeaderText(current==null?node.description():string(current.get("name"),id));dialog.getDialogPane().setPrefWidth(600);styleDialog(dialog);
        TextField alias=field(current==null?"":string(current.get("name"),""),t("Понятное имя устройства","Device display name"));ComboBox<String> kind=new ComboBox<>();kind.getItems().setAll("name_equals","name_contains","description_contains");kind.setValue(string(oldSelector.get("kind"),"name_equals"));kind.setAccessibleText(t("Тип selector","Selector kind"));TextField value=field(string(oldSelector.get("value"),""),t("Значение selector","Selector value"));Label error=label("", "danger");
        ComboBox<String> role = new ComboBox<>();
        role.getItems().setAll("unassigned", "haptic_output", "subwoofer", "speaker", "speakers", "output", "microphone", "accelerometer", "input", "source");
        String selectedRole = string(current == null ? null : current.get("role"), "unassigned");
        if (!role.getItems().contains(selectedRole)) role.getItems().add(selectedRole);
        role.setValue(selectedRole);
        role.setAccessibleText(t("Роль устройства для режимов профиля", "Device role for profile modes"));
        role.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String value) {
                if (value == null) return "";
                return switch (value) {
                    case "unassigned" -> t("Не назначена", "Unassigned");
                    case "haptic_output" -> t("Вибровыход (haptic_output)", "Haptic output (haptic_output)");
                    case "subwoofer" -> t("Сабвуфер (subwoofer)", "Subwoofer (subwoofer)");
                    case "speaker", "speakers" -> t("Колонки (speaker)", "Speakers (speaker)");
                    case "output" -> t("Общий выход (output)", "Generic output (output)");
                    case "microphone" -> t("Микрофон (microphone)", "Microphone (microphone)");
                    case "accelerometer" -> t("Акселерометр (accelerometer)", "Accelerometer (accelerometer)");
                    case "input", "source" -> t("Общий вход (input)", "Generic input (input)");
                    default -> value;
                };
            }
            @Override public String fromString(String value) { return value; }
        });
        VBox content=new VBox(8,label(t("Понятное имя (необязательно)","Display name (optional)"),"muted"),alias,label(t("Стабильный selector","Stable selector"),"muted"),new HBox(8,kind,value),label(t("Роль выхода/входа. Новым устройствам роль не назначается автоматически.","Output/input role. New devices are not assigned a role automatically."),"muted"),role,error);HBox.setHgrow(value,Priority.ALWAYS);dialog.getDialogPane().setContent(content);dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,javafx.scene.control.ButtonType.CANCEL);Button save=(Button)dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK);
        Runnable validate=()->{Map<String,Object> selector=DefaultConfigMap.map("kind",kind.getValue(),"value",value.getText().trim());List<AudioNode> all=new ArrayList<>(physicalSinks());all.addAll(snapshot.sources());long count=all.stream().filter(candidate->DeviceSelectorMatcher.matches(candidate,selector)).count();boolean targetMatches=node==null||DeviceSelectorMatcher.matches(node,selector);String issue=kind.getValue()==null?t("Выберите тип selector.","Choose a selector kind."):value.getText().isBlank()?t("Введите значение selector.","Enter a selector value."):!targetMatches?t("Selector должен находить выбранное устройство.","The selector must match the selected device."):count>1?t("Selector неоднозначен: он подходит нескольким подключённым устройствам.","The selector is ambiguous and matches multiple connected devices."):"";error.setText(issue);error.setManaged(!issue.isEmpty());error.setVisible(!issue.isEmpty());save.setDisable(!issue.isEmpty());};
        kind.valueProperty().addListener((o,a,b)->validate.run());value.textProperty().addListener((o,a,b)->validate.run());validate.run();
        dialog.setResultConverter(button->button==javafx.scene.control.ButtonType.OK?DefaultConfigMap.map("name",alias.getText().trim(),"selector",DefaultConfigMap.map("kind",kind.getValue(),"value",value.getText().trim()),"role",role.getValue()):null);
        dialog.showAndWait().ifPresent(result->updateConfig(c->{Map<String,Object> device=current==null?ensureDevice(c,node):maps(c.get("devices")).stream().filter(item->id.equals(item.get("id"))).findFirst().orElse(null);if(device==null)return;device.put("selector",result.get("selector"));if(String.valueOf(result.get("name")).isBlank())device.remove("name");else device.put("name",result.get("name"));if("unassigned".equals(result.get("role")))device.remove("role");else device.put("role",result.get("role"));}));
    }

    protected void editOutputEq(Map<String, Object> savedDevice, AudioNode node) {
        if (savedDevice == null && node == null) return;
        Map<String, Object> config = store.snapshot();
        String id = savedDevice == null ? "" : string(savedDevice.get("id"), "");
        Map<String, Object> current = savedDevice == null ? null : maps(config.get("devices")).stream()
                .filter(device -> id.equals(device.get("id"))).findFirst().orElse(null);
        ComboBox<String> filtersRef = new ComboBox<>();
        filtersRef.getItems().add("none");
        map(map(config.get("dsp")).get("route_chains")).keySet().stream().sorted()
                .map(chainId -> "dsp.route_chains." + chainId).forEach(filtersRef.getItems()::add);
        String selected = string(current == null ? null : current.get("filters_ref"), "none");
        if (!filtersRef.getItems().contains(selected)) filtersRef.getItems().add(selected);
        filtersRef.setValue(selected);
        filtersRef.setAccessibleText(t("EQ-цепочка физического выхода", "Physical output EQ chain"));
        Dialog<javafx.scene.control.ButtonType> dialog = new Dialog<>();
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.setTitle(t("Коррекция выхода", "Output correction"));
        dialog.setHeaderText(current == null ? nodeDisplayName(node) : string(current.get("name"), id));
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        dialog.getDialogPane().setContent(new VBox(8,
                label(t("Общая цепочка применяется после сведения всех маршрутов на этот выход.",
                        "This shared chain runs after all routes to this output have been mixed."), "muted"), filtersRef));
        styleDialog(dialog);
        dialog.showAndWait().filter(javafx.scene.control.ButtonType.OK::equals).ifPresent(button -> updateConfig(candidate -> {
            Map<String, Object> device = current == null ? ensureDevice(candidate, node)
                    : maps(candidate.get("devices")).stream().filter(item -> id.equals(item.get("id"))).findFirst().orElse(null);
            if (device == null) return;
            if ("none".equals(filtersRef.getValue())) device.remove("filters_ref");
            else device.put("filters_ref", filtersRef.getValue());
        }));
    }

    protected Map<String, Object> ensureApplication(Map<String, Object> config, AudioNode stream) {
        List<Map<String, Object>> applications = maps(config.get("applications"));
        Map<String, Object> existing = ApplicationRuleMatcher.find(applications, stream);
        if (existing != null) return existing;
        String process = stream.processName(); String appId = stream.applicationId();
        Map<String, Object> match = new LinkedHashMap<>();
        if (!process.isBlank()) match.put("process", process);
        if (!appId.isBlank()) match.put("app_id", appId);
        if (match.isEmpty()) match.put("process", stream.name());
        String identity = !process.isBlank() ? process : !appId.isBlank() ? appId : stream.name();
        Map<String, Object> item = DefaultConfigMap.map("id", slug(identity), "match", match, "volume", 1.0, "mute", false, "bus", "main", "preferred_output", "system_default");
        applications.add(item); return item;
    }

    protected HBox busRow(Map<String, Object> bus, boolean active) {
        String id = string(bus.get("id"), "");
        HBox row = new HBox(10); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("node-card");
        VBox details = new VBox(4, label(string(bus.get("name"), id), "card-title"),
                label(string(bus.get("channels"), "stereo") + " · " + volumeLabel(number(bus.get("volume"), 1))
                        + (Boolean.TRUE.equals(bus.get("mute")) ? " · Mute" : "")
                        + (bus.get("filters_ref") == null ? "" : " · EQ: " + string(bus.get("filters_ref"), "")), "muted"));
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().addAll(details, spacer,
                actionButton(active ? t("Отключить", "Deactivate") : t("Включить", "Activate"), () -> setProfileMembership("active_buses", id, !active)),
                actionButton(t("Изменить", "Edit"), () -> editBus(bus)),
                actionButton(t("Удалить", "Delete"), () -> deleteBus(id)));
        return row;
    }

    protected HBox routeRow(Map<String, Object> route, boolean active) {
        Map<String, Object> config = store.snapshot();
        String id = string(route.get("id"), "");
        String polarity = string(route.get("polarity"), "normal");
        String routeChannels = route.containsKey("channel_matrix") ? t("произвольная матрица", "custom matrix")
                : string(route.get("channel_map"), "stereo");
        String summary = endpointLabel(config, string(route.get("from"), "")) + " → " + endpointLabel(config, string(route.get("to"), ""))
                + " · " + number(route.get("gain_db"), 0) + " dB · " + number(route.get("delay_ms"), 0) + " ms · " + routeChannels
                + " · p" + number(route.get("priority"), 0)
                + ("normal".equals(polarity) ? "" : " · " + t("инверсия фазы: ", "phase inverted: ") + polarityLabel(polarity))
                + (route.get("filters_ref") == null ? "" : " · EQ: " + string(route.get("filters_ref"), ""))
                + (route.get("fallback") == null ? "" : " · " + t("fallback: ", "fallback: ") + endpointLabel(config, string(route.get("fallback"), "")));
        HBox row = new HBox(10); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("node-card");
        VBox details = new VBox(4, label(string(route.get("name"), id), "card-title"), label(summary, "muted"));
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().addAll(details, spacer,
                actionButton(active ? t("Отключить", "Deactivate") : t("Включить", "Activate"), () -> setProfileMembership("active_routes", id, !active)),
                actionButton(t("Изменить", "Edit"), () -> editRoute(route)),
                actionButton(t("Удалить", "Delete"), () -> deleteRoute(id)));
        return row;
    }

    protected String endpointLabel(Map<String, Object> config, String id) {
        Map<String, Object> bus = maps(config.get("buses")).stream().filter(item -> id.equals(item.get("id"))).findFirst().orElse(null);
        if (bus != null) return string(bus.get("name"), id);
        if ("system_default".equals(id)) return t("системный выход", "system default");
        return outputReferenceName(config, id);
    }

    protected void setProfileMembership(String key, String id, boolean enabled) {
        String target = "active_buses".equals(key) ? t("шину ", "bus ") : t("маршрут ", "route ");
        String description = (enabled ? t("Включить ", "Activate ") : t("Отключить ", "Deactivate ")) + target + id;
        updateRouteConfig(description, config -> {
            Map<String, Object> profile = activeProfile(config);
            List<String> members = new ArrayList<>(stringList(profile.get(key)));
            if (enabled && !members.contains(id)) members.add(id);
            if (!enabled) members.removeIf(id::equals);
            profile.put(key, members);
        });
    }
}
