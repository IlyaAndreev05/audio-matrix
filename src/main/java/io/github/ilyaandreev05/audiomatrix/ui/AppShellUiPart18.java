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
        requestGnomeStatePublication(null);
    }

    /**
     * Publishes one concrete observation. Called only on the {@code audio-matrix-gnome-state}
     * lane, so it reads no JavaFX widget and never regresses revision or capture time.
     */
    @Override
    protected void publishGnomeState(PipeWireBackend.Snapshot observation, VersionedConfig config) {
        if (gnomeBridge == null || observation == null || config == null) return;
        if (config.revision() < lastPublishedRevision) return;
        if (observation.capturedAtMillis() < lastPublishedCapturedAtMillis) return;
        lastPublishedRevision = config.revision();
        lastPublishedCapturedAtMillis = observation.capturedAtMillis();
        String activeRoute = String.valueOf(config.getOrDefault("active_route", ""));
        gnomeBridge.publishState(observation, config, config.revision(), dynamicGraphState(activeRoute));
    }

    protected void applyRoutes() {
        autoApplySuppressedUntilRestart = false;
        runOnFx(() -> setNotice(t("Обновляю состояние PipeWire и применяю граф…", "Refreshing PipeWire and applying the graph…"), false));
        runRuntimeAudio(() -> backend.refresh().whenComplete((current, refreshError) -> {
            if (refreshError != null) {
                runOnFx(() -> setNotice(t("Не удалось обновить состояние PipeWire: ", "Could not refresh PipeWire state: ") + rootMessage(refreshError), true));
                return;
            }
            runRuntimeAudio(() -> {
                if (current.capturedAtMillis() >= snapshot.capturedAtMillis()) snapshot = current;
                VersionedConfig config = store.snapshot();
                CompletableFuture<PipeWireRouteManager.ApplyReport> apply = requestRouteApply(config, current, true, false);
                if (apply == null) {
                    runOnFx(() -> setNotice(t("Граф уже обновляется; повторный запрос объединён с текущим.",
                            "The graph is already updating; this request was coalesced."), false));
                    return;
                }
                apply.whenComplete((report, error) -> runRuntimeAudio(() -> {
                    RouteApplyGate.Completion completion = finishRouteApply(error == null);
                    if (error != null) {
                        routesRequested = true;
                        runOnFx(() -> setNotice(t("Граф PipeWire не применён: ", "PipeWire graph was not applied: ") + rootMessage(error), true));
                    } else {
                        routesRequested = routeManager.active();
                        autoApplySuppressedUntilRestart = false;
                        String summary = t("Создано шин: ", "Buses: ") + report.buses()
                                + t(", маршрутов: ", ", routes: ") + report.routes()
                                + t(", потоков: ", ", streams: ") + report.streams();
                        if (!report.warnings().isEmpty()) summary += " · " + String.join("; ", report.warnings());
                        String text = summary;
                        runOnFx(() -> setNotice(text, false));
                    }
                    retryPendingRouteApply(completion);
                    requestUiRefresh();
                }));
            });
        }));
    }

    protected void clearRoutes() {
        routesRequested = false;
        dspReferenceMode = false;
        autoApplySuppressedUntilRestart = true;
        runRuntimeAudio(() -> routeManager.clear().whenComplete((ignored, error) -> runRuntimeAudio(() -> {
            routeApplyGate.reset();
            runOnFx(() -> {
                if (error != null) setNotice(t("Не удалось сбросить граф: ", "Could not clear the graph: ") + rootMessage(error), true);
                else setNotice(t("Маршруты сброшены; исходные связи потоков восстановлены.", "Routes cleared; original stream links restored."), false);
                showPage(currentPage);
            });
        })));
    }
}
