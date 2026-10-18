package io.github.ilyaandreev05.audiomatrix.ui;

import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Editable parametric EQ bands and their measured-coefficient response plot. */
final class GraphEqBandEditor {
    private final List<Map<String, Object>> bands = new ArrayList<>();
    private final ListView<String> list = new ListView<>();
    private final ComboBox<String> type = new ComboBox<>();
    private final TextField frequency = new TextField();
    private final TextField gain = new TextField();
    private final TextField q = new TextField();
    private final GraphEqCanvas canvas = new GraphEqCanvas();
    private final VBox view = new VBox(8);
    private final BiFunction<String, String, String> t;
    private boolean loading;

    GraphEqBandEditor(Object rawBands, BiFunction<String, String, String> translate) {
        t = translate;
        bands.addAll(decode(rawBands));
        type.getItems().setAll("peaking", "low_shelf", "high_shelf", "notch");
        frequency.setPromptText("100"); gain.setPromptText("0"); q.setPromptText("1.0");
        list.setPrefHeight(88);
        list.getSelectionModel().selectedIndexProperty().addListener((observable, oldValue, index) -> load(index.intValue()));
        type.valueProperty().addListener((observable, oldValue, value) -> editCurrent());
        frequency.textProperty().addListener((observable, oldValue, value) -> editCurrent());
        gain.textProperty().addListener((observable, oldValue, value) -> editCurrent());
        q.textProperty().addListener((observable, oldValue, value) -> editCurrent());
        Button add = new Button(t.apply("Добавить полосу", "Add band"));
        Button remove = new Button(t.apply("Удалить выбранную", "Remove selected"));
        add.setOnAction(event -> addBand());
        remove.setOnAction(event -> removeBand());
        canvas.setBands(bands, -1, this::movePoint);
        VBox fields = new VBox(6, field(t.apply("Тип", "Type"), type), field(t.apply("Частота, Hz", "Frequency, Hz"), frequency),
                field(t.apply("Усиление, dB", "Gain, dB"), gain), field("Q", q));
        HBox editors = new HBox(10, list, fields); editors.setAlignment(Pos.CENTER_LEFT);
        HBox actions = new HBox(8, add, remove); actions.setAlignment(Pos.CENTER_LEFT);
        view.getChildren().addAll(new Label(t.apply("Кривая рассчитана по biquad-коэффициентам; перетаскивай точки для изменения частоты и усиления.",
                "Curve uses biquad coefficients; drag points to change frequency and gain.")), canvas, actions, editors);
        refreshList();
        if (!bands.isEmpty()) list.getSelectionModel().select(0);
    }

    VBox view() { return view; }
    List<Map<String, Object>> bands() { return bands.stream().map(Map::copyOf).toList(); }

    boolean valid() {
        for (Map<String, Object> band : bands) {
            double hz = number(band.get("frequency_hz")), db = number(band.get("gain_db")), quality = number(band.get("q"));
            if (!Double.isFinite(hz) || !Double.isFinite(db) || !Double.isFinite(quality)
                    || hz < 20 || hz > 20000 || db < -24 || db > 24 || quality <= 0 || quality > 20) return false;
        }
        if (list.getSelectionModel().getSelectedIndex() < 0) return true;
        try {
            double hz = Double.parseDouble(frequency.getText()), db = Double.parseDouble(gain.getText());
            double quality = Double.parseDouble(q.getText());
            return hz >= 20 && hz <= 20000 && db >= -24 && db <= 24 && quality > 0 && quality <= 20;
        } catch (NumberFormatException invalid) { return false; }
    }

    private void addBand() {
        if (bands.size() >= 20) return;
        bands.add(band("peaking", bands.isEmpty() ? 100.0 : Math.min(20000, 100.0 * Math.pow(2, bands.size())), 0.0, 1.0));
        refreshList(); list.getSelectionModel().select(bands.size() - 1); draw();
    }

    private void removeBand() {
        int index = list.getSelectionModel().getSelectedIndex();
        if (index < 0 || index >= bands.size()) return;
        bands.remove(index); refreshList();
        if (!bands.isEmpty()) list.getSelectionModel().select(Math.min(index, bands.size() - 1));
        draw();
    }

