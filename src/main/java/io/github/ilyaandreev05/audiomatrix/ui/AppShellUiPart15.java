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

abstract class AppShellUiPart15 extends AppShellUiPart14 {
    protected AppShellUiPart15(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected void deleteCalibrationNote(String id) {
        updateConfig(config -> maps(config.get("calibrations")).removeIf(item -> id.equals(item.get("id"))));
    }

    protected ComboBox<Map<String, Object>> calibrationMeasurementSelector(List<Map<String, Object>> measurements) {
        ComboBox<Map<String, Object>> selector = new ComboBox<>(FXCollections.observableArrayList(measurements));
        selector.setConverter(new StringConverter<>() {
            @Override public String toString(Map<String, Object> item) { return item == null ? "" : calibrationMeasurementLabel(item); }
            @Override public Map<String, Object> fromString(String value) { return null; }
        });
        selector.setAccessibleText(t("Сохранённая кривая", "Saved response curve"));
        selector.setPrefWidth(360);
        return selector;
    }

    protected void selectCalibrationMeasurement(ComboBox<Map<String, Object>> selector, String id, int fallbackIndex) {
        for (Map<String, Object> item : selector.getItems()) if (id.equals(item.get("id"))) {
            selector.getSelectionModel().select(item);
            return;
        }
        if (fallbackIndex >= 0 && fallbackIndex < selector.getItems().size()) selector.getSelectionModel().select(fallbackIndex);
    }

    protected String calibrationMeasurementLabel(Map<String, Object> item) {
        String method = switch (string(item.get("method"), "")) {
            case "microphone" -> t("Микрофон", "Microphone");
            case "accelerometer" -> t("Акселерометр", "Accelerometer");
            default -> string(item.get("method"), t("Датчик", "Sensor"));
        };
        String sensorSource = string(item.get("sensor_source_description"), "");
        String mount = string(item.get("sensor_mount"), t("положение не указано", "position unspecified"));
        String date = string(item.get("analyzed_at"), "");
        if (date.length() > 16) date = date.substring(0, 16).replace('T', ' ');
        String kind = "impulse_decay".equals(item.get("measurement_kind")) ? t(" · импульс / затухание", " · impulse / decay") : "";
        return method + kind + (sensorSource.isBlank() ? "" : " · " + sensorSource) + " · " + mount + (date.isBlank() ? "" : " · " + date);
    }

    protected void compareCalibrationMeasurements(Map<String, Object> before, Map<String, Object> after) {
        try {
            if (before == null || after == null) throw new IllegalArgumentException(t("Выберите обе кривые.", "Choose both response curves."));
            if (Objects.equals(before.get("id"), after.get("id"))) throw new IllegalArgumentException(t("Выберите два разных измерения.", "Choose two different measurements."));
            for (String field : List.of("device", "method", "sensor_source", "sensor_channel_index", "sensor_mount")) {
                if (!Objects.equals(before.get(field), after.get(field)))
                    throw new IllegalArgumentException(t("Выбранные кривые должны совпадать по выходу, методу, каналу и положению датчика.",
                            "The curves must use the same output, sensor source, method, channel, and sensor position."));
            }
            SavedCalibrationFiles beforeFiles = readSavedCalibrationFiles(before);
            SavedCalibrationFiles afterFiles = readSavedCalibrationFiles(after);
            CalibrationMeasurementAnalyzer.Comparison curves = CalibrationMeasurementAnalyzer.compare(beforeFiles.result(), afterFiles.result());
            calibrationComparisonPreview = new CalibrationComparisonPreview(string(before.get("id"), ""), string(after.get("id"), ""),
                    t("До", "Before") + " · " + calibrationMeasurementLabel(before),
                    t("После", "After") + " · " + calibrationMeasurementLabel(after), curves,
                    beforeFiles.result().clipped() || afterFiles.result().clipped(),
                    Boolean.TRUE.equals(before.get("verified")) && Boolean.TRUE.equals(after.get("verified")));
            calibrationPreview = null;
            calibrationPreviewSaved = false;
            calibrationFilterSuggestions = List.of(); calibrationFilterPreviewShown = false;
            showPage(Page.CALIBRATION);
        } catch (Exception failure) {
            setNotice(t("Кривые не сравнены: ", "Could not compare curves: ") + rootMessage(failure), true);
        }
    }

    protected LineChart<Number, Number> calibrationComparisonChart(CalibrationMeasurementAnalyzer.Comparison comparison,
                                                                  String beforeName, String afterName) {
        double lower = Math.log10(comparison.points().getFirst().frequencyHz());
        double upper = Math.log10(comparison.points().getLast().frequencyHz());
        NumberAxis frequency = new NumberAxis(lower, upper, Math.max(0.05, (upper - lower) / 4));
        frequency.setLabel(t("Частота, Hz · логарифмическая шкала", "Frequency, Hz · logarithmic scale"));
        frequency.setTickLabelFormatter(new StringConverter<>() {
            @Override public String toString(Number value) { return String.format(Locale.ROOT, "%.0f", Math.pow(10, value.doubleValue())); }
            @Override public Number fromString(String value) { return Math.log10(Double.parseDouble(value)); }
        });
        NumberAxis level = new NumberAxis();
        level.setLabel(t("Относительный уровень, dB", "Relative level, dB"));
        LineChart<Number, Number> chart = new LineChart<>(frequency, level);
        chart.setCreateSymbols(true); chart.setAnimated(false); chart.setLegendVisible(true); chart.setPrefHeight(280);
        chart.setAccessibleText(t("Наложение кривых до и после. Таблица точных значений и разницы находится ниже.",
                "Before and after response overlay. A table with exact values and delta follows."));
        XYChart.Series<Number, Number> before = new XYChart.Series<>(); before.setName(beforeName);
        XYChart.Series<Number, Number> after = new XYChart.Series<>(); after.setName(afterName);
        for (CalibrationMeasurementAnalyzer.ComparisonPoint point : comparison.points()) {
            double x = Math.log10(point.frequencyHz());
            XYChart.Data<Number, Number> beforePoint = new XYChart.Data<>(x, point.beforeRelativeDb());
            beforePoint.setNode(new Circle(4));
            Tooltip.install(beforePoint.getNode(), new Tooltip(String.format(Locale.ROOT, "%.1f Hz · %.2f dB", point.frequencyHz(), point.beforeRelativeDb())));
            before.getData().add(beforePoint);
            XYChart.Data<Number, Number> afterPoint = new XYChart.Data<>(x, point.afterRelativeDb());
            afterPoint.setNode(new Circle(4));
            Tooltip.install(afterPoint.getNode(), new Tooltip(String.format(Locale.ROOT, "%.1f Hz · %.2f dB", point.frequencyHz(), point.afterRelativeDb())));
            after.getData().add(afterPoint);
        }
        chart.getData().setAll(before, after);
        return chart;
    }

    protected TableView<CalibrationMeasurementAnalyzer.ComparisonPoint> calibrationComparisonTable(
            CalibrationMeasurementAnalyzer.Comparison comparison) {
        TableView<CalibrationMeasurementAnalyzer.ComparisonPoint> table = new TableView<>(FXCollections.observableArrayList(comparison.points()));
        TableColumn<CalibrationMeasurementAnalyzer.ComparisonPoint, String> frequency = new TableColumn<>(t("Частота, Hz", "Frequency, Hz"));
        frequency.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(String.format(Locale.ROOT, "%.1f", cell.getValue().frequencyHz())));
        TableColumn<CalibrationMeasurementAnalyzer.ComparisonPoint, String> before = new TableColumn<>(t("До, dB", "Before, dB"));
        before.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(String.format(Locale.ROOT, "%.2f", cell.getValue().beforeRelativeDb())));
        TableColumn<CalibrationMeasurementAnalyzer.ComparisonPoint, String> after = new TableColumn<>(t("После, dB", "After, dB"));
        after.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(String.format(Locale.ROOT, "%.2f", cell.getValue().afterRelativeDb())));
        TableColumn<CalibrationMeasurementAnalyzer.ComparisonPoint, String> delta = new TableColumn<>(t("Разница, dB", "Delta, dB"));
        delta.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(String.format(Locale.ROOT, "%+.2f", cell.getValue().deltaDb())));
        table.getColumns().setAll(frequency, before, after, delta);
        table.setAccessibleText(t("Таблица сравнения относительных кривых", "Relative response comparison table"));
        table.setPrefHeight(Math.min(260, 34 + comparison.points().size() * 28));
        return table;
    }

    protected LineChart<Number, Number> calibrationChart(CalibrationMeasurementAnalyzer.Result result, String method) {
        double lower = Math.log10(result.points().getFirst().frequencyHz());
        double upper = Math.log10(result.points().getLast().frequencyHz());
        NumberAxis frequency = new NumberAxis(lower, upper, Math.max(0.05, (upper - lower) / 4));
        frequency.setLabel(t("Частота, Hz · логарифмическая шкала", "Frequency, Hz · logarithmic scale"));
        frequency.setTickLabelFormatter(new javafx.util.StringConverter<>() {
            @Override public String toString(Number value) { return String.format(Locale.ROOT, "%.0f", Math.pow(10, value.doubleValue())); }
            @Override public Number fromString(String value) { return Math.log10(Double.parseDouble(value)); }
        });
        NumberAxis level = new NumberAxis();
        level.setLabel(t("Относительный уровень, dB", "Relative level, dB"));
        LineChart<Number, Number> chart = new LineChart<>(frequency, level);
        chart.setCreateSymbols(true); chart.setAnimated(false); chart.setLegendVisible(true); chart.setPrefHeight(280);
        chart.setAccessibleText(t("График относительного отклика. Таблица точных значений находится ниже.",
                "Relative response chart. A table with exact values follows."));
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName("microphone".equals(method) ? t("Микрофон", "Microphone") : t("Акселерометр", "Accelerometer"));
        for (CalibrationMeasurementAnalyzer.Point point : result.points()) {
            XYChart.Data<Number, Number> data = new XYChart.Data<>(Math.log10(point.frequencyHz()), point.relativeDb());
            data.setNode(new Circle(4));
            String clipping = point.clipped() ? t(" · клиппинг", " · clipped") : "";
            Tooltip.install(data.getNode(), new Tooltip(String.format(Locale.ROOT, "%.1f Hz · %.2f dB", point.frequencyHz(), point.relativeDb()) + clipping));
            series.getData().add(data);
        }
        chart.getData().add(series);
        return chart;
    }

    protected LineChart<Number, Number> calibrationFilterChart(CalibrationMeasurementAnalyzer.Result result, String method,
                                                               List<CalibrationFilterAdvisor.Suggestion> suggestions) {
        LineChart<Number, Number> chart = calibrationChart(result, method);
        chart.setAccessibleText(t("Исходная относительная кривая и offline-прогноз после PEQ-срезов; прогноз не является измерением.",
                "Original relative response and offline prediction after PEQ cuts; prediction is not a measurement."));
        XYChart.Series<Number, Number> predicted = new XYChart.Series<>();
        predicted.setName(t("Прогноз после срезов", "Predicted after cuts"));
        for (CalibrationFilterAdvisor.PreviewPoint point : CalibrationFilterAdvisor.preview(result, suggestions)) {
            XYChart.Data<Number, Number> data = new XYChart.Data<>(Math.log10(point.frequencyHz()), point.afterRelativeDb());
            data.setNode(new Circle(4));
            Tooltip.install(data.getNode(), new Tooltip(String.format(Locale.ROOT, "%.1f Hz · %.2f dB", point.frequencyHz(), point.afterRelativeDb())));
            predicted.getData().add(data);
        }
        chart.getData().add(predicted);
        return chart;
    }

    protected TableView<CalibrationMeasurementAnalyzer.Point> calibrationPointsTable(CalibrationMeasurementAnalyzer.Result result) {
        TableView<CalibrationMeasurementAnalyzer.Point> table = new TableView<>(FXCollections.observableArrayList(result.points()));
        TableColumn<CalibrationMeasurementAnalyzer.Point, Number> frequency = new TableColumn<>(t("Частота, Hz", "Frequency, Hz"));
        frequency.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().frequencyHz()));
        TableColumn<CalibrationMeasurementAnalyzer.Point, Number> amplitude = new TableColumn<>(t("Входной PCM", "Input PCM"));
        amplitude.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().amplitudePcm()));
        TableColumn<CalibrationMeasurementAnalyzer.Point, Number> level = new TableColumn<>(t("Относительно, dB", "Relative, dB"));
        level.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().relativeDb()));
        TableColumn<CalibrationMeasurementAnalyzer.Point, String> clipped = new TableColumn<>(t("Клиппинг", "Clipped"));
        clipped.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().clipped() ? t("Да", "Yes") : t("Нет", "No")));
        table.getColumns().setAll(frequency, amplitude, level, clipped);
        table.setAccessibleText(t("Таблица значений относительного отклика", "Relative response values table"));
        table.setPrefHeight(Math.min(260, 34 + result.points().size() * 28));
        table.setPlaceholder(label(t("Нет точек для отображения.", "No response points to show."), "muted"));
        return table;
    }
}
