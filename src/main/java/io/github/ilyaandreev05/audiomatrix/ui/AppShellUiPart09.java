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

abstract class AppShellUiPart09 extends AppShellUiPart08 {
    protected AppShellUiPart09(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected Node limiterControl(String title, String key, double minimum, double maximum, double initial, String unit) {
        Slider slider = new Slider(minimum, maximum, Math.max(minimum, Math.min(maximum, initial))); slider.setAccessibleText(title);
        slider.setShowTickMarks(true);
        slider.setMajorTickUnit("release_ms".equals(key) ? 50 : ("lookahead_ms".equals(key) ? 1 : 3));
        slider.setBlockIncrement("release_ms".equals(key) ? 5 : 0.1);
        Label value = label("", "muted"); value.setMinWidth(64);
        Runnable refreshValue = () -> value.setText(("release_ms".equals(key)
                ? Long.toString(Math.round(slider.getValue())) : String.format(Locale.ROOT, "%.1f", slider.getValue())) + " " + unit);
        Runnable save = () -> updateLimiter(key, "release_ms".equals(key)
                ? (double) Math.round(slider.getValue()) : Math.round(slider.getValue() * 10.0) / 10.0);
        slider.valueProperty().addListener((observable, oldValue, newValue) -> refreshValue.run());
        slider.setOnMouseReleased(event -> save.run());
        slider.setOnKeyReleased(event -> save.run());
        refreshValue.run();
        HBox row = new HBox(10, label(title, "muted"), slider, value);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slider, Priority.ALWAYS);
        return row;
    }

    protected VBox calibrationPage() {
        CalibrationPageState state = new CalibrationPageState();
        state.page = page(t("Ручная проверка тоном, коротким импульсом и sweep. Измерительная калибровка требует реального датчика.", "Manual tone, short impulse, and sweep checks. Measurement calibration requires a real sensor."));
        buildCalibrationManualCard(state);
        buildCalibrationObservationCard(state);
        buildCalibrationMeasurementCard(state);
        state.page.getChildren().addAll(state.test, state.observations, state.measurement);
        return state.page;
    }