    private void load(int index) {
        if (index < 0 || index >= bands.size()) return;
        Map<String, Object> band = bands.get(index);
        loading = true;
        type.setValue(String.valueOf(band.getOrDefault("type", "peaking")));
        frequency.setText(String.valueOf(band.get("frequency_hz")));
        gain.setText(String.valueOf(band.get("gain_db")));
        q.setText(String.valueOf(band.get("q")));
        loading = false; draw();
    }

    private void editCurrent() {
        if (loading) return;
        int index = list.getSelectionModel().getSelectedIndex();
        if (index < 0 || index >= bands.size()) return;
        try {
            Map<String, Object> edited = band(type.getValue(), Double.parseDouble(frequency.getText()),
                    Double.parseDouble(gain.getText()), Double.parseDouble(q.getText()));
            bands.set(index, edited); refreshList(index); draw();
        } catch (RuntimeException ignored) {
            // Invalid in-progress text stays visible and disables Save until corrected.
        }
    }

    private void movePoint(int index, double[] point) {
        if (index < 0 || index >= bands.size()) return;
        Map<String, Object> old = bands.get(index);
        bands.set(index, band(String.valueOf(old.get("type")), point[0], point[1], number(old.get("q"))));
        loading = true; frequency.setText(String.format(java.util.Locale.ROOT, "%.1f", point[0]));
        gain.setText(String.format(java.util.Locale.ROOT, "%.1f", point[1])); loading = false;
        refreshList(index); draw();
    }

    private void draw() { canvas.setBands(bands, list.getSelectionModel().getSelectedIndex(), this::movePoint); }

    private void refreshList() { refreshList(list.getSelectionModel().getSelectedIndex()); }

    private void refreshList(int selected) {
        list.setItems(FXCollections.observableArrayList(bands.stream().map(this::label).toList()));
        if (selected >= 0 && selected < bands.size()) list.getSelectionModel().select(selected);
    }

    private String label(Map<String, Object> band) {
        return band.get("type") + " · " + Math.round(number(band.get("frequency_hz"))) + " Hz · "
                + number(band.get("gain_db")) + " dB · Q " + number(band.get("q"));
    }

    private static VBox field(String name, javafx.scene.Node control) { return new VBox(3, new Label(name), control); }
    private static Map<String, Object> band(String type, double hz, double db, double quality) {
        String normalized = type == null ? "peaking" : type;
        if (normalized.equalsIgnoreCase("bell") || normalized.equalsIgnoreCase("peak")) normalized = "peaking";
        return new LinkedHashMap<>(Map.of("type", normalized, "frequency_hz", hz, "gain_db", db, "q", quality));
    }

    private static List<Map<String, Object>> decode(Object raw) {
        if (raw instanceof Map<?, ?> legacy && legacy.containsKey("frequency_hz")) raw = List.of(legacy);
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) {
            Map<String, Object> band = new LinkedHashMap<>();
            Object type = map.containsKey("type") ? map.get("type") : map.containsKey("filter_kind")
                    ? map.get("filter_kind") : map.get("filterKind");
            String normalized = type == null ? "peaking" : type.toString().toLowerCase(java.util.Locale.ROOT)
                    .replace('-', '_').replace(' ', '_');
            if (normalized.equals("peak") || normalized.equals("bell")) normalized = "peaking";
            band.put("type", normalized);
            Object hz = map.containsKey("frequency_hz") ? map.get("frequency_hz") : map.get("frequencyHz");
            Object db = map.containsKey("gain_db") ? map.get("gain_db") : map.get("gainDb");
            band.put("frequency_hz", hz == null ? 100.0 : hz);
            band.put("gain_db", db == null ? 0.0 : db);
            band.put("q", map.containsKey("q") ? map.get("q") : 1.0);
            if (Boolean.FALSE.equals(map.get("enabled"))) band.put("enabled", false);
            result.add(band);
        }
        return result;
    }

    private static double number(Object value) { return value instanceof Number n ? n.doubleValue() : Double.NaN; }
}
