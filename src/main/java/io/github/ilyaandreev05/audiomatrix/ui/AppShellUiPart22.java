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

abstract class AppShellUiPart22 extends AppShellUiPart21 {
    protected AppShellUiPart22(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected void editBus(Map<String, Object> existing) {
        Map<String, Object> source = existing == null ? DefaultConfigMap.map("name", "", "role", "general", "channels", "stereo", "volume", 1.0, "mute", false) : existing;
        Dialog<javafx.scene.control.ButtonType> dialog = new Dialog<>();
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.setTitle(existing == null ? t("Добавить шину", "Add bus") : t("Изменить шину", "Edit bus"));
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        TextField name = field(string(source.get("name"), ""), t("Имя шины", "Bus name"));
        ComboBox<String> role = new ComboBox<>(); role.getItems().setAll("general", "music", "effects", "subwoofer");
        String busId = string(source.get("id"), "");
        role.setValue(string(source.get("role"), ProfileRoutePolicy.builtInBusRole(busId)));
        role.setAccessibleText(t("Назначение шины для режимов профиля", "Bus purpose for profile modes"));
        role.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String value) { return switch (value == null ? "" : value) {
                case "general" -> t("Общая", "General"); case "music" -> t("Музыка", "Music");
                case "effects" -> t("Эффекты", "Effects"); case "subwoofer" -> t("Сабвуферная ветка", "Subwoofer branch");
                default -> value;
            }; }
            @Override public String fromString(String value) { return value; }
        });
        ComboBox<String> channels = new ComboBox<>(); channels.getItems().setAll("mono", "stereo", "5.1", "7.1"); channels.setValue(string(source.get("channels"), "stereo")); channels.setAccessibleText(t("Каналы шины", "Bus channels"));
        TextField volume = field(String.valueOf(number(source.get("volume"), 1)), t("Громкость 0–1", "Volume 0–1"));
        ComboBox<String> filtersRef = new ComboBox<>(); filtersRef.getItems().add("none");
        map(map(store.snapshot().get("dsp")).get("route_chains")).keySet().stream().sorted().map(id -> "dsp.route_chains." + id).forEach(filtersRef.getItems()::add);
        filtersRef.setValue(string(source.get("filters_ref"), "none")); filtersRef.setAccessibleText(t("EQ шины", "Bus EQ"));
        CheckBox muted = new CheckBox(t("Mute шины", "Mute bus")); muted.setSelected(Boolean.TRUE.equals(source.get("mute")));
        VBox content = new VBox(10, label(t("Название", "Name"), "muted"), name,
                label(t("Назначение для Haptic/Subwoofer", "Purpose for Haptic/Subwoofer modes"), "muted"), role,
                label(t("Каналы", "Channels"), "muted"), channels,
                label(t("Уровень 0–1", "Volume 0–1"), "muted"), volume,
                label(t("EQ шины (route chain)", "Bus EQ (route chain)"), "muted"), filtersRef, muted);
        dialog.getDialogPane().setContent(content);
        styleDialog(dialog);
        dialog.showAndWait().filter(javafx.scene.control.ButtonType.OK::equals).ifPresent(result -> {
            double level;
            try { level = Double.parseDouble(volume.getText().trim()); }
            catch (NumberFormatException error) { setNotice(t("Громкость должна быть числом от 0 до 1.", "Volume must be a number from 0 to 1."), true); return; }
            if (name.getText().isBlank() || role.getValue() == null || channels.getValue() == null || !Double.isFinite(level) || level < 0 || level > 1) {
                setNotice(t("Проверьте название, каналы и громкость шины.", "Check the bus name, channels, and volume."), true); return;
            }
            updateRouteConfig(existing == null ? t("Создать шину", "Create bus") : t("Изменить шину", "Edit bus"), config -> {
                Map<String, Object> bus;
                if (existing == null) {
                    String id = "user-" + UUID.randomUUID().toString().substring(0, 8);
                    bus = DefaultConfigMap.map("id", id, "name", name.getText().trim(), "role", role.getValue(), "channels", channels.getValue(), "volume", level, "mute", muted.isSelected());
                    maps(config.get("buses")).add(bus);
                    List<String> active = new ArrayList<>(stringList(activeProfile(config).get("active_buses"))); active.add(id); activeProfile(config).put("active_buses", active);
                } else {
                    String id = string(existing.get("id"), "");
                    bus = maps(config.get("buses")).stream().filter(item -> id.equals(item.get("id"))).findFirst().orElse(null);
                    if (bus == null) return;
                    bus.put("name", name.getText().trim()); bus.put("role", role.getValue()); bus.put("channels", channels.getValue()); bus.put("volume", level); bus.put("mute", muted.isSelected());
                }
                if ("none".equals(filtersRef.getValue())) bus.remove("filters_ref"); else bus.put("filters_ref", filtersRef.getValue());
            });
        });
    }

    protected void deleteBus(String id) {
        if (maps(store.snapshot().get("buses")).size() <= 1) { setNotice(t("Нельзя удалить последнюю шину.", "The last bus cannot be deleted."), true); return; }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, t("Связанные маршруты будут удалены, приложения переназначены на другую шину. Удалить?", "Related routes will be removed and applications moved to another bus. Delete it?"), javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        if (getScene() != null) confirm.initOwner(getScene().getWindow());
        confirm.setHeaderText(t("Удалить шину", "Delete bus"));
        confirm.showAndWait().filter(javafx.scene.control.ButtonType.OK::equals).ifPresent(button -> updateRouteConfig(t("Удалить шину ", "Delete bus ") + id, config -> {
            List<Map<String, Object>> buses = maps(config.get("buses"));
            String fallback = buses.stream().map(item -> string(item.get("id"), "")).filter(candidate -> !id.equals(candidate)).findFirst().orElse("");
            buses.removeIf(item -> id.equals(item.get("id")));
            Set<String> removedRoutes = new java.util.HashSet<>();
            maps(config.get("routes")).removeIf(route -> {
                boolean remove = id.equals(route.get("from")) || id.equals(route.get("to"));
                if (remove) removedRoutes.add(string(route.get("id"), ""));
                return remove;
            });
            for (Map<String, Object> profile : maps(config.get("profiles"))) {
                profile.put("active_buses", stringList(profile.get("active_buses")).stream().filter(candidate -> !id.equals(candidate)).toList());
                profile.put("active_routes", stringList(profile.get("active_routes")).stream().filter(candidate -> !removedRoutes.contains(candidate)).toList());
                if (stringList(profile.get("active_buses")).isEmpty() && !fallback.isBlank()) profile.put("active_buses", List.of(fallback));
            }
            for (Map<String, Object> application : maps(config.get("applications"))) if (id.equals(application.get("bus"))) {
                if (fallback.isBlank()) application.remove("bus"); else application.put("bus", fallback);
            }
        }));
    }

    protected List<Endpoint> routeEndpoints(Map<String, Object> config) {
        List<Endpoint> result = new ArrayList<>();
        for (Map<String, Object> bus : maps(config.get("buses"))) {
            String id = string(bus.get("id"), ""); result.add(new Endpoint(id, t("Шина · ", "Bus · ") + string(bus.get("name"), id), true, null));
        }
        result.add(new Endpoint("system_default", t("Выход · системный по умолчанию", "Output · system default"), false, null));
        Set<String> knownDevices = new java.util.HashSet<>();
        for (Map<String, Object> device : maps(config.get("devices"))) {
            String role = string(device.get("role"), "");
            if (!Set.of("sink", "output").contains(role)) continue;
            String id = string(device.get("id"), ""); knownDevices.add(id);
            AudioNode online = DeviceSelectorMatcher.resolve(physicalSinks(), id, maps(config.get("devices")));
            result.add(new Endpoint(id, t("Выход · ", "Output · ") + outputReferenceName(config, id), false, online));
        }
        for (AudioNode sink : physicalSinks()) {
            Map<String, Object> device = matchingDevice(config, sink);
            String id = device == null ? "live:" + sink.id() : string(device.get("id"), "");
            if (knownDevices.add(id)) result.add(new Endpoint(id, t("Выход · ", "Output · ") + nodeDisplayName(sink), false, sink));
        }
        return result;
    }
}
