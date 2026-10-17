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

abstract class AppShellUiPart16 extends AppShellUiPart15 {
    protected AppShellUiPart16(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected String impulseSummary(CalibrationImpulseAnalyzer.Result result) {
        String frequency = result.dominantFrequencyHz() == null ? t("нет надёжного пика", "no reliable peak")
                : String.format(Locale.ROOT, "%.1f Hz", result.dominantFrequencyHz());
        String q = result.qualityFactor() == null ? t("н/д", "n/a") : String.format(Locale.ROOT, "%.1f", result.qualityFactor());
        String t20 = result.decayT20Ms() == null ? t("не измерено", "not measured")
                : String.format(Locale.ROOT, "%.0f ms", result.decayT20Ms());
        String t60 = result.projectedT60Ms() == null ? t("не доступно", "unavailable")
                : String.format(Locale.ROOT, "%.0f ms", result.projectedT60Ms());
        return t("Пик на %d ms · доминирующая частота %s · Q по затуханию %s · T20 %s · T60 экстраполировано %s · шум до импульса %.6f PCM%s",
                        "Peak at %d ms · dominant frequency %s · decay-derived Q %s · T20 %s · extrapolated T60 %s · pre-impulse noise %.6f PCM%s")
                .formatted(result.detectedPeakOffsetMs(), frequency, q, t20, t60, result.noiseRmsPcm(),
                        result.clipped() ? t(" · КЛИППИНГ", " · CLIPPED") : "");
    }

    protected LineChart<Number, Number> calibrationImpulseChart(CalibrationImpulseAnalyzer.Result result) {
        NumberAxis time = new NumberAxis(); time.setLabel(t("Время после пика, ms", "Time after response peak, ms"));
        NumberAxis level = new NumberAxis(-120, 0, 20); level.setLabel(t("Относительный уровень, dB", "Relative level, dB"));
        LineChart<Number, Number> chart = new LineChart<>(time, level);
        chart.setCreateSymbols(false); chart.setAnimated(false); chart.setLegendVisible(true); chart.setPrefHeight(260);
        chart.setAccessibleText(t("Огибающая и интегрированная кривая затухания импульсной записи.",
                "Envelope and integrated decay curve for the impulse recording."));
        XYChart.Series<Number, Number> envelope = new XYChart.Series<>();
        envelope.setName(t("Огибающая 10 ms", "10 ms envelope"));
        XYChart.Series<Number, Number> integrated = new XYChart.Series<>();
        integrated.setName(t("Интегрированное затухание · шумовой пол", "Integrated decay · noise floor"));
        for (CalibrationImpulseAnalyzer.DecayPoint point : result.points()) {
            envelope.getData().add(new XYChart.Data<>(point.timeMs(), point.envelopeRelativeDb()));
            integrated.getData().add(new XYChart.Data<>(point.timeMs(), point.integratedDecayDb()));
        }
        chart.getData().setAll(envelope, integrated);
        return chart;
    }

    protected void saveImpulseCalibration(ImpulseDecayPreview preview) {
        if (preview == null || preview.source() == null || preview.output() == null || calibrationImpulsePreviewSaved) return;
        String id = "impulse-" + UUID.randomUUID().toString().replace("-", "");
        Path root = store.path().getParent().toAbsolutePath().normalize();
        Path recordingRelative = Path.of("measurements", "calibration", id + ".wav");
        Path decayRelative = Path.of("measurements", "calibration", id + "-decay.csv");
        Path recordingTarget = root.resolve(recordingRelative).normalize();
        Path decayTarget = root.resolve(decayRelative).normalize();
        ImpulseDecayPreview saved = new ImpulseDecayPreview(preview.source(), preview.output(), preview.method(), preview.channel(),
                preview.sensorMount(), preview.impulseOffsetMs(), preview.responseWindowMs(), preview.result(),
                recordingRelative.toString().replace('\\', '/'), decayRelative.toString().replace('\\', '/'), id);
        calibrationImpulsePreview = saved; calibrationImpulsePreviewSaved = true;
        setNotice(t("Сохраняю исходный WAV и CSV затухания…", "Saving the source WAV and decay CSV…"), false);
        Thread.ofVirtual().name("audio-matrix-impulse-save").start(() -> {
            try {
                atomicCopyMeasurement(root, preview.source(), recordingTarget);
                atomicWriteMeasurement(root, decayTarget, CalibrationImpulseAnalyzer.toCsv(preview.result()));
                store.updateAsync(config -> {
                    Map<String, Object> output = ensureDevice(config, preview.output());
                    CalibrationImpulseAnalyzer.Result result = preview.result();
                    maps(config.get("calibrations")).add(DefaultConfigMap.map(
                            "id", id, "device", output.get("id"), "channel", null, "method", preview.method(),
                            "measurement_kind", "impulse_decay", "sensor_source", null, "sensor_source_description", null,
                            "sensor_mount", preview.sensorMount(), "measurement_file", saved.measurementFile(),
                            "response_curve_file", null, "decay_curve_file", saved.decayCurveFile(),
                            "filters_ref", null, "verified", false, "sample_rate_hz", result.sampleRate(),
                            "sensor_channel_index", preview.channel(), "impulse_offset_ms", preview.impulseOffsetMs(),
                            "response_window_ms", preview.responseWindowMs(), "impulse_peak_offset_ms", result.detectedPeakOffsetMs(),
                            "peak_pcm", result.peakPcm(), "noise_rms_pcm", result.noiseRmsPcm(), "clipped", result.clipped(),
                            "dominant_frequency_hz", result.dominantFrequencyHz(), "quality_factor", result.qualityFactor(),
                            "decay_t20_ms", result.decayT20Ms(), "decay_t60_projected_ms", result.projectedT60Ms(),
                            "analyzed_at", Instant.now().toString(),
                            "notes", t("Импульсный отклик; относительная оценка затухания, физические единицы датчика не заданы.",
                                    "Impulse response; relative decay estimate, sensor physical units unspecified.")));
                }).join();
                Platform.runLater(() -> {
                    setNotice(t("WAV и кривая затухания сохранены; фильтры не применялись.",
                            "WAV and decay curve saved; no filters were applied."), false);
                    showPage(Page.CALIBRATION);
                });
            } catch (Exception failure) {
                try { Files.deleteIfExists(recordingTarget); Files.deleteIfExists(decayTarget); } catch (IOException ignored) {}
                Platform.runLater(() -> {
                    calibrationImpulsePreview = preview; calibrationImpulsePreviewSaved = false;
                    showPage(Page.CALIBRATION);
                    setNotice(t("Импульсное измерение не сохранено: ", "Impulse measurement was not saved: ") + rootMessage(failure), true);
                });
            }
        });
    }

    protected void saveCalibrationMeasurement(MeasurementPreview preview) {
        if (preview == null || preview.source() == null || preview.output() == null || calibrationPreviewSaved) return;
        String id = "measurement-" + UUID.randomUUID().toString().replace("-", "");
        Path root = store.path().getParent().toAbsolutePath().normalize();
        Path recordingRelative = Path.of("measurements", "calibration", id + ".wav");
        Path curveRelative = Path.of("measurements", "calibration", id + "-response.csv");
        Path recordingTarget = root.resolve(recordingRelative).normalize();
        Path curveTarget = root.resolve(curveRelative).normalize();
        MeasurementPreview saved = new MeasurementPreview(preview.source(), preview.output(), preview.method(),
                preview.sensorSource(), preview.sensorSourceDescription(), preview.channel(),
                preview.startHz(), preview.endHz(), preview.steps(), preview.dwellMs(), preview.offsetMs(), preview.sensorMount(),
                preview.result(), recordingRelative.toString().replace('\\', '/'), curveRelative.toString().replace('\\', '/'), id,
                preview.temporaryDirectory());
        calibrationPreview = saved; calibrationPreviewSaved = true;
        setNotice(t("Сохраняю исходный WAV и относительную кривую…", "Saving the source WAV and relative curve…"), false);
        Thread.ofVirtual().name("audio-matrix-calibration-save").start(() -> {
            try {
                atomicCopyMeasurement(root, preview.source(), recordingTarget);
                atomicWriteMeasurement(root, curveTarget, CalibrationMeasurementAnalyzer.toCsv(preview.result()));
                store.updateAsync(config -> {
                    Map<String, Object> output = ensureDevice(config, preview.output());
                    maps(config.get("calibrations")).add(DefaultConfigMap.map("id", id, "device", output.get("id"),
                            "channel", null, "method", preview.method(), "sensor_source", preview.sensorSource(),
                            "sensor_source_description", preview.sensorSourceDescription(), "sensor_mount", preview.sensorMount(),
                            "measurement_file", saved.measurementFile(), "response_curve_file", saved.responseCurveFile(),
                            "filters_ref", null, "verified", false, "sample_rate_hz", preview.result().sampleRate(),
                            "sensor_channel_index", preview.channel(), "sweep_start_hz", preview.startHz(),
                            "sweep_end_hz", preview.endHz(), "sweep_steps", preview.steps(), "sweep_dwell_ms", preview.dwellMs(),
                            "recording_offset_ms", preview.offsetMs(), "peak_pcm", preview.result().peakPcm(),
                            "clipped", preview.result().clipped(), "analyzed_at", Instant.now().toString(),
                            "notes", t("Импортированный WAV; относительный отклик, физические единицы датчика не заданы.",
                                    "Imported WAV; relative response only, sensor physical units unspecified.")));
                }).join();
                deleteTemporaryCalibrationDirectory(preview.temporaryDirectory());
                Platform.runLater(() -> {
                    if (preview.temporaryDirectory() != null && preview.source().equals(calibrationRecordingPath)) calibrationRecordingPath = null;
                    setNotice(t("Запись и кривая сохранены; фильтры не применялись.", "Recording and curve saved; no filters were applied."), false);
                    showPage(Page.CALIBRATION);
                });
            } catch (Exception failure) {
                try { Files.deleteIfExists(recordingTarget); Files.deleteIfExists(curveTarget); } catch (IOException ignored) {}
                Platform.runLater(() -> {
                    calibrationPreview = preview; calibrationPreviewSaved = false;
                    showPage(Page.CALIBRATION);
                    setNotice(t("Измерение не сохранено: ", "Measurement was not saved: ") + rootMessage(failure), true);
                });
            }
        });
    }
}