    protected void buildCalibrationManualCard(CalibrationPageState state) {
        state.test = card(t("Ручная калибровка", "Manual calibration"));
        state.test.getChildren().add(label(t("Сигналы идут только на выбранный sink и требуют проверки аппаратной цепи и отдельного подтверждения. Импульс ограничен 10 ms, 100 Hz, peak 0.005 PCM и уровнем pw-play 0.03; усилитель может добавить неизвестное усиление.", "Signals go only to the selected sink and require a hardware-chain check plus a separate confirmation. The impulse is capped at 10 ms, 100 Hz, 0.005 PCM peak, and pw-play level 0.03; the amplifier may add unknown gain."), "warning"));
        state.hardwareChecked = new CheckBox(t("Я проверил выбранный выход, усилитель, питание и нагрузку.",
                "I checked the selected output, amplifier, power supply, and load."));
        state.hardwareChecked.setAccessibleText(t("Подтверждение проверки аппаратной цепи", "Hardware path check confirmation"));
        state.outputs = new ComboBox<>(); state.outputs.getItems().setAll(physicalSinks()); state.outputs.setCellFactory(list -> nodeCell()); state.outputs.setButtonCell(nodeCell()); state.outputs.setPromptText(t("Выбрать выход", "Select output")); state.outputs.setAccessibleText(t("Выход для проверки", "Output under test"));
        if (selectedSink != null) state.outputs.getSelectionModel().select(selectedSink);
        state.outputs.setOnAction(e -> { selectedSink = state.outputs.getValue(); state.hardwareChecked.setSelected(false); });
        state.frequency = field("80", t("Частота (Hz)", "Frequency (Hz)"));
        state.duration = field("500", t("Длительность (ms)", "Duration (ms)"));
        state.sweepStart = field("30", t("Начало sweep (Hz)", "Sweep start (Hz)"));
        state.sweepEnd = field("300", t("Конец sweep (Hz)", "Sweep end (Hz)"));
        state.sweepSteps = field("8", t("Шаги (2–16)", "Steps (2–16)"));
        state.observationFrequency = field("80", t("Проблемная частота (Hz)", "Problem frequency (Hz)"));
        state.observationNote = field("", t("Что вы слышите или чувствуете?", "What do you hear or feel?"));
        VBox sweepStartField = new VBox(4, label(t("Начало (Hz)", "Start (Hz)"), "muted"), state.sweepStart);
        VBox sweepEndField = new VBox(4, label(t("Конец (Hz)", "End (Hz)"), "muted"), state.sweepEnd);
        VBox sweepStepsField = new VBox(4, label(t("Шаги (2–16)", "Steps (2–16)"), "muted"), state.sweepSteps);
        state.observationFrequencyField = new VBox(4, label(t("Частота (Hz)", "Frequency (Hz)"), "muted"), state.observationFrequency);
        state.observationNoteField = new VBox(4, label(t("Субъективное наблюдение", "Listening observation"), "muted"), state.observationNote);
        state.phase = new ComboBox<>(); state.phase.getItems().setAll("before", "after"); state.phase.setValue("before");
        state.phase.setAccessibleText(t("Этап сравнения", "Comparison stage"));
        Label frequencyLabel = label(t("Частота, Гц", "Frequency, Hz"), ""); frequencyLabel.setLabelFor(state.frequency);
        Label durationLabel = label(t("Длительность, мс", "Duration, ms"), ""); durationLabel.setLabelFor(state.duration);
        Button[] manualImpulseRef = new Button[1];
        Button manualImpulse = actionButton(t("Короткий импульс · 10 ms", "Short impulse · 10 ms"), () -> {
            try {
                if (selectedSink == null) throw new IllegalStateException(t("Сначала выберите выход.", "Choose an output first."));
                if (!state.hardwareChecked.isSelected()) throw new IllegalStateException(t("Подтвердите проверку выхода, усилителя и нагрузки.",
                        "Confirm that the output, amplifier, and load have been checked."));
                String signal = t("Импульс 100 Hz, 10 ms · PCM peak ≤ 0.005 · pw-play 0.03",
                        "Impulse 100 Hz, 10 ms · PCM peak ≤ 0.005 · pw-play 0.03");
                if (!confirmCalibrationOutput(selectedSink, signal)) return;
                AudioNode output = selectedSink;
                manualImpulseRef[0].setDisable(true);
                backend.playManualImpulse(output).whenComplete((v, error) -> Platform.runLater(() -> {
                    manualImpulseRef[0].setDisable(!state.hardwareChecked.isSelected());
                    setNotice(error == null ? t("Импульс завершён. Это слуховая проверка, не измерение.", "Impulse finished. This is a listening check, not a measurement.")
                            : t("Импульс не воспроизведён: ", "Impulse was not played: ") + rootMessage(error), error != null);
                }));
            } catch (Exception e) { setNotice(e.getMessage(), true); }
        });
        manualImpulseRef[0] = manualImpulse;
        manualImpulse.setAccessibleText(t("Воспроизвести короткий ограниченный импульс на выбранном выходе", "Play a short, bounded impulse on the selected output"));
        Button play = actionButton(t("Воспроизвести тестовый тон", "Play test tone"), () -> {
            try {
                if (selectedSink == null) throw new IllegalStateException(t("Сначала выберите выход.", "Choose an output first."));
                if (!state.hardwareChecked.isSelected()) throw new IllegalStateException(t("Подтвердите проверку выхода, усилителя и нагрузки.",
                        "Confirm that the output, amplifier, and load have been checked."));
                double hz = Double.parseDouble(state.frequency.getText()); int ms = Integer.parseInt(state.duration.getText());
                if (!confirmCalibrationOutput(selectedSink, t("Тон %.0f Hz, %d ms", "Tone %.0f Hz, %d ms").formatted(hz, ms))) return;
                backend.playTestTone(selectedSink, hz, ms).whenComplete((v, error) -> Platform.runLater(() -> setNotice(error == null ? t("Тестовый тон завершён. Это не измерение.", "Test tone finished. This is not a measurement.") : t("Тон не воспроизведён: ", "Tone was not played: ") + rootMessage(error), error != null)));
            } catch (Exception e) { setNotice(e.getMessage(), true); }
        });
        Button sweep = actionButton(t("Ступенчатый sweep · до 3.2 s", "Stepped sweep · up to 3.2 s"), () -> {
            try {
                if (selectedSink == null) throw new IllegalStateException(t("Сначала выберите выход.", "Choose an output first."));
                if (!state.hardwareChecked.isSelected()) throw new IllegalStateException(t("Подтвердите проверку выхода, усилителя и нагрузки.",
                        "Confirm that the output, amplifier, and load have been checked."));
                double startHz = Double.parseDouble(state.sweepStart.getText().trim());
                double endHz = Double.parseDouble(state.sweepEnd.getText().trim());
                int steps = Integer.parseInt(state.sweepSteps.getText().trim());
                if (!confirmCalibrationOutput(selectedSink, t("Sweep %.0f–%.0f Hz, %d шагов", "Sweep %.0f–%.0f Hz, %d steps").formatted(startHz, endHz, steps))) return;
                backend.playSteppedSweep(selectedSink, startHz, endHz, steps).whenComplete((v, error) -> Platform.runLater(() ->
                        setNotice(error == null ? t("Sweep завершён. Это слуховая проверка, не измерение.", "Sweep finished. This is a listening check, not a measurement.")
                                : t("Sweep не воспроизведён: ", "Sweep was not played: ") + rootMessage(error), error != null)));
            } catch (Exception e) { setNotice(e.getMessage(), true); }
        });
        Runnable updateManualTestAvailability = () -> {
            manualImpulse.setDisable(!state.hardwareChecked.isSelected());
            play.setDisable(!state.hardwareChecked.isSelected());
            sweep.setDisable(!state.hardwareChecked.isSelected());
        };
        state.hardwareChecked.selectedProperty().addListener((observable, oldValue, newValue) -> updateManualTestAvailability.run());
        updateManualTestAvailability.run();
        state.test.getChildren().addAll(state.outputs, state.hardwareChecked, manualImpulse,
                label(t("Максимальные параметры импульса: 10 ms · 100 Hz · 0.005 PCM · pw-play 0.03", "Impulse caps: 10 ms · 100 Hz · 0.005 PCM · pw-play 0.03"), "muted"),
                new HBox(12, frequencyLabel, state.frequency, durationLabel, state.duration), play,
                label(t("Ограниченный ступенчатый sweep · 20–1000 Hz, максимум 16 × 200 ms", "Bounded stepped sweep · 20–1000 Hz, maximum 16 × 200 ms"), "muted"),
                new HBox(12, sweepStartField, sweepEndField, sweepStepsField), sweep);
    }

