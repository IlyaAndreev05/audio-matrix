package io.github.ilyaandreev05.audiomatrix.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import java.util.function.IntConsumer;
/** Interactive logarithmic EQ response plot with draggable frequency and gain points. */
final class GraphEqCanvas extends Canvas {
    private static final double LEFT = 42, RIGHT = 12, TOP = 12, BOTTOM = 28;
    private static final double MIN_HZ = 20, MAX_HZ = 20000, MIN_DB = -24, MAX_DB = 24;
    private List<Map<String, Object>> bands = List.of();
    private int selected = -1;
    private BiConsumer<Integer, double[]> onMove = (index, values) -> {};
    private IntConsumer onSelect = index -> {};
    private boolean dragging;

    GraphEqCanvas() {
        super(600, 230);
        setAccessibleText("Parametric EQ response, frequency 20 Hz to 20 kHz; drag points to change frequency and gain");
        setOnMousePressed(event -> select(event.getX(), event.getY()));
        setOnMouseDragged(event -> move(event.getX(), event.getY()));
        setOnMouseReleased(event -> dragging = false);
        widthProperty().addListener((observable, oldValue, value) -> draw());
        heightProperty().addListener((observable, oldValue, value) -> draw());
        draw();
    }

    void setBands(List<Map<String, Object>> value, int selectedIndex, BiConsumer<Integer, double[]> callback,
                  IntConsumer selectionCallback) {
        bands = List.copyOf(value);
        selected = selectedIndex;
        onMove = callback == null ? (index, point) -> {} : callback;
        onSelect = selectionCallback;
        draw();
    }

    private void select(double x, double y) {
        int nearest = -1;
        double distance = 14;
        for (int i = 0; i < bands.size(); i++) {
            double dx = x(bandNumber(bands.get(i), "frequency_hz", "frequencyHz")) - x;
            double dy = y(bandNumber(bands.get(i), "gain_db", "gainDb")) - y;
            double current = Math.hypot(dx, dy);
            if (current < distance) { nearest = i; distance = current; }
        }
        dragging = nearest >= 0;
        if (dragging) { selected = nearest; onSelect.accept(nearest); draw(); }
    }

    private void move(double px, double py) {
        if (!dragging || selected < 0 || selected >= bands.size()) return;
        double width = Math.max(1, getWidth() - LEFT - RIGHT);
        double height = Math.max(1, getHeight() - TOP - BOTTOM);
        double fraction = clamp((px - LEFT) / width, 0, 1);
        double frequency = MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, fraction);
        double gain = clamp(MAX_DB - (py - TOP) / height * (MAX_DB - MIN_DB), MIN_DB, MAX_DB);
        onMove.accept(selected, new double[]{frequency, gain});
    }

    private void draw() {
        GraphicsContext graphics = getGraphicsContext2D();
        double width = getWidth(), height = getHeight();
        graphics.setFill(Color.web("#171a20")); graphics.fillRect(0, 0, width, height);
        graphics.setStroke(Color.web("#414854")); graphics.setLineWidth(1);
        for (int db = -24; db <= 24; db += 12) {
            double y = y(db); graphics.strokeLine(LEFT, y, width - RIGHT, y);
            graphics.setFill(Color.web("#aab2bf")); graphics.fillText(db + " dB", 2, y + 4);
        }
        double[] ticks = {20, 50, 100, 500, 1000, 5000, 10000, 20000};
        for (double frequency : ticks) {
            double x = x(frequency); graphics.strokeLine(x, TOP, x, height - BOTTOM);
            String label = frequency >= 1000 ? Math.round(frequency / 1000) + "k" : Integer.toString((int) frequency);
            graphics.setFill(Color.web("#aab2bf")); graphics.fillText(label, x - 10, height - 7);
        }
        graphics.setStroke(Color.web("#18b6a4")); graphics.setLineWidth(2.2);
        double previousX = x(MIN_HZ), previousY = y(GraphEqResponse.responseDb(bands, MIN_HZ));
        for (int i = 1; i <= 420; i++) {
            double fraction = (double) i / 420;
            double frequency = MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, fraction);
            double currentX = x(frequency), currentY = y(GraphEqResponse.responseDb(bands, frequency));
            graphics.strokeLine(previousX, previousY, currentX, currentY);
            previousX = currentX; previousY = currentY;
        }
        for (int i = 0; i < bands.size(); i++) {
            double px = x(bandNumber(bands.get(i), "frequency_hz", "frequencyHz"));
            double py = y(bandNumber(bands.get(i), "gain_db", "gainDb"));
            graphics.setFill(i == selected ? Color.web("#f6f7f9") : Color.web("#3978ff"));
            graphics.fillOval(px - 5, py - 5, 10, 10);
            graphics.setStroke(Color.web("#101216")); graphics.strokeOval(px - 5, py - 5, 10, 10);
        }
    }

    private double x(double frequency) {
        double fraction = Math.log(clamp(frequency, MIN_HZ, MAX_HZ) / MIN_HZ) / Math.log(MAX_HZ / MIN_HZ);
        return LEFT + fraction * (getWidth() - LEFT - RIGHT);
    }

    private double y(double db) {
        return TOP + (MAX_DB - clamp(db, MIN_DB, MAX_DB)) / (MAX_DB - MIN_DB) * (getHeight() - TOP - BOTTOM);
    }

    private static double bandNumber(Map<String, Object> band, String snake, String camel) {
        Object value = band.getOrDefault(snake, band.get(camel));
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
