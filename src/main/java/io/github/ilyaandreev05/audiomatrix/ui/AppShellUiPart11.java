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

abstract class AppShellUiPart11 extends AppShellUiPart10 {
    protected AppShellUiPart11(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected void buildCalibrationImpulseButtons(CalibrationPageState state) {
        state.impulseOffset = field("500", t("Импульс от начала WAV, ms", "Impulse offset from WAV start, ms"));
        state.impulseWindow = field("2000", t("Окно отклика, ms", "Response window, ms"));
        state.analyzeImpulse = actionButton(t("Анализировать затухание", "Analyze impulse decay"), () -> {
            try {
                if (state.recording[0] == null) throw new IllegalArgumentException(t("Сначала выберите WAV.", "Choose a WAV file first."));
                int channel = state.sensorChannel.getValue() == null ? 0 : state.sensorChannel.getValue();
                int offsetMs = Integer.parseInt(state.impulseOffset.getText().trim());
                int windowMs = Integer.parseInt(state.impulseWindow.getText().trim());
                String method = state.sensorMethod.getValue();
                String mount = state.sensorMount.getText().trim();
                if (mount.isBlank()) throw new IllegalArgumentException(t("Укажите положение датчика.", "Enter the sensor position."));
                Path source = state.recording[0];
                AudioNode output = selectedSink;
                state.chooseRecording.setDisable(true); state.analyzeRecording.setDisable(true); state.estimateOffset.setDisable(true);
                state.analyzeImpulseRef[0].setDisable(true); state.sensorChannel.setDisable(true); state.sensorMethod.setDisable(true); state.sensorMount.setDisable(true);
                setNotice(t("Анализирую импульс и затухание офлайн; звук не воспроизводится…",
                        "Analyzing the impulse and decay offline; no audio is played…"), false);
                Thread.ofVirtual().name("audio-matrix-impulse-analysis").start(() -> {
                    try {
                        CalibrationMeasurementAnalyzer.Recording input = CalibrationMeasurementAnalyzer.readPcm16Wav(source, channel);
                        CalibrationImpulseAnalyzer.Result result = CalibrationImpulseAnalyzer.analyze(input, offsetMs, windowMs);
                        ImpulseDecayPreview preview = new ImpulseDecayPreview(source, output, method, channel, mount,
                                offsetMs, windowMs, result, null, null, null);
                        Platform.runLater(() -> {
                            calibrationImpulsePreview = preview; calibrationImpulsePreviewSaved = false;
                            state.chooseRecording.setDisable(false); state.analyzeRecording.setDisable(false); state.estimateOffset.setDisable(false);
                            state.analyzeImpulseRef[0].setDisable(false); state.sensorChannel.setDisable(false); state.sensorMethod.setDisable(false); state.sensorMount.setDisable(false);
                            showPage(Page.CALIBRATION);
                            setNotice(result.clipped() ? t("Анализ затухания рассчитан, но в выбранном окне есть клиппинг.",
                                            "Decay analysis completed, but the selected window contains clipping.")
                                    : t("Затухание рассчитано из WAV. T60 и Q — оценки, не физическая калибровка.",
                                            "Decay was analyzed from the WAV. T60 and Q are estimates, not physical calibration."), result.clipped());
                        });
                    } catch (Exception failure) {
                        Platform.runLater(() -> {
                            state.chooseRecording.setDisable(false); state.analyzeRecording.setDisable(false); state.estimateOffset.setDisable(false);
                            state.analyzeImpulseRef[0].setDisable(false); state.sensorChannel.setDisable(false); state.sensorMethod.setDisable(false); state.sensorMount.setDisable(false);
                            setNotice(t("Затухание не рассчитано: ", "Could not analyze impulse decay: ") + rootMessage(failure), true);
                        });
                    }
                });
            } catch (Exception invalid) { setNotice(invalid.getMessage(), true); }
        });
        state.analyzeImpulseRef[0] = state.analyzeImpulse;
        state.recordSweep = actionButton(t("Записать sweep с датчиком", "Record a synchronized sensor sweep"),
                () -> beginCalibrationCapture(state.sensorSource.getValue(), state.sensorMethod.getValue(), state.sensorChannel.getValue(),
                        state.sensorMount.getText(), state.sweepStart.getText(), state.sweepEnd.getText(), state.sweepSteps.getText(), state.captureOffset,
                        state.hardwareChecked, state.sensorChecked, state.chooseRecording, state.analyzeRecording, state.recordingPath, state.recordSweepRef[0], state.cancelCaptureRef[0], state.test));
        state.cancelCapture = actionButton(t("Отменить запись", "Cancel capture"), () -> {
            backend.stopCalibrationCapture();
            setNotice(t("Останавливаю запись и sweep…", "Stopping sensor capture and sweep…"), false);
        });
        state.recordSweepRef[0] = state.recordSweep;
        state.cancelCaptureRef[0] = state.cancelCapture;
        boolean captureActive = calibrationCaptureTask != null && !calibrationCaptureTask.isDone();
        Runnable updateCaptureAvailability = () -> state.recordSweep.setDisable(captureActive || selectedSink == null
                || state.sensorSource.getValue() == null || !state.hardwareChecked.isSelected() || !state.sensorChecked.isSelected());
        state.hardwareChecked.selectedProperty().addListener((observable, oldValue, newValue) -> updateCaptureAvailability.run());
        state.sensorSource.valueProperty().addListener((observable, oldValue, newValue) -> updateCaptureAvailability.run());
        state.sensorChecked.selectedProperty().addListener((observable, oldValue, newValue) -> updateCaptureAvailability.run());
        updateCaptureAvailability.run();
        state.cancelCapture.setDisable(!captureActive);
        state.test.setDisable(captureActive);
        state.chooseRecording.setDisable(captureActive);
        state.analyzeRecording.setDisable(captureActive);
        state.estimateOffset.setDisable(captureActive || state.recording[0] == null);
        state.analyzeImpulse.setDisable(captureActive || state.recording[0] == null);
        state.hardwareChecked.setDisable(captureActive);
        state.measurement.getChildren().addAll(new HBox(10, state.sensorMethod, state.sensorChannel, state.captureOffset),
                new HBox(10, state.sensorSource, state.sensorMount), new HBox(10, state.chooseRecording, state.recordingPath, state.estimateOffset, state.analyzeRecording),
                state.sensorChecked, new FlowPane(10, 8, state.recordSweep, state.cancelCapture),
                new HBox(10, state.impulseOffset, state.impulseWindow, state.analyzeImpulse));
    }
}
