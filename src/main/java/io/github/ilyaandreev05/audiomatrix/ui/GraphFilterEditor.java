package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/** Native JavaFX editor for the supported graph filter processors. */
public final class GraphFilterEditor {
    private static final List<String> EFFECTS = List.of("eq", "low_pass", "high_pass", "delay", "limiter");
    private final FilterNode source;
    private final BiFunction<String, String, String> t;
    private final Dialog<Settings> dialog = new Dialog<>();
    private final ComboBox<String> effect = new ComboBox<>();
    private final CheckBox enabled;
    private final VBox fields = new VBox(8);
    private final Button save;
    private GraphEqBandEditor eqBands;
    private TextField frequency, delay, ceiling, lookahead, release;
    private ComboBox<Integer> slope;

    private GraphFilterEditor(Window owner, FilterNode node, BiFunction<String, String, String> translate) {
        source = node; t = translate;
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(t.apply("Настройки Filter", "Filter settings"));
        dialog.setHeaderText(node.name());
        dialog.getDialogPane().setPrefWidth(760);
        dialog.getDialogPane().setPrefHeight(610);
        effect.getItems().setAll(EFFECTS);
        effect.setValue(EFFECTS.contains(node.processor()) ? node.processor() : "low_pass");
        enabled = new CheckBox(t.apply("Фильтр включён", "Filter enabled"));
        enabled.setSelected(node.enabled());
        dialog.getDialogPane().setContent(new VBox(10, new Label(t.apply("Эффект", "Effect")), effect, enabled, fields));
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);
        save = (Button) dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK);
        effect.valueProperty().addListener((observable, oldValue, newValue) -> renderFields());
        effect.setAccessibleText(t.apply("Тип эффекта Filter", "Filter effect type"));
        renderFields();
        effect.valueProperty().addListener((observable, oldValue, newValue) -> validate());
        enabled.selectedProperty().addListener((observable, oldValue, newValue) -> validate());
        dialog.setResultConverter(button -> button == javafx.scene.control.ButtonType.OK ? settings() : null);
    }

    public static boolean supportsEditor(FilterNode node) { return !"phase_invert".equals(node.processor()); }

    public static Optional<Settings> show(Window owner, FilterNode node,
                                          BiFunction<String, String, String> translate) {
        if (!supportsEditor(node)) return Optional.empty();
        return new GraphFilterEditor(owner, node, translate).dialog.showAndWait();
    }

    private void renderFields() {
        fields.getChildren().clear(); eqBands = null; frequency = delay = ceiling = lookahead = release = null;
        Map<String, Object> params = source.parameters();
        switch (effect.getValue()) {
            case "eq" -> {
                Object bands = "eq".equals(source.processor()) ? params.get("bands") :
                        List.of(Map.of("type", "peaking", "frequency_hz", 100.0, "gain_db", 0.0, "q", 1.0));
                eqBands = new GraphEqBandEditor(bands, t);
                fields.getChildren().add(eqBands.view());
            }
            case "low_pass", "high_pass" -> renderPassFields(params);
            case "delay" -> {
                delay = field(t.apply("Задержка ветки (мс)", "Path delay (ms)"),
                        value(params, "delay_ms", 0.0)); fields.getChildren().add(delay);
            }
            case "limiter" -> renderLimiterFields(params);
            default -> fields.getChildren().add(new Label("Unsupported filter"));
        }
        validate();
    }

    private void renderPassFields(Map<String, Object> params) {
        frequency = field(t.apply("Частота среза (Hz)", "Cutoff frequency (Hz)"), value(params, "frequency_hz", 100.0));
        slope = new ComboBox<>(); slope.getItems().setAll(6, 12, 18, 24, 36, 48);
        slope.setValue((int) number(params.get("slope_db_oct"), 24));
        fields.getChildren().addAll(frequency, new Label(t.apply("Крутизна (dB/октаву)", "Slope (dB/octave)")), slope);
    }

    private void renderLimiterFields(Map<String, Object> params) {
        ceiling = field(t.apply("Порог (dBFS)", "Ceiling (dBFS)"), value(params, "ceiling_db", -1.0));
        lookahead = field(t.apply("Lookahead (мс)", "Lookahead (ms)"), value(params, "lookahead_ms", 5.0));
        release = field(t.apply("Восстановление (мс)", "Release (ms)"), value(params, "release_ms", 80.0));
        fields.getChildren().addAll(ceiling, lookahead, release);
    }

    private void validate() {
        boolean valid = switch (effect.getValue() == null ? "" : effect.getValue()) {
            case "eq" -> eqBands != null && eqBands.valid();
            case "low_pass", "high_pass" -> bounded(frequency, 20, 20000) && slope.getValue() != null;
            case "delay" -> bounded(delay, 0, 5000);
            case "limiter" -> bounded(ceiling, -12, 0) && bounded(lookahead, 1, 10) && bounded(release, 5, 500);
            default -> false;
        };
        save.setDisable(!valid);
    }

    private Settings settings() {
        Map<String, Object> params = new LinkedHashMap<>();
        switch (effect.getValue()) {
            case "eq" -> params.put("bands", eqBands.bands());
            case "low_pass", "high_pass" -> {
                params.put("frequency_hz", parse(frequency)); params.put("slope_db_oct", slope.getValue());
            }
            case "delay" -> params.put("delay_ms", parse(delay));
            case "limiter" -> {
                params.put("ceiling_db", parse(ceiling)); params.put("lookahead_ms", parse(lookahead));
                params.put("release_ms", parse(release));
            }
            default -> throw new IllegalStateException("Unknown graph filter type");
        }
        return new Settings(effect.getValue(), enabled.isSelected(), params);
    }

    private TextField field(String label, String initial) {
        TextField input = new TextField(initial); input.setPromptText(label);
        input.textProperty().addListener((observable, oldValue, newValue) -> validate());
        return input;
    }

    private static String value(Map<String, Object> params, String key, double fallback) {
        return Double.toString(number(params.get(key), fallback));
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static double parse(TextField field) { return Double.parseDouble(field.getText().trim()); }

    private static boolean bounded(TextField field, double min, double max) {
        if (field == null) return false;
        try { double value = parse(field); return Double.isFinite(value) && value >= min && value <= max; }
        catch (NumberFormatException invalid) { return false; }
    }

    public record Settings(String processor, boolean enabled, Map<String, Object> parameters) {
        public Settings { parameters = Map.copyOf(parameters); }
    }
}