    protected void buildCalibrationObservationCard(CalibrationPageState state) {
        state.observations = card(t("Ручные заметки · сравнение до/после", "Manual notes · before/after comparison"));
        state.observations.getChildren().add(label(t("Это субъективные заметки, не показания датчика и не измеренная АЧХ.", "These are subjective notes, not sensor readings or a measured frequency response."), "muted"));
        Button saveObservation = actionButton(t("Сохранить наблюдение", "Save observation"), () -> {
            try {
                if (selectedSink == null) throw new IllegalStateException(t("Сначала выберите выход.", "Choose an output first."));
                double hz = Double.parseDouble(state.observationFrequency.getText().trim());
                String note = state.observationNote.getText().trim();
                if (!Double.isFinite(hz) || hz < 20 || hz > 1000) throw new IllegalArgumentException(t("Частота должна быть от 20 до 1000 Hz.", "Frequency must be between 20 and 1000 Hz."));
                if (note.isBlank()) throw new IllegalArgumentException(t("Добавьте короткое субъективное наблюдение.", "Enter a short listening observation."));
                updateConfig(config -> {
                    Map<String, Object> device = ensureDevice(config, selectedSink);
                    maps(config.get("calibrations")).add(DefaultConfigMap.map("id", "manual-" + UUID.randomUUID().toString().substring(0, 8),
                            "device", device.get("id"), "channel", null, "method", "manual", "measurement_file", null,
                            "filters_ref", null, "verified", false, "frequency_hz", hz, "phase", state.phase.getValue(),
                            "observed_at", Instant.now().toString(), "notes", note));
                });
                state.observationNote.clear();
            } catch (Exception invalid) { setNotice(invalid.getMessage(), true); }
        });
        state.observations.getChildren().add(new HBox(10, state.observationFrequencyField, state.observationNoteField, state.phase, saveObservation));
        List<Map<String, Object>> savedObservations = maps(store.snapshot().get("calibrations")).stream()
                .filter(item -> "manual".equals(item.get("method")) && item.get("frequency_hz") instanceof Number).toList();
        if (savedObservations.isEmpty()) state.observations.getChildren().add(label(t("Пока нет сохранённых заметок.", "No saved notes yet."), "muted"));
        for (Map<String, Object> item : savedObservations) {
            String id = string(item.get("id"), "");
            String detail = string(item.get("phase"), "before") + " · " + number(item.get("frequency_hz"), 0) + " Hz · " + string(item.get("notes"), "");
            HBox noteRow = new HBox(8); noteRow.setAlignment(Pos.CENTER_LEFT);
            Label noteLabel = label(detail, "muted"); HBox.setHgrow(noteLabel, Priority.ALWAYS);
            noteRow.getChildren().addAll(noteLabel, actionButton(t("Удалить", "Delete"), () -> deleteCalibrationNote(id)));
            state.observations.getChildren().add(noteRow);
        }
    }

    protected void buildCalibrationMeasurementCard(CalibrationPageState state) {
        state.measurement = card(t("Анализ записи датчика", "Analyze a sensor recording"));
        state.measurement.getChildren().add(label(t("Импортированный WAV анализируется офлайн. Синхронная запись ниже открывает только выбранный источник и воспроизводит короткий sweep на выбранном выходе после отдельного подтверждения. Результат показывает относительный уровень, не SPL и не ускорение в физических единицах.",
                "Imported WAV files are analyzed offline. Synchronized capture below opens only the selected input and plays a short sweep to the selected output after a separate confirmation. Results show relative level, not SPL or acceleration in physical units."), "warning"));
        buildCalibrationSensorFields(state);
        buildCalibrationOffsetButton(state);
        buildCalibrationRecordingButtons(state);
        buildCalibrationImpulseButtons(state);
        state.measurement.getChildren().add(label(t("Для анализа импульса оставьте не менее 20 ms предзаписи; смещение и окно указываются относительно начала WAV. Кривая затухания и T20 строятся по энергии сигнала, T60 экстраполируется, Q выводится из T60 и доминирующей частоты. Все результаты остаются непроверенными.",
                        "Keep at least 20 ms of pre-roll for impulse analysis; offset and window are relative to the WAV start. The decay curve and T20 use signal energy, T60 is extrapolated, and Q is derived from T60 and the dominant frequency. Results remain unverified."), "muted"));
        buildCalibrationResults(state);
    }
}
