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
        VBox page = page(t("Монитор, Наушники и Свободный используют один общий закреплённый Vibro-тракт. Неизвестные выходы требуют явной привязки.", "Monitor, Headphones and Free share one pinned Vibro path. Unknown outputs require explicit binding."));
        Map<String,Object> config=store.snapshot();
        List<Map<String, Object>> profiles = maps(config.get("profiles"));
        VBox list = card(t("Профили", "Profiles"));
        String active = string(config.get("active_profile"), "desk");
        List<Map<String, Object>> visibleProfiles = profiles.stream().filter(profile -> matchesSearch(string(profile.get("name"), "") + " " + string(profile.get("id"), "") + " " + string(profile.get("mode"), "") + " " + outputReferenceName(config,string(profile.get("default_output"),"system_default")), search.getText())).toList();
        if (visibleProfiles.isEmpty()) list.getChildren().add(label(t("Профили не найдены.", "No profiles found."), "muted"));
        for (Map<String, Object> profile : visibleProfiles) {
            String id = string(profile.get("id"), "");
            HBox row = new HBox(12); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("node-card");
            int graphCount=GraphRouteLibrary.routesForProfile(config,id).size();
            VBox info = new VBox(4, label(string(profile.get("name"), id), "card-title"), label(id + " · " + string(profile.get("mode"), "hybrid") + " · " + outputReferenceName(config,string(profile.get("default_output"),"system_default")) + " · " + graphCount + t(" графов"," graphs"), "muted"));
            Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
            row.getChildren().addAll(info, spacer);
            if (id.equals(active)) row.getChildren().add(label(t("Активен", "Active"), "accent"));
            else row.getChildren().add(actionButton(t("Активировать", "Activate"), () -> activateProfile(id)));
            row.getChildren().add(actionButton(t("Переименовать", "Rename"), () -> renameProfile(id)));
            row.getChildren().add(actionButton(t("Дублировать профиль и графы", "Duplicate profile and graphs"), () -> copyProfile(id)));
            if (profiles.size() > 1) row.getChildren().add(actionButton(t("Удалить", "Delete"), () -> deleteProfile(id)));
            list.getChildren().add(row);
        }
        list.getChildren().add(actionButton(t("Новый профиль", "New profile"), this::createProfile));
        if (!Boolean.TRUE.equals(config.get("octine_profiles_available")))
            list.getChildren().add(actionButton(t("Добавить профили Монитор / Наушники / Свободный…", "Add Monitor / Headphones / Free profiles…"),
                    () -> updateRouteConfig(t("Добавить общие профили Octine", "Add Octine presets"), GraphProfileLibrary::installOctineProfiles)));
        page.getChildren().add(list);
        page.getChildren().add(GraphVibroControls.create(config,this::t,
                change->updateRouteConfig(t("Изменить общий Vibro", "Change common Vibro"),change)));
        VBox presets = card(t("Режимы выхода", "Output modes"));
        presets.getChildren().addAll(label(t("Haptic · Subwoofer · Speaker · Hybrid · Multi-output · Off", "Haptic · Subwoofer · Speaker · Hybrid · Multi-output · Off"), "card-title"),
                label(t("Haptic пропускает шины с ролью effects на выходы haptic_output; Subwoofer — шину subwoofer на выход subwoofer; Speaker — маршруты на speaker. Назначьте роли в редакторах шин и устройств. Без роли специальный маршрут будет пропущен.",
                        "Haptic routes effects buses to haptic_output devices; Subwoofer routes the subwoofer bus to a subwoofer output; Speaker keeps routes to speaker outputs. Set roles in bus and device editors. Specialized routes without matching roles are skipped."), "muted"));
        ComboBox<String> mode = new ComboBox<>(); mode.getItems().addAll("haptic", "subwoofer", "speaker", "hybrid", "multi-output", "off");
        mode.setValue(activeProfile().getOrDefault("mode", "hybrid").toString()); mode.setAccessibleText(t("Режим профиля", "Profile mode"));
        mode.setOnAction(e -> updateRouteConfig(t("Изменён режим выхода профиля", "Profile output mode changed"),
                updated -> activeProfile(updated).put("mode", mode.getValue()))); presets.getChildren().add(mode);
        page.getChildren().add(presets);
        page.getChildren().add(routeHistoryCard());
        VBox importExport = card(t("Резервная копия и перенос", "Backup and transfer"));
        importExport.getChildren().addAll(label(t("ZIP включает JSON-профили, связи, маршруты и все указанные файлы измерений; перед импортом проверяются manifest и контрольные суммы.", "ZIP includes JSON profiles, links, routes, and referenced measurement files; the manifest and checksums are validated before import."), "muted"),
                actionButton(t("Экспортировать ZIP…", "Export ZIP…"), this::exportArchive),
                actionButton(t("Проверить и импортировать ZIP…", "Preview and import ZIP…"), this::importArchive),
                label(t("После подтверждения импорт заменяет config.json. Совпавшие правила могут сразу изменить громкость/mute устройств и потоков. Активный граф маршрутов обновится; если импорт включает apply_on_start, маршруты могут примениться сразу. Пропавшие или неоднозначные selectors можно сопоставить с текущим устройством либо оставить offline.", "After confirmation, import replaces config.json. Matching rules may immediately change device/stream volume or mute. An active route graph will refresh; imported apply_on_start may apply routes immediately. Missing or ambiguous selectors can be mapped to a current device or kept offline."), "warning"));
        page.getChildren().add(importExport);
        return page;
    }
}
