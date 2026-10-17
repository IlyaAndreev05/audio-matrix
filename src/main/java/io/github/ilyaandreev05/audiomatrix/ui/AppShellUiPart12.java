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

abstract class AppShellUiPart12 extends AppShellUiPart11 {
    protected AppShellUiPart12(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected void buildCalibrationResults(CalibrationPageState state) {
        if (calibrationImpulsePreview != null) {
            ImpulseDecayPreview preview = calibrationImpulsePreview;
            state.measurement.getChildren().addAll(label(impulseSummary(preview.result()), preview.result().clipped() ? "warning" : "muted"),
                    calibrationImpulseChart(preview.result()));
            if (calibrationImpulsePreviewSaved) {
                state.measurement.getChildren().add(label(t("WAV и CSV затухания сохранены в JSON-профиле.", "WAV and decay CSV are saved in the JSON profile."), "muted"));
            } else {
                Button saveImpulse = actionButton(t("Сохранить WAV и затухание в JSON-профиль", "Save WAV and decay to the JSON profile"),
                        () -> saveImpulseCalibration(preview));
                saveImpulse.setDisable(preview.output() == null);
                Button discardImpulse = actionButton(t("Отбросить анализ затухания", "Discard decay analysis"), () -> {
                    calibrationImpulsePreview = null; calibrationImpulsePreviewSaved = false; showPage(Page.CALIBRATION);
                });
                state.measurement.getChildren().add(new FlowPane(10, 8, saveImpulse, discardImpulse));
                if (preview.output() == null)
                    state.measurement.getChildren().add(label(t("Выберите выход выше, чтобы привязать измерение к профилю.",
                            "Select an output above to associate this measurement with a profile."), "muted"));
            }
        }
        if (calibrationPreview != null) {
            String quality = calibrationPreview.result().clipped()
                    ? t("Есть клиппинг входной записи; пики кривой могут быть занижены.", "The input recording is clipped; response peaks may be understated.")
                    : t("Относительный уровень нормирован к медиане точек. Глубокие провалы не преобразуются в усиление.", "Relative level is normalized to the median point. Deep nulls are not turned into boost.");
            state.measurement.getChildren().addAll(label(quality, calibrationPreview.result().clipped() ? "warning" : "muted"),
                    calibrationFilterPreviewShown && !calibrationFilterSuggestions.isEmpty()
                            ? calibrationFilterChart(calibrationPreview.result(), calibrationPreview.method(), calibrationFilterSuggestions)
                            : calibrationChart(calibrationPreview.result(), calibrationPreview.method()),
                    calibrationPointsTable(calibrationPreview.result()));
            VBox filterProposal = new VBox(8);
            filterProposal.getStyleClass().add("node-card");
            filterProposal.getChildren().addAll(label(t("Предпросмотр коррекции", "Filter correction preview"), "card-title"),
                    label(t("Только выраженные пики: максимум 3 отрицательных PEQ-среза до −3 dB. Прогноз рассчитан по относительной кривой и не заменяет повторное измерение; провалы не усиливаются.",
                                    "Only clear peaks: at most 3 subtractive PEQ cuts up to −3 dB. This prediction uses the relative curve and does not replace a repeat measurement; nulls are never boosted."), "muted"));
            if (!calibrationFilterPreviewShown) {
                Button suggest = actionButton(t("Предложить срезы и показать прогноз", "Suggest cuts and show prediction"), () -> {
                    calibrationFilterSuggestions = CalibrationFilterAdvisor.suggest(calibrationPreview.result());
                    calibrationFilterPreviewShown = true;
                    showPage(Page.CALIBRATION);
                    setNotice(calibrationFilterSuggestions.isEmpty()
                            ? t("Надёжных пиков для умеренного среза не найдено; фильтры не изменены.", "No clear peaks qualify for a moderate cut; filters were not changed.")
                            : t("Показан offline-прогноз. Фильтры ещё не сохранены и не применены.", "An offline prediction is shown. Filters have not been saved or applied."), false);
                });
                suggest.setDisable(calibrationPreview.result().clipped());
                filterProposal.getChildren().add(suggest);
                if (calibrationPreview.result().clipped())
                    filterProposal.getChildren().add(label(t("Из-за клиппинга предложения недоступны.", "Suggestions are unavailable because the recording is clipped."), "warning"));
            } else if (calibrationFilterSuggestions.isEmpty()) {
                filterProposal.getChildren().add(label(t("Выраженных локальных пиков не найдено. Автоподъём или срез не предлагается.",
                        "No clear local peaks were found. No automatic boost or cut is suggested."), "muted"));
                filterProposal.getChildren().add(actionButton(t("Скрыть прогноз", "Hide prediction"), () -> {
                    calibrationFilterPreviewShown = false; showPage(Page.CALIBRATION);
                }));
            } else {
                for (CalibrationFilterAdvisor.Suggestion suggestion : calibrationFilterSuggestions) {
                    filterProposal.getChildren().add(label(String.format(Locale.ROOT, "%.1f Hz · %+.1f dB · Q %.1f · ",
                                    suggestion.frequencyHz(), suggestion.gainDb(), suggestion.q())
                                    + t("выступание ", "peak prominence ") + String.format(Locale.ROOT, "%.1f dB", suggestion.prominenceDb()), "muted"));
                }
                Button applyCuts = actionButton(t("Сохранить и применить срезы", "Save and apply cuts"),
                        () -> applyCalibrationFilterProposal(calibrationPreview, state.hardwareChecked));
                applyCuts.setAccessibleText(t("Подтвердить и применить прогнозируемые фильтры к выбранному выходу", "Confirm and apply predicted filters to the selected output"));
                boolean canApply = calibrationPreviewSaved && calibrationPreview.calibrationId() != null
                        && calibrationPreview.output() != null && selectedSink != null
                        && calibrationPreview.output().id() == selectedSink.id() && snapshot.connected()
                        && !calibrationPreview.result().clipped() && state.hardwareChecked.isSelected();
                applyCuts.setDisable(!canApply);
                state.hardwareChecked.selectedProperty().addListener((observable, oldValue, newValue) ->
                        applyCuts.setDisable(!(calibrationPreviewSaved && calibrationPreview.calibrationId() != null
                                && calibrationPreview.output() != null && selectedSink != null
                                && calibrationPreview.output().id() == selectedSink.id() && snapshot.connected()
                                && !calibrationPreview.result().clipped() && newValue)));
                filterProposal.getChildren().addAll(applyCuts,
                        label(calibrationPreviewSaved
                                        ? t("Применение требует сохранённого WAV, совпадения с выбранным выходом и подтверждения аппаратной цепи.",
                                                "Applying requires a saved WAV, a match with the selected output, and hardware-chain acknowledgement.")
                                        : t("Сначала сохраните WAV и кривую, чтобы привязать фильтры к калибровочному профилю.",
                                                "Save the WAV and curve first so the filters can be linked to the calibration profile."), "muted"),
                        actionButton(t("Скрыть прогноз", "Hide prediction"), () -> {
                            calibrationFilterPreviewShown = false; showPage(Page.CALIBRATION);
                        }));
            }
            state.measurement.getChildren().add(filterProposal);
            if (calibrationPreviewSaved) {
                state.measurement.getChildren().add(label(t("Запись WAV и CSV кривой сохранены в папке measurements.", "WAV recording and curve CSV are saved under measurements."), "muted"));
            } else {
                Button saveMeasurement = actionButton(t("Сохранить запись и кривую в JSON-профиль", "Save recording and curve to the JSON profile"),
                        () -> saveCalibrationMeasurement(calibrationPreview));
                Button discardMeasurement = actionButton(t("Отбросить результат", "Discard result"), () -> {
                    discardTemporaryCalibrationPreview();
                    showPage(Page.CALIBRATION);
                    setNotice(t("Несохранённый результат отброшен.", "Unsaved result discarded."), false);
                });
                state.measurement.getChildren().add(new FlowPane(10, 8, saveMeasurement, discardMeasurement));
            }
        }
        if (calibrationFilterRollback != null) {
            state.measurement.getChildren().addAll(label(t("Сохранённая коррекция: ", "Saved correction: ")
                            + calibrationFilterRollback.outputDescription(), "muted"),
                    actionButton(t("Откатить последний фильтр калибровки", "Roll back the latest calibration filter"),
                            this::rollbackCalibrationFilterProposal));
        }
        List<Map<String, Object>> savedCalibrationRecords = maps(store.snapshot().get("calibrations")).stream()
                .filter(item -> item.get("measurement_file") instanceof String).toList();
        List<Map<String, Object>> savedMeasurements = savedCalibrationRecords.stream()
                .filter(item -> item.get("response_curve_file") instanceof String).toList();
        if (!savedCalibrationRecords.isEmpty()) {
            state.measurement.getChildren().add(label(t("Сохранённые измерения", "Saved measurements"), "muted"));
            for (Map<String, Object> item : savedCalibrationRecords) {
                boolean impulse = "impulse_decay".equals(item.get("measurement_kind"));
                state.measurement.getChildren().add(new HBox(8, label(calibrationMeasurementLabel(item), "muted"),
                        actionButton(impulse ? t("Показать затухание", "Show decay") : t("Показать кривую", "Show curve"),
                                () -> loadCalibrationMeasurement(item))));
            }
        }
        if (savedMeasurements.size() >= 2) {
            VBox comparison = card(t("Сравнение до/после", "Before/after comparison"));
            comparison.getChildren().add(label(t("Сравнивайте один выход, источник, метод, канал и положение датчика. Каждая кривая нормирована отдельно к своей медиане: график показывает форму, а не абсолютную вибрацию или изменение общей громкости.",
                    "Compare the same output, input source, method, channel, and sensor position. Each curve is normalized to its own median: this shows response shape, not absolute vibration or overall loudness."), "warning"));
            ComboBox<Map<String, Object>> before = calibrationMeasurementSelector(savedMeasurements); before.setAccessibleText(t("Измерение до", "Before measurement"));
            ComboBox<Map<String, Object>> after = calibrationMeasurementSelector(savedMeasurements); after.setAccessibleText(t("Измерение после", "After measurement"));
            if (calibrationComparisonPreview != null) {
                selectCalibrationMeasurement(before, calibrationComparisonPreview.beforeId(), 0);
                selectCalibrationMeasurement(after, calibrationComparisonPreview.afterId(), 1);
            } else {
                before.getSelectionModel().select(0);
                after.getSelectionModel().select(1);
            }
            Button compare = actionButton(t("Сравнить кривые", "Compare curves"), () -> compareCalibrationMeasurements(before.getValue(), after.getValue()));
            FlowPane selectors = new FlowPane(10, 8,
                    new VBox(4, label(t("До", "Before"), "muted"), before),
                    new VBox(4, label(t("После", "After"), "muted"), after), compare);
            comparison.getChildren().add(selectors);
            boolean previewAvailable = calibrationComparisonPreview != null
                    && savedMeasurements.stream().anyMatch(item -> calibrationComparisonPreview.beforeId().equals(item.get("id")))
                    && savedMeasurements.stream().anyMatch(item -> calibrationComparisonPreview.afterId().equals(item.get("id")));
            if (previewAvailable) {
                CalibrationComparisonPreview preview = calibrationComparisonPreview;
                comparison.getChildren().add(label(preview.clipped()
                                ? t("В одной из записей есть клиппинг; сравнение может быть занижено.", "One recording is clipped; the comparison may understate peaks.")
                                : preview.verified()
                                ? t("Сравнение сохранённых кривых относительного отклика.", "Comparison of saved relative response curves.")
                                : t("Кривые загружены, но измерения не подтверждены.", "Curves loaded, but measurements are unverified."),
                        preview.clipped() ? "warning" : "muted"));
                comparison.getChildren().addAll(calibrationComparisonChart(preview.comparison(), preview.beforeLabel(), preview.afterLabel()),
                        calibrationComparisonTable(preview.comparison()));
            } else if (calibrationComparisonPreview != null) {
                calibrationComparisonPreview = null;
            }
            state.measurement.getChildren().add(comparison);
        }
    }
}
