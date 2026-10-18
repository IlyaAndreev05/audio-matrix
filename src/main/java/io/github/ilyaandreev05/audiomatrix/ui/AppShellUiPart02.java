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

abstract class AppShellUiPart02 extends AppShellUiPart01 {
    protected AppShellUiPart02(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected VBox homePage() {
        VBox page = page(t("Обзор текущего звукового графа и доступных выходов.", "Current audio graph and available outputs."));
        HBox cards = new HBox(14);
        cards.getChildren().addAll(metricCard(t("Выходы", "Outputs"), Integer.toString(physicalSinks().size()), t("физические PipeWire sinks", "physical PipeWire sinks")),
                metricCard(t("Источники", "Sources"), Integer.toString(snapshot.sources().size()), t("входы, доступные системе", "inputs visible to the system")),
                metricCard(t("Потоки", "Streams"), Integer.toString(snapshot.streams().size()), t("сейчас воспроизводят звук", "currently playing")));
        cards.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));
        page.getChildren().add(cards);

        VBox outputCard = card(t("Управление физическим выходом", "Physical output control"));
        outputCard.getChildren().add(label(t("Этот ползунок управляет уровнем выбранного PipeWire sink. Он не измеряет акустические децибелы.", "This slider controls the selected PipeWire sink. It does not measure acoustic decibels."), "muted"));
        if (!snapshot.connected()) {
            outputCard.getChildren().add(label(snapshot.error().isBlank() ? t("PipeWire ещё не подключён.", "PipeWire has not connected yet.") : snapshot.error(), "danger"));
        } else if (physicalSinks().isEmpty()) {
            outputCard.getChildren().add(label(t("Система не сообщает ни одного звукового выхода.", "The system reports no audio outputs."), "warning"));
        } else {
            ComboBox<AudioNode> outputs = new ComboBox<>(); outputs.getItems().setAll(physicalSinks());
            outputs.setMaxWidth(Double.MAX_VALUE); outputs.setAccessibleText(t("Выходное устройство", "Output device"));
            outputs.setCellFactory(list -> nodeCell()); outputs.setButtonCell(nodeCell());
            AudioNode configured = configuredSink();
            AudioNode actual = physicalSinks().stream().filter(n -> n.id() == snapshot.defaultSinkId()).findFirst().orElse(null);
            AudioNode previous=selectedSink;
            selectedSink = previous==null?null:physicalSinks().stream().filter(node->node.name().equals(previous.name())).findFirst().orElse(null);
            if(selectedSink==null)selectedSink=configured!=null?configured:actual;
            if (selectedSink != null) outputs.getSelectionModel().select(selectedSink);
            outputs.setOnAction(event -> {
                AudioNode selected = outputs.getValue(); if (selected == null) return;
                selectedSink = selected;
                setNotice(t("Выход выбран для индивидуальных настроек и измерения.", "Output selected for individual controls and measurements."), false);
                showPage(Page.HOME);
            });
            Label outputLabel = label(t("Выходное устройство", "Output device"), ""); outputLabel.setLabelFor(outputs);
            Slider level = new Slider(0, 1, selectedSink != null && !selectedSink.muted() && Double.isFinite(selectedSink.volume()) ? Math.min(1, selectedSink.volume()) : 0);
            level.setAccessibleText(t("Громкость выходного устройства", "Output device volume")); level.setShowTickMarks(false);
            Label percent = label(volumeLabel(selectedSink == null ? Double.NaN : selectedSink.muted()?0:selectedSink.volume()), "stat-value");
            PauseTransition debounce = new PauseTransition(Duration.millis(160));
            level.valueProperty().addListener((obs, old, value) -> {
                percent.setText(volumeLabel(value.doubleValue()));
                if (selectedSink == null) return;
                AudioNode target = selectedSink;
                debounce.setOnFinished(e -> backend.setMute(target.id(),false).thenCompose(ignored->backend.setVolume(target.id(), level.getValue())).whenComplete((v, error) -> {
                    if (error != null) Platform.runLater(() -> setNotice(t("Не удалось изменить уровень: ", "Could not change the level: ") + rootMessage(error), true));
                }));
                debounce.playFromStart();
            });
            level.setOnMouseReleased(event -> { if (selectedSink != null) saveDeviceVolume(selectedSink, level.getValue()); });
            level.setOnKeyReleased(event -> { if (selectedSink != null) saveDeviceVolume(selectedSink, level.getValue()); });
            Button mute = actionButton(selectedSink != null && selectedSink.muted() ? t("Включить звук", "Unmute") : t("Выключить звук", "Mute"), () -> {
                AudioNode target = selectedSink; if (target == null) return;
                backend.setMute(target.id(), !target.muted()).whenComplete((v, error) -> Platform.runLater(() -> {
                    if (error != null) setNotice(rootMessage(error), true); else { saveDeviceMute(target, !target.muted()); refreshBackend(); }
                }));
            });
            Button refresh = actionButton(t("Обновить состояние", "Refresh state"), this::refreshBackend);
            HBox levelRow = new HBox(12, percent, level); levelRow.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(level, Priority.ALWAYS);
            outputCard.getChildren().addAll(outputLabel, outputs, levelRow, new HBox(10, mute, refresh));
        }
        page.getChildren().add(outputCard);

