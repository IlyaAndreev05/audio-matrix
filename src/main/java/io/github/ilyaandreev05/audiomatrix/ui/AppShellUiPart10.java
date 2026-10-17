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

abstract class AppShellUiPart10 extends AppShellUiPart09 {
    protected AppShellUiPart10(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected void buildCalibrationSensorFields(CalibrationPageState state) {
        state.sensorMethod = new ComboBox<>(); state.sensorMethod.getItems().setAll("microphone", "accelerometer"); state.sensorMethod.setValue("accelerometer");
        state.sensorMethod.setAccessibleText(t("Тип датчика", "Sensor type"));
        state.sensorChannel = new ComboBox<>(); state.sensorChannel.getItems().setAll(0, 1); state.sensorChannel.setValue(0);
        state.sensorChannel.setAccessibleText(t("Канал WAV, начиная с 0", "WAV channel, zero-based"));
        state.sensorSource = new ComboBox<>();
        state.sensorSource.getItems().setAll(snapshot.sources().stream().filter(node -> node.kind().equals("input")
                && node.channels() >= 1 && node.channels() <= 2).toList());
        state.sensorSource.setCellFactory(list -> nodeCell()); state.sensorSource.setButtonCell(nodeCell());
        state.sensorSource.setPromptText(t("Выбрать вход датчика", "Select sensor input"));
        state.sensorSource.setAccessibleText(t("Вход PipeWire для синхронной записи", "PipeWire input for synchronized capture"));
        state.sensorChecked = new CheckBox(t("Я проверил подключение и закрепление датчика.",
                "I checked the sensor connection and mounting."));
        state.sensorChecked.setAccessibleText(t("Подтверждение подключения датчика", "Sensor connection confirmation"));
        state.sensorSource.setOnAction(event -> state.sensorChecked.setSelected(false));
        state.captureOffset = field("0", t("Начало sweep в записи, ms", "Sweep start in recording, ms"));
        state.sensorMount = field("", t("Положение датчика", "Sensor position"));
        state.recording = new Path[]{calibrationRecordingPath};
        state.recordingPath = label(calibrationRecordingPath == null ? t("WAV не выбран.", "No WAV selected.")
                : calibrationRecordingPath.getFileName().toString(), "muted");
    }

    protected void buildCalibrationOffsetButton(CalibrationPageState state) {
        state.estimateOffset = actionButton(t("Найти начало sweep", "Estimate sweep start"), () -> {
            try {
                if (state.recording[0] == null) throw new IllegalArgumentException(t("Сначала выберите WAV.", "Choose a WAV file first."));
                int channel = state.sensorChannel.getValue() == null ? 0 : state.sensorChannel.getValue();
                double startHz = Double.parseDouble(state.sweepStart.getText().trim());
                double endHz = Double.parseDouble(state.sweepEnd.getText().trim());
                int steps = Integer.parseInt(state.sweepSteps.getText().trim());
                Path source = state.recording[0];
                state.estimateOffsetRef[0].setDisable(true); state.chooseRecordingRef[0].setDisable(true); state.analyzeButtonRef[0].setDisable(true);
                state.sensorChannel.setDisable(true); state.sweepStart.setDisable(true); state.sweepEnd.setDisable(true); state.sweepSteps.setDisable(true);
                setNotice(t("Ищу последовательность sweep в первых 10 секундах записи; звук не воспроизводится…",
                        "Searching for the stepped-sine sequence in the first 10 seconds; no audio is played…"), false);
                Thread.ofVirtual().name("audio-matrix-calibration-alignment").start(() -> {
                    try {
                        CalibrationMeasurementAnalyzer.Recording input = CalibrationMeasurementAnalyzer.readPcm16Wav(source, channel);
                        CalibrationMeasurementAnalyzer.OffsetEstimate estimate = CalibrationMeasurementAnalyzer.estimateSweepOffset(
                                input, startHz, endHz, steps, 200, 10_000);
                        Platform.runLater(() -> {
                            state.captureOffset.setText(Integer.toString(estimate.offsetMs()));
                            state.estimateOffsetRef[0].setDisable(false); state.chooseRecordingRef[0].setDisable(false); state.analyzeButtonRef[0].setDisable(false);
                            state.sensorChannel.setDisable(false); state.sweepStart.setDisable(false); state.sweepEnd.setDisable(false); state.sweepSteps.setDisable(false);
                            setNotice(t("Начало sweep примерно на %d ms (неопределённость ±%d ms, оценка %.0f%%). Проверьте/поправьте смещение и постройте кривую.",
                                            "Sweep start estimated at %d ms (uncertainty ±%d ms, fit %.0f%%). Review or adjust the offset, then plot the curve.")
                                    .formatted(estimate.offsetMs(), estimate.uncertaintyMs(), estimate.sequenceScore() * 100), false);
                        });
                    } catch (Exception failure) {
                        Platform.runLater(() -> {
                            state.estimateOffsetRef[0].setDisable(false); state.chooseRecordingRef[0].setDisable(false); state.analyzeButtonRef[0].setDisable(false);
                            state.sensorChannel.setDisable(false); state.sweepStart.setDisable(false); state.sweepEnd.setDisable(false); state.sweepSteps.setDisable(false);
                            setNotice(t("Смещение не определено: ", "Could not estimate the offset: ") + rootMessage(failure), true);
                        });
                    }
                });
            } catch (Exception invalid) { setNotice(invalid.getMessage(), true); }
        });
        state.estimateOffsetRef[0] = state.estimateOffset;
        state.estimateOffset.setAccessibleText(t("Офлайн-поиск начала тестовой частотной последовательности", "Offline search for the start of the calibration frequency sequence"));
        state.estimateOffset.setDisable(state.recording[0] == null);
    }

    protected void buildCalibrationRecordingButtons(CalibrationPageState state) {
        state.chooseRecording = actionButton(t("Выбрать WAV", "Choose WAV"), () -> {
            FileChooser chooser = new FileChooser(); chooser.setTitle(t("Выберите запись датчика", "Choose sensor recording"));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("WAV PCM", "*.wav", "*.WAV"));
            java.io.File selected = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
            if (selected != null) {
                discardTemporaryCalibrationPreview();
                state.recording[0] = selected.toPath(); calibrationRecordingPath = state.recording[0];
                calibrationPreview = null; calibrationPreviewSaved = false; calibrationComparisonPreview = null;
                calibrationFilterSuggestions = List.of(); calibrationFilterPreviewShown = false;
                calibrationImpulsePreview = null; calibrationImpulsePreviewSaved = false;
                state.recordingPath.setText(state.recording[0].getFileName().toString()); state.estimateOffset.setDisable(false); showPage(Page.CALIBRATION);
            }
        });
        state.chooseRecordingRef[0] = state.chooseRecording;
        state.analyzeRecording = actionButton(t("Построить относительный отклик", "Plot relative response"), () -> {
            try {
                if (state.recording[0] == null) throw new IllegalArgumentException(t("Сначала выберите WAV.", "Choose a WAV file first."));
                AudioNode output = selectedSink;
                if (output == null) throw new IllegalArgumentException(t("Сначала выберите выход в блоке ручной калибровки.", "Choose an output in the manual calibration section first."));
                int channel = state.sensorChannel.getValue() == null ? 0 : state.sensorChannel.getValue();
                int offset = Integer.parseInt(state.captureOffset.getText().trim());
                double startHz = Double.parseDouble(state.sweepStart.getText().trim());
                double endHz = Double.parseDouble(state.sweepEnd.getText().trim());
                int steps = Integer.parseInt(state.sweepSteps.getText().trim());
                String method = state.sensorMethod.getValue(); String mount = state.sensorMount.getText().trim();
                Path source = state.recording[0];
                state.chooseRecording.setDisable(true); state.analyzeButtonRef[0].setDisable(true);
                setNotice(t("Анализирую WAV без воспроизведения…", "Analyzing WAV without playback…"), false);
                Thread.ofVirtual().name("audio-matrix-calibration-analysis").start(() -> {
                    try {
                        CalibrationMeasurementAnalyzer.Recording input = CalibrationMeasurementAnalyzer.readPcm16Wav(source, channel);
                        CalibrationMeasurementAnalyzer.Result result = CalibrationMeasurementAnalyzer.analyze(input,
                                startHz, endHz, steps, 200, offset);
                        Path temporaryDirectory = calibrationPreview != null && source.equals(calibrationPreview.source())
                                ? calibrationPreview.temporaryDirectory() : null;
                        MeasurementPreview preview = new MeasurementPreview(source, output, method, null, null, channel, startHz, endHz,
                                steps, 200, offset, mount, result, null, null, null, temporaryDirectory);
                        Platform.runLater(() -> {
                            calibrationPreview = preview; calibrationPreviewSaved = false; calibrationComparisonPreview = null;
                            calibrationFilterSuggestions = List.of(); calibrationFilterPreviewShown = false;
                            state.chooseRecording.setDisable(false); state.analyzeButtonRef[0].setDisable(false);
                            showPage(Page.CALIBRATION);
                            setNotice(result.clipped() ? t("Кривая рассчитана, но в WAV есть цифрово обрезанные отсчёты.", "Curve calculated, but the WAV contains digitally clipped samples.")
                                    : t("Относительная кривая рассчитана. Единицы датчика неизвестны.", "Relative curve calculated. Sensor units are unknown."), result.clipped());
                        });
                    } catch (Exception failure) {
                        Platform.runLater(() -> { state.chooseRecording.setDisable(false); state.analyzeButtonRef[0].setDisable(false); setNotice(rootMessage(failure), true); });
                    }
                });
            } catch (Exception invalid) { setNotice(invalid.getMessage(), true); }
        });
        state.analyzeButtonRef[0] = state.analyzeRecording;
    }
}
