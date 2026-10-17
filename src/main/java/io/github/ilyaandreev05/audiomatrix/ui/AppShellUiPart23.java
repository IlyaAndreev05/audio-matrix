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

abstract class AppShellUiPart23 extends AppShellUiPart22 {
    protected AppShellUiPart23(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected void editRoute(Map<String, Object> existing) {
        Map<String, Object> config = store.snapshot();
        List<Endpoint> endpoints = routeEndpoints(config);
        List<Endpoint> fromEndpoints = endpoints.stream().filter(Endpoint::bus).toList();
        Dialog<javafx.scene.control.ButtonType> dialog = new Dialog<>();
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.setTitle(existing == null ? t("Добавить маршрут", "Add route") : t("Изменить маршрут", "Edit route"));
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        TextField name = field(string(existing == null ? null : existing.get("name"), existing == null ? t("Новый маршрут", "New route") : string(existing.get("id"), "")), t("Название маршрута", "Route name"));
        ComboBox<Endpoint> from = endpointCombo(fromEndpoints); from.setPromptText(t("Из шины", "From bus")); from.setAccessibleText(t("Шина-источник", "Source bus"));
        ComboBox<Endpoint> to = endpointCombo(endpoints); to.setPromptText(t("В шину или выход", "To bus or output")); to.setAccessibleText(t("Назначение маршрута", "Route destination"));
        if (existing != null) {
            fromEndpoints.stream().filter(item -> item.id().equals(existing.get("from"))).findFirst().ifPresent(from::setValue);
            endpoints.stream().filter(item -> item.id().equals(existing.get("to"))).findFirst().ifPresent(to::setValue);
        }
        AtomicReference<Map<String, Object>> channelMatrix = new AtomicReference<>(existing != null && existing.get("channel_matrix") instanceof Map<?, ?> matrix
                ? copyMap(map(matrix)) : null);
        double maximumRouteGain = limiterEnabled(config) ? 24 : 0;
        TextField gain = field(String.valueOf(number(existing == null ? null : existing.get("gain_db"), 0)),
                maximumRouteGain > 0 ? t("Gain dB −60…24", "Gain dB −60…24") : t("Gain dB −60…0 (limiter выключен)", "Gain dB −60…0 (limiter disabled)"));
        TextField volume = field(String.valueOf(number(existing == null ? null : existing.get("volume"), 1)), t("Громкость 0–1", "Volume 0–1"));
        TextField delay = field(String.valueOf(number(existing == null ? null : existing.get("delay_ms"), 0)), t("Задержка ms 0–2000", "Delay ms 0–2000"));
        ComboBox<String> channelMap = new ComboBox<>(); channelMap.getItems().setAll("stereo", "mono", "mono_sum", "left", "right", "swap_lr",
                "center", "lfe", "rear_left", "rear_right", "rear_sum", "side_left", "side_right", "side_sum", "surround_sum", "all_sum");
        channelMap.setValue(string(existing == null ? null : existing.get("channel_map"), "stereo")); channelMap.setAccessibleText(t("Channel map", "Channel map"));
        ComboBox<String> polarity = new ComboBox<>(); polarity.getItems().setAll("normal", "invert", "invert_left", "invert_right");
        polarity.setValue(string(existing == null ? null : existing.get("polarity"), "normal"));
        polarity.setAccessibleText(t("Полярность маршрута", "Route polarity"));
        polarity.setCellFactory(items -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : polarityOptionLabel(item)); }
        });
        polarity.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : polarityOptionLabel(item)); }
        });
        Runnable refreshPolarityOptions = () -> {
            String selected = polarity.getValue();
            Set<String> outputPositions;
            try { outputPositions = channelMatrix.get() == null ? null : Set.copyOf(ChannelMatrix.parse(channelMatrix.get()).outputs()); }
            catch (IllegalArgumentException ignored) { outputPositions = Set.of(); }
            boolean stereo = outputPositions != null ? outputPositions.containsAll(Set.of("FL", "FR"))
                    : !Set.of("mono", "mono_sum", "left", "right", "center", "lfe", "rear_left", "rear_right",
                            "rear_sum", "side_left", "side_right", "side_sum", "surround_sum", "all_sum").contains(channelMap.getValue());
            polarity.getItems().setAll(stereo ? List.of("normal", "invert", "invert_left", "invert_right") : List.of("normal", "invert"));
            if (selected != null && polarity.getItems().contains(selected)) polarity.setValue(selected);
            else polarity.setValue("normal");
        };
        channelMap.valueProperty().addListener((observable, oldValue, newValue) -> refreshPolarityOptions.run());
        refreshPolarityOptions.run();
        Button clearMatrix = actionButton(t("Убрать матрицу", "Clear matrix"), () -> {});
        clearMatrix.setOnAction(event -> {
            channelMatrix.set(null);
            channelMap.setDisable(false);
            clearMatrix.setDisable(true);
            refreshPolarityOptions.run();
        });
        clearMatrix.setDisable(channelMatrix.get() == null);
        Button matrixButton = actionButton(t("Редактировать матрицу каналов…", "Edit channel matrix…"), () -> {
            Endpoint source = from.getValue(), target = to.getValue();
            if (source == null || target == null) {
                setNotice(t("Сначала выберите шину-источник и назначение маршрута.", "Choose a source bus and route destination first."), true);
                return;
            }
            List<String> inputs = routeMatrixBusPositions(config, source.id());
            List<String> outputs = routeMatrixEndpointPositions(config, target);
            if (outputs.isEmpty() && channelMatrix.get() != null) {
                try { outputs = ChannelMatrix.parse(channelMatrix.get()).outputs(); }
                catch (IllegalArgumentException ignored) { }
            }
            if (inputs.isEmpty() || outputs.isEmpty()) {
                setNotice(t("Нужны доступные сведения о каналах источника и назначения.", "Channel layouts for the selected source and destination must be available."), true);
                return;
            }
            Map<String, Object> updated = editChannelMatrix(inputs, outputs, channelMatrix.get(), limiterEnabled(config));
            if (updated != null) {
                channelMatrix.set(updated);
                channelMap.setDisable(true);
                clearMatrix.setDisable(false);
                refreshPolarityOptions.run();
            }
        });
        channelMap.setDisable(channelMatrix.get() != null);
        ComboBox<String> filtersRef = new ComboBox<>(); filtersRef.getItems().add("none");
        map(map(config.get("dsp")).get("route_chains")).keySet().stream().sorted().map(id -> "dsp.route_chains." + id).forEach(filtersRef.getItems()::add);
        String currentFiltersRef = string(existing == null ? null : existing.get("filters_ref"), "none");
        if (!filtersRef.getItems().contains(currentFiltersRef)) filtersRef.getItems().add(currentFiltersRef);
        filtersRef.setValue(currentFiltersRef); filtersRef.setAccessibleText(t("EQ-цепочка маршрута", "Route EQ chain"));
        CheckBox muted = new CheckBox(t("Mute маршрута", "Mute route")); muted.setSelected(Boolean.TRUE.equals(existing == null ? false : existing.get("mute")));
        CheckBox enabled = new CheckBox(t("Маршрут включён", "Route enabled")); enabled.setSelected(existing == null || !Boolean.FALSE.equals(existing.get("enabled")));
        TextField priority = field(String.valueOf((int) number(existing == null ? null : existing.get("priority"), 0)), t("Приоритет 0–1000", "Priority 0–1000"));
        VBox gainField = new VBox(3, label(maximumRouteGain > 0 ? t("Gain, dB · −60…24", "Gain, dB · −60…24")
                : t("Gain, dB · −60…0", "Gain, dB · −60…0"), "muted"), gain);
        VBox volumeField = new VBox(3, label(t("Громкость · 0–1", "Volume · 0–1"), "muted"), volume);
        VBox delayField = new VBox(3, label(t("Задержка, ms · 0–2000", "Delay, ms · 0–2000"), "muted"), delay);
        VBox priorityField = new VBox(3, label(t("Приоритет · 0–1000", "Priority · 0–1000"), "muted"), priority);
        List<Endpoint> fallbackOptions = endpoints.stream().filter(endpoint -> !endpoint.bus()).toList();
        ComboBox<Endpoint> fallback = endpointCombo(fallbackOptions); fallback.setPromptText(t("Без резервного выхода", "No fallback output")); fallback.setAccessibleText(t("Резервный выход", "Fallback output"));
        if (existing != null && existing.get("fallback") != null) fallbackOptions.stream().filter(item -> item.id().equals(existing.get("fallback"))).findFirst().ifPresent(fallback::setValue);
        VBox content = new VBox(8, label(t("Имя", "Name"), "muted"), name, label(t("Источник", "Source"), "muted"), from,
                label(t("Назначение", "Destination"), "muted"), to, new HBox(8, gainField, volumeField), new HBox(8, delayField, priorityField),
                label(t("Channel map", "Channel map"), "muted"), channelMap,
                new HBox(8, matrixButton, clearMatrix),
                label(t("Коэффициенты матрицы: строки — выходы, столбцы — входы. Отрицательный коэффициент инвертирует фазу. Сумма модулей в строке выше 1 требует limiter.",
                        "Matrix rows are outputs and columns are inputs. Negative coefficients invert phase. A row absolute-sum above 1 requires the output limiter."), "muted"),
                label(t("Полярность", "Polarity"), "muted"), polarity,
                label(t("EQ-цепочка (необязательно)", "EQ chain (optional)"), "muted"), filtersRef,
                label(t("Резервный выход при недоступности цели", "Fallback if the destination is unavailable"), "muted"), fallback, muted, enabled);
        dialog.getDialogPane().setContent(content);
        styleDialog(dialog);
        dialog.showAndWait().filter(javafx.scene.control.ButtonType.OK::equals).ifPresent(button -> {
            if (name.getText().isBlank() || from.getValue() == null || to.getValue() == null) { setNotice(t("Заполните имя, шину и назначение.", "Provide a name, source bus, and destination."), true); return; }
            double gainDb, level, delayMs; int priorityValue;
            try { gainDb = Double.parseDouble(gain.getText().trim()); level = Double.parseDouble(volume.getText().trim()); delayMs = Double.parseDouble(delay.getText().trim()); priorityValue = Integer.parseInt(priority.getText().trim()); }
            catch (NumberFormatException error) { setNotice(t("Gain, громкость, задержка и приоритет должны быть числами.", "Gain, volume, delay, and priority must be numeric."), true); return; }
            if (!Double.isFinite(gainDb) || gainDb < -60 || gainDb > maximumRouteGain || !Double.isFinite(level) || level < 0 || level > 1 || !Double.isFinite(delayMs) || delayMs < 0 || delayMs > 2000 || priorityValue < 0 || priorityValue > 1000 || channelMap.getValue() == null) {
                setNotice(t("Проверьте пределы Gain, громкости, задержки, приоритета и channel map.", "Check the Gain, volume, delay, priority, and channel map limits."), true); return;
            }
            String routeId = existing == null ? "route-" + UUID.randomUUID().toString().substring(0, 8) : string(existing.get("id"), "");
            Endpoint target = to.getValue();
            String targetId = target.id();
            if (target.node() != null) {
                Map<String, Object> device = ensureDevice(config, target.node());
                targetId = string(device.get("id"), targetId);
            }
            String fallbackId = null;
            if (fallback.getValue() != null) {
                Endpoint selectedFallback = fallback.getValue(); fallbackId = selectedFallback.id();
                if (selectedFallback.node() != null) fallbackId = string(ensureDevice(config, selectedFallback.node()).get("id"), fallbackId);
            }
            Map<String, Object> route = DefaultConfigMap.map("id", routeId, "name", name.getText().trim(), "from", from.getValue().id(), "to", targetId,
                    "gain_db", gainDb, "volume", level, "mute", muted.isSelected(), "delay_ms", delayMs, "priority", priorityValue,
                    "channel_map", channelMap.getValue(), "polarity", polarity.getValue(), "enabled", enabled.isSelected());
            if (channelMatrix.get() != null) route.put("channel_matrix", channelMatrix.get());
            if (filtersRef.getValue() != null && !"none".equals(filtersRef.getValue())) route.put("filters_ref", filtersRef.getValue());
            if (fallbackId != null) route.put("fallback", fallbackId);
            List<Map<String, Object>> candidateRoutes = maps(config.get("routes"));
            int oldIndex = -1;
            if (existing != null) for (int i = 0; i < candidateRoutes.size(); i++) if (routeId.equals(candidateRoutes.get(i).get("id"))) { oldIndex = i; break; }
            if (oldIndex >= 0) candidateRoutes.set(oldIndex, route); else candidateRoutes.add(route);
            List<String> active = new ArrayList<>(stringList(activeProfile(config).get("active_routes")));
            if (!active.contains(routeId)) active.add(routeId);
            activeProfile(config).put("active_routes", active);
            List<String> errors = ConfigValidator.validate(config);
            if (!errors.isEmpty()) { setNotice(t("Маршрут не сохранён: ", "Route not saved: ") + String.join("; ", errors), true); return; }
            saveRouteConfig(existing == null ? t("Создать маршрут", "Create route") : t("Изменить маршрут", "Edit route"), config);
        });
    }
}