        VBox state = card(t("Что применяется сейчас", "What is applied now"));
        boolean processingActive = routeManager.active() && routeManager.status().contains("PipeWire filters");
        state.getChildren().addAll(
                label(t("Громкость sink, mute и выбор default выходят через wpctl и проверяются по PipeWire.", "Sink volume, mute, and default output are changed through wpctl and read back from PipeWire."), ""),
                label(processingActive
                        ? t("PipeWire-маршруты и фильтрующие цепочки активны; limiter стоит после сведения выходных ветвей.", "PipeWire routes and processing chains are active; the limiter follows the output mix.")
                        : routeManager.active()
                        ? t("PipeWire-маршруты активны; фильтры выхода выключены.", "PipeWire routes are active; output processing is disabled.")
                        : t("Шины и маршруты применяются на экране маршрутов; выходная filter-chain создаётся при включённом лимитере или коррекции.", "Apply buses and routes on the Routes page; output filter-chains are created when limiting or correction is enabled."),
                        routeManager.active() ? "accent" : "warning"),
                label(t("Фактические аудиометры требуют meter-node; в этой сборке входной и выходной уровень не показывается как будто он измерен.", "Actual audio meters need a meter node; this build does not present input/output levels as measured."), "muted"));
        page.getChildren().add(state);
        return page;
    }

    protected VBox applicationsPage() {
        VBox page = page(t("Активные потоки отдельно от недавно завершившихся.", "Active streams are shown separately from recently ended streams."));
        if (!snapshot.connected()) page.getChildren().add(errorCard(t("PipeWire недоступен", "PipeWire unavailable"), snapshot.error()));
        List<AudioNode> active = filtered(snapshot.streams(), search.getText());
        VBox activeCard = card(t("Активные потоки", "Active streams"));
        if (active.isEmpty()) activeCard.getChildren().add(label(t("Активных аудиопотоков сейчас нет.", "There are no active audio streams right now."), "muted"));
        for (AudioNode stream : active) activeCard.getChildren().add(streamRow(stream, false));
        page.getChildren().add(activeCard);
        VBox endedCard = card(t("Недавно завершённые", "Recently ended"));
        List<AudioNode> ended = filtered(snapshot.recentlyEnded(), search.getText());
        if (ended.isEmpty()) endedCard.getChildren().add(label(t("За последние 30 секунд завершившихся потоков нет.", "No streams ended in the last 30 seconds."), "muted"));
        for (AudioNode stream : ended) endedCard.getChildren().add(streamRow(stream, true));
        page.getChildren().add(endedCard);
        return page;
    }

    protected Node streamRow(AudioNode stream, boolean ended) {
        VBox card = new VBox(9); card.getStyleClass().add("node-card");
        HBox heading = new HBox(10); heading.setAlignment(Pos.CENTER_LEFT);
        VBox names = new VBox(3, label(stream.applicationName().isBlank() ? stream.description() : stream.applicationName(), "card-title"),
                label(stream.processName().isBlank() ? stream.name() : stream.processName() + " · " + stream.name(), "muted"));
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        heading.getChildren().addAll(names, spacer, label(ended ? t("Завершён", "Ended") : stream.state(), ended ? "muted" : "status-pill"));
        card.getChildren().add(heading);
        if (!ended) {
            HBox controls = new HBox(12); controls.setAlignment(Pos.CENTER_LEFT);
            Label current = label(volumeLabel(stream.volume()), "");
            Slider slider = new Slider(0, 1, Double.isFinite(stream.volume()) ? Math.min(stream.volume(), 1) : 0);
            slider.setAccessibleText(t("Громкость приложения", "Application volume"));
            slider.setDisable(!Double.isFinite(stream.volume()));
            if (!Double.isFinite(stream.volume())) slider.setTooltip(new javafx.scene.control.Tooltip(t("PipeWire не сообщил уровень этого потока.", "PipeWire did not report this stream's volume.")));
            HBox.setHgrow(slider, Priority.ALWAYS);
            PauseTransition debounce = new PauseTransition(Duration.millis(180));
            slider.valueProperty().addListener((obs, old, value) -> {
                current.setText(volumeLabel(value.doubleValue()));
                debounce.setOnFinished(e -> backend.setVolume(stream.id(), value.doubleValue()).whenComplete((v, err) -> {
                    if (err != null) Platform.runLater(() -> setNotice(rootMessage(err), true));
                })); debounce.playFromStart();
            });
            slider.setOnMouseReleased(e -> saveAppVolume(stream, slider.getValue()));
            slider.setOnKeyReleased(e -> saveAppVolume(stream, slider.getValue()));
            Button mute = actionButton(stream.muted() ? t("Включить звук", "Unmute") : t("Выключить звук", "Mute"), () -> backend.setMute(stream.id(), !stream.muted()).whenComplete((v, err) -> Platform.runLater(() -> {
                if (err != null) setNotice(rootMessage(err), true); else { saveAppMute(stream, !stream.muted()); refreshBackend(); }
            })));
            ComboBox<AudioNode> destination = new ComboBox<>(); destination.getItems().setAll(physicalSinks());
            destination.setCellFactory(list -> nodeCell()); destination.setButtonCell(nodeCell()); destination.setPromptText(t("Перенос…", "Move to…")); destination.setPrefWidth(190); destination.setAccessibleText(t("Выход для переноса потока", "Destination for stream transfer"));
            Button move = actionButton(t("Перенести", "Move"), () -> {
                AudioNode sink = destination.getValue(); if (sink == null) return;
                backend.moveStream(stream, sink).whenComplete((v, err) -> Platform.runLater(() -> {
                    if (err != null) setNotice(t("Перенос не выполнен: ", "Stream transfer failed: ") + rootMessage(err), true);
                    else refreshBackend();
                }));
            });
            move.setDisable(!backend.canMoveStreams() || routeManager.active());
            move.setTooltip(new javafx.scene.control.Tooltip(routeManager.active()
                    ? t("Сначала очистите применённые маршруты.", "Clear the applied route graph first.")
                    : backend.canMoveStreams()
                    ? t("Переносит поток на выбранный выход через pactl или прямые PipeWire-связи.", "Moves the stream using pactl or direct PipeWire links.")
                    : t("Нужны pactl или pw-link; ни одна команда не найдена.", "Either pactl or pw-link is required; neither command was found.")));
            controls.getChildren().addAll(label(t("Громкость приложения", "Application volume"), "muted"), slider, current, mute, destination, move);
            card.getChildren().add(controls);

            Map<String, Object> appConfig = store.snapshot();
            Map<String, Object> appRule = ApplicationRuleMatcher.find(maps(appConfig.get("applications")), stream);
            ComboBox<String> eqChain = new ComboBox<>();
            eqChain.getItems().add("none");
            map(map(appConfig.get("dsp")).get("route_chains")).keySet().stream().sorted()
                    .map(id -> "dsp.route_chains." + id).forEach(eqChain.getItems()::add);
            String savedChain = string(appRule == null ? null : appRule.get("filters_ref"), "none");
            if (!eqChain.getItems().contains(savedChain)) eqChain.getItems().add(savedChain);
            eqChain.setValue(savedChain);
            eqChain.setAccessibleText(t("EQ-цепочка приложения", "Application EQ chain"));
            eqChain.setPrefWidth(250);
            eqChain.setOnAction(event -> {
                String selected = eqChain.getValue();
                if (selected == null) return;
                updateConfig(config -> {
                    Map<String, Object> rule = ensureApplication(config, stream);
                    if ("none".equals(selected)) rule.remove("filters_ref");
                    else rule.put("filters_ref", selected);
                });
                setNotice(t("EQ-цепочка сохранена для правила приложения.", "Application EQ chain saved to this app rule."), false);
            });
            card.getChildren().add(new HBox(10, label(t("EQ приложения", "Application EQ"), "muted"), eqChain,
                    label(t("Цепочки создаются на экране DSP.", "Create chains on the DSP page."), "muted")));
        }
        return card;
    }
}
