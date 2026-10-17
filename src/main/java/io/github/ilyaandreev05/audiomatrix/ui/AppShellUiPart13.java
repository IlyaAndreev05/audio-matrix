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

abstract class AppShellUiPart13 extends AppShellUiPart12 {
    protected AppShellUiPart13(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected void beginCalibrationCapture(AudioNode source, String method, Integer channelValue, String mountText,
                                        String startText, String endText, String stepsText, TextField captureOffset,
                                        CheckBox hardwareChecked, CheckBox sensorChecked, Button chooseRecording, Button analyzeRecording,
                                        Label recordingPath, Button recordButton, Button cancelButton, VBox testCard) {
        try {
            if (!hardwareChecked.isSelected()) throw new IllegalStateException(t("Сначала подтвердите проверку аппаратной цепи.",
                    "Confirm the hardware-path check first."));
            if (!sensorChecked.isSelected()) throw new IllegalStateException(t("Подтвердите подключение и закрепление датчика.",
                    "Confirm the sensor connection and mounting first."));
            if (source == null) throw new IllegalStateException(t("Выберите вход микрофона или акселерометра.",
                    "Choose a microphone or accelerometer input."));
            if (selectedSink == null) throw new IllegalStateException(t("Сначала выберите выход в блоке ручной калибровки.",
                    "Choose an output in the manual calibration section first."));
            if (string(mountText, "").isBlank()) throw new IllegalArgumentException(t("Укажите положение датчика.", "Enter the sensor position."));
            if (!snapshot.sources().stream().anyMatch(item -> item.id() == source.id())
                    || !physicalSinks().stream().anyMatch(item -> item.id() == selectedSink.id()))
                throw new IllegalStateException(t("Источник или выход уже исчез. Обновите состояние и выберите устройства снова.",
                        "The input or output is no longer available. Refresh and select the devices again."));
            int channel = channelValue == null ? 0 : channelValue;
            if (channel < 0 || channel >= source.channels()) throw new IllegalArgumentException(t("Выбранного канала нет во входном устройстве.",
                    "The selected channel is not available on this input."));
            double startHz = Double.parseDouble(startText.trim());
            double endHz = Double.parseDouble(endText.trim());
            int steps = Integer.parseInt(stepsText.trim());
            if (!Double.isFinite(startHz) || !Double.isFinite(endHz) || startHz < 20 || endHz > 1000 || endHz <= startHz
                    || steps < 2 || steps > 16)
                throw new IllegalArgumentException(t("Sweep: диапазон 20–1000 Hz, шагов 2–16.", "Sweep must be 20–1000 Hz with 2–16 steps."));
            if (calibrationCaptureTask != null && !calibrationCaptureTask.isDone())
                throw new IllegalStateException(t("Запись уже выполняется.", "A capture is already running."));

            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
            confirmation.setTitle(t("Подтвердите запись sweep", "Confirm sensor sweep"));
            confirmation.setHeaderText(t("Будет воспроизведён короткий тестовый sweep", "A short calibration sweep will play"));
            confirmation.setContentText(t("Вход: %s\nВыход: %s\nСигнал: PCM не выше 0.025, уровень pw-play 0.10; громкость устройства не меняется. Усилитель может дополнительно усиливать сигнал. Убедитесь, что схема и нагрузка проверены и физическая громкость минимальна. Исходный WAV останется временным, пока вы отдельно не сохраните измерение.",
                            "Input: %s\nOutput: %s\nSignal: PCM amplitude at most 0.025, pw-play level 0.10; device volume is not changed. The amplifier may add gain. Verify the wiring and load, and keep physical output at its minimum. The source WAV stays temporary until you separately save the measurement.")
                    .formatted(source.description(), selectedSink.description()));
            if (getScene() != null && getScene().getWindow() != null) confirmation.initOwner(getScene().getWindow());
            confirmation.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            if (confirmation.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return;

            AudioNode output = selectedSink;
            String sensorMount = mountText.trim();
            recordButton.setDisable(true); cancelButton.setDisable(false); testCard.setDisable(true);
            chooseRecording.setDisable(true); analyzeRecording.setDisable(true);
            hardwareChecked.setDisable(true);
            setNotice(t("Записываю датчик, затем воспроизведу sweep на выбранном выходе…",
                    "Recording the sensor, then playing the sweep to the selected output…"), false);
            CompletableFuture<MeasurementPreview> task = backend.captureSteppedSweep(source, output, startHz, endHz, steps)
                    .thenApplyAsync(capture -> {
                        try {
                            CalibrationMeasurementAnalyzer.Recording recording = CalibrationMeasurementAnalyzer.readPcm16Wav(capture.recording(), channel);
                            CalibrationMeasurementAnalyzer.Result result = CalibrationMeasurementAnalyzer.analyze(recording,
                                    startHz, endHz, steps, 200, capture.sweepOffsetMs());
                            return new MeasurementPreview(capture.recording(), output, method, source.name(), source.description(),
                                    channel, startHz, endHz, steps, 200,
                                    capture.sweepOffsetMs(), sensorMount, result, null, null, null, capture.temporaryDirectory());
                        } catch (Exception failure) {
                            deleteTemporaryCalibrationDirectory(capture.temporaryDirectory());
                            throw new java.util.concurrent.CompletionException(failure);
                        }
                    }, calibrationIo);
            calibrationCaptureTask = task;
            task.whenComplete((preview, error) -> Platform.runLater(() -> {
                if (calibrationCaptureTask == task) calibrationCaptureTask = null;
                if (closing) return;
                recordButton.setDisable(false); cancelButton.setDisable(true); testCard.setDisable(false);
                chooseRecording.setDisable(false); analyzeRecording.setDisable(false); hardwareChecked.setDisable(false);
                if (error != null) {
                    setNotice(t("Запись не завершена: ", "Capture did not complete: ") + rootMessage(error), true);
                    return;
                }
                calibrationPreview = preview; calibrationPreviewSaved = false; calibrationComparisonPreview = null;
                calibrationFilterSuggestions = List.of(); calibrationFilterPreviewShown = false;
                calibrationRecordingPath = preview.source();
                captureOffset.setText(Integer.toString(preview.offsetMs()));
                recordingPath.setText(preview.source().getFileName().toString());
                if (currentPage == Page.CALIBRATION) showPage(Page.CALIBRATION);
                setNotice(preview.result().clipped()
                                ? t("Кривая рассчитана, но в записи есть клиппинг.", "Curve calculated, but the recording is clipped.")
                                : t("Запись проанализирована; кривая не подтверждена, фильтры не применялись.",
                                        "Recording analyzed; the curve is unverified and no filters were applied."),
                        preview.result().clipped());
            }));
        } catch (Exception invalid) {
            setNotice(rootMessage(invalid), true);
        }
    }

    protected boolean confirmCalibrationOutput(AudioNode sink, String signalDescription) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle(t("Подтвердите тестовый сигнал", "Confirm test signal"));
        confirmation.setHeaderText(t("На выбранный выход будет подан тестовый сигнал", "A test signal will be sent to the selected output"));
        confirmation.setContentText(t("Выход: %s\n%s\nГромкость устройства не меняется. Усилитель может дополнительно усиливать сигнал. Убедитесь, что нагрузка и питание проверены, а физический уровень минимален.",
                        "Output: %s\n%s\nDevice volume is not changed. The amplifier may add gain. Verify the load and power supply, and keep physical output at its minimum.")
                .formatted(sink.description(), signalDescription));
        if (getScene() != null && getScene().getWindow() != null) confirmation.initOwner(getScene().getWindow());
        confirmation.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        return confirmation.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }
}
