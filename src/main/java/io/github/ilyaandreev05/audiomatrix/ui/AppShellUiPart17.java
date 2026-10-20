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

abstract class AppShellUiPart17 extends AppShellUiPart16 {
    protected AppShellUiPart17(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected void loadCalibrationMeasurement(Map<String, Object> calibration) {
        try {
            if ("impulse_decay".equals(calibration.get("measurement_kind"))) {
                Path root = store.path().getParent().toAbsolutePath().normalize();
                Path recording = savedCalibrationAsset(root, string(calibration.get("measurement_file"), ""));
                Path decayCsv = savedCalibrationAsset(root, string(calibration.get("decay_curve_file"), ""));
                if (Files.size(decayCsv) > 128 * 1024 || !Files.readAllLines(decayCsv).getFirst()
                        .equals("time_ms,envelope_relative_db,integrated_decay_db"))
                    throw new IOException("Saved impulse decay curve has an unsupported format");
                int channel = (int) number(calibration.get("sensor_channel_index"), 0);
                int offsetMs = (int) number(calibration.get("impulse_offset_ms"), 500);
                int windowMs = (int) number(calibration.get("response_window_ms"), 2_000);
                CalibrationMeasurementAnalyzer.Recording input = CalibrationMeasurementAnalyzer.readPcm16Wav(recording, channel);
                CalibrationImpulseAnalyzer.Result result = CalibrationImpulseAnalyzer.analyze(input, offsetMs, windowMs);
                calibrationImpulsePreview = new ImpulseDecayPreview(recording, null,
                        string(calibration.get("method"), "accelerometer"), channel,
                        string(calibration.get("sensor_mount"), ""), offsetMs, windowMs, result,
                        string(calibration.get("measurement_file"), ""), string(calibration.get("decay_curve_file"), ""),
                        string(calibration.get("id"), ""));
                calibrationImpulsePreviewSaved = true;
                calibrationPreview = null; calibrationPreviewSaved = false; calibrationComparisonPreview = null;
                calibrationFilterSuggestions = List.of(); calibrationFilterPreviewShown = false;
                calibrationRecordingPath = recording;
                showPage(Page.CALIBRATION);
                setNotice(result.clipped() ? t("Затухание загружено; в исходной записи есть клиппинг.",
                                "Decay analysis loaded; the source recording contains clipping.")
                        : t("Затухание пересчитано из сохранённого WAV.", "Decay analysis recalculated from the saved WAV."), result.clipped());
                return;
            }
            SavedCalibrationFiles saved = readSavedCalibrationFiles(calibration);
            CalibrationMeasurementAnalyzer.Result result = saved.result();
            Map<String, Object> config = store.snapshot();
            String savedDeviceId = string(calibration.get("device"), "");
            AudioNode savedOutput = maps(config.get("devices")).stream()
                    .filter(device -> savedDeviceId.equals(device.get("id"))).findFirst()
                    .map(device -> DeviceSelectorMatcher.resolve(physicalSinks(), savedDeviceId, maps(config.get("devices"))))
                    .orElse(null);
            calibrationPreview = new MeasurementPreview(saved.recording(), savedOutput, string(calibration.get("method"), "accelerometer"),
                    string(calibration.get("sensor_source"), null), string(calibration.get("sensor_source_description"), null),
                    (int) number(calibration.get("sensor_channel_index"), 0),
                    number(calibration.get("sweep_start_hz"), result.points().getFirst().frequencyHz()),
                    number(calibration.get("sweep_end_hz"), result.points().getLast().frequencyHz()),
                    (int) number(calibration.get("sweep_steps"), result.points().size()),
                    (int) number(calibration.get("sweep_dwell_ms"), 200),
                    (int) number(calibration.get("recording_offset_ms"), 0), string(calibration.get("sensor_mount"), ""),
                    result, string(calibration.get("measurement_file"), ""), string(calibration.get("response_curve_file"), ""),
                    string(calibration.get("id"), ""), null);
            calibrationPreviewSaved = true;
            calibrationImpulsePreview = null; calibrationImpulsePreviewSaved = false;
            calibrationComparisonPreview = null;
            calibrationFilterSuggestions = List.of(); calibrationFilterPreviewShown = false;
            restoreCalibrationRollbackState(calibration);
            showPage(Page.CALIBRATION);
        } catch (Exception failure) { setNotice(t("Измерение не загружено: ", "Could not load calibration measurement: ") + rootMessage(failure), true); }
    }

    protected SavedCalibrationFiles readSavedCalibrationFiles(Map<String, Object> calibration) throws IOException {
        Path root = store.path().getParent().toAbsolutePath().normalize();
        String rawCurve = string(calibration.get("response_curve_file"), "");
        String rawRecording = string(calibration.get("measurement_file"), "");
        Path relativeCurve = Path.of(rawCurve).normalize(); Path relativeRecording = Path.of(rawRecording).normalize();
        if (rawCurve.isBlank() || rawRecording.isBlank() || relativeCurve.isAbsolute() || relativeCurve.startsWith("..")
                || relativeRecording.isAbsolute() || relativeRecording.startsWith(".."))
            throw new IOException("Calibration files must be stored inside the Audio Matrix config directory");
        Path curve = root.resolve(relativeCurve).normalize(); Path recording = root.resolve(relativeRecording).normalize();
        if (!curve.startsWith(root) || !recording.startsWith(root))
            throw new IOException("Saved recording or response curve is outside the config directory");
        checkMeasurementParents(root, curve); checkMeasurementParents(root, recording);
        if (Files.isSymbolicLink(curve) || Files.isSymbolicLink(recording)
                || !Files.isRegularFile(curve) || !Files.isRegularFile(recording))
            throw new IOException("Saved recording or response curve is missing");
        return new SavedCalibrationFiles(recording, curve, readCalibrationCurve(curve, calibration));
    }

    protected CalibrationMeasurementAnalyzer.Result readCalibrationCurve(Path path, Map<String, Object> calibration) throws IOException {
        if (Files.size(path) > 64 * 1024 || Files.isSymbolicLink(path)) throw new IOException("Response curve file exceeds the size limit or is a symbolic link");
        List<String> rows = Files.readAllLines(path);
        if (rows.size() < 3 || rows.size() > 17 || !rows.getFirst().equals("frequency_hz,amplitude_pcm,relative_db,clipped"))
            throw new IOException("Response curve CSV has an unsupported format");
        List<CalibrationMeasurementAnalyzer.Point> points = new ArrayList<>();
        try {
            for (String row : rows.subList(1, rows.size())) {
                String[] columns = row.split(",", -1);
                if (columns.length != 4) throw new IllegalArgumentException("invalid row");
                double frequency = Double.parseDouble(columns[0]); double amplitude = Double.parseDouble(columns[1]);
                double relativeDb = Double.parseDouble(columns[2]);
                if (!Double.isFinite(frequency) || frequency <= 0 || !Double.isFinite(amplitude) || amplitude < 0 || !Double.isFinite(relativeDb))
                    throw new IllegalArgumentException("invalid numeric value");
                points.add(new CalibrationMeasurementAnalyzer.Point(frequency, amplitude, relativeDb, Boolean.parseBoolean(columns[3])));
            }
            for (int i = 1; i < points.size(); i++) if (points.get(i).frequencyHz() <= points.get(i - 1).frequencyHz())
                throw new IllegalArgumentException("frequencies are not increasing");
        } catch (RuntimeException invalid) { throw new IOException("Response curve CSV contains invalid values", invalid); }
        return new CalibrationMeasurementAnalyzer.Result((int) number(calibration.get("sample_rate_hz"), 48_000),
                (int) number(calibration.get("sensor_channel_index"), 0), number(calibration.get("peak_pcm"), 0),
                Boolean.TRUE.equals(calibration.get("clipped")), 0, points);
    }

    protected String meterText() {
        return meterText(backend.outputLevel());
    }

    protected String inputMeterText() {
        return inputMeterEnabled ? meterText(backend.inputLevel()) : t("Входной meter выключен", "Input meter is off");
    }

    protected String meterText(io.github.ilyaandreev05.audiomatrix.audio.PipeWireLevelMonitor.Reading reading) {
        if (!reading.available()) return reading.message();
        String value = t("Пик ", "Peak ") + String.format(Locale.ROOT, "%.1f dBFS", reading.peakDbfs())
                + " · RMS " + String.format(Locale.ROOT, "%.1f dBFS", reading.rmsDbfs());
        return reading.clipped() ? value + " · " + t("цифровой клиппинг", "digital clipping") : value;
    }

    protected void refreshMeterLabel() {
        if (outputMeterValue != null) outputMeterValue.setText(meterText());
        if (inputMeterValue != null) inputMeterValue.setText(inputMeterText());
    }

    protected VBox profilesPage() {
        VBox page = page(t("В Audio Matrix используется один профиль «Основной». Все источники, маршруты и уровни редактируются в едином графе.",
                "Audio Matrix uses one profile, Main. All sources, routes, and levels are edited in one graph."));
        Map<String,Object> config = store.snapshot();
        VBox main = card(t("Основной", "Main"));
        main.getChildren().addAll(label(t("Единый граф маршрутов", "Unified route graph"), "card-title"),
                label(t("Монитор, вибродинамик, приложения и звуки событий управляются независимо в редакторе графа.",
                        "Monitor, haptic output, applications, and event sounds are controlled independently in the graph editor."), "muted"),
                actionButton(t("Открыть редактор маршрутов", "Open route editor"), () -> showPage(Page.GRAPH)));
        page.getChildren().add(main);
        page.getChildren().add(routeHistoryCard());
        VBox importExport = card(t("Резервная копия и перенос", "Backup and transfer"));
        String archive = String.valueOf(config.getOrDefault("migration_archive", "")).isBlank()
                ? t("Старые профили остаются в резервной копии перед миграцией.", "Previous profiles remain in the pre-migration backup.")
                : t("Предыдущая конфигурация и профили сохранены в архиве миграции.", "The previous configuration and profiles are preserved in the migration archive.");
        importExport.getChildren().addAll(label(t("ZIP включает граф маршрутов и указанные файлы измерений; перед импортом проверяются manifest и контрольные суммы.", "ZIP includes the route graph and referenced measurement files; the manifest and checksums are validated before import."), "muted"),
                label(archive, "muted"),
                actionButton(t("Экспортировать ZIP…", "Export ZIP…"), this::exportArchive),
                actionButton(t("Проверить и импортировать ZIP…", "Preview and import ZIP…"), this::importArchive),
                label(t("После подтверждения импорт заменяет config.json. Совпавшие правила могут сразу изменить громкость/mute устройств и потоков. Активный граф маршрутов обновится; если импорт включает apply_on_start, маршруты могут примениться сразу. Пропавшие или неоднозначные selectors можно сопоставить с текущим устройством либо оставить offline.", "After confirmation, import replaces config.json. Matching rules may immediately change device/stream volume or mute. An active route graph will refresh; imported apply_on_start may apply routes immediately. Missing or ambiguous selectors can be mapped to a current device or kept offline."), "warning"));
        page.getChildren().add(importExport);
        return page;
    }
}
