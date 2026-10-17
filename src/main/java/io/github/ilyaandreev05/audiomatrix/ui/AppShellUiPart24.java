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

abstract class AppShellUiPart24 extends AppShellUiPart23 {
    protected AppShellUiPart24(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected Map<String, Object> editChannelMatrix(List<String> inputs, List<String> outputs,
                                                   Map<String, Object> current, boolean limiterAvailable) {
        if (inputs.isEmpty() || outputs.isEmpty() || inputs.size() > 8 || outputs.size() > 8) return null;
        ChannelMatrix previous = null;
        try { if (current != null) previous = ChannelMatrix.parse(current); }
        catch (IllegalArgumentException ignored) { }

        GridPane grid = new GridPane();
        grid.setHgap(6); grid.setVgap(6); grid.setPadding(new Insets(8));
        grid.add(label(t("Выход ↓ / вход →", "Output ↓ / input →"), "muted"), 0, 0);
        for (int column = 0; column < inputs.size(); column++)
            grid.add(label(inputs.get(column), "card-title"), column + 1, 0);
        TextField[][] cells = new TextField[outputs.size()][inputs.size()];
        for (int row = 0; row < outputs.size(); row++) {
            grid.add(label(outputs.get(row), "card-title"), 0, row + 1);
            for (int column = 0; column < inputs.size(); column++) {
                String output = outputs.get(row), input = inputs.get(column);
                double initial = output.equals(input) ? 1.0 : 0.0;
                if (previous != null && previous.outputs().contains(output) && previous.inputs().contains(input))
                    initial = previous.coefficients().get(previous.outputs().indexOf(output)).get(previous.inputs().indexOf(input));
                TextField coefficient = new TextField(String.format(Locale.ROOT, "%.3f", initial));
                coefficient.setPrefColumnCount(6);
                coefficient.setAccessibleText(t("Коэффициент ", "Coefficient ") + output + " ← " + input);
                cells[row][column] = coefficient;
                grid.add(coefficient, column + 1, row + 1);
            }
        }
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true); scroll.setPrefViewportWidth(650); scroll.setPrefViewportHeight(360);
        Dialog<ButtonType> matrixDialog = new Dialog<>();
        if (getScene() != null) matrixDialog.initOwner(getScene().getWindow());
        matrixDialog.setTitle(t("Матрица каналов", "Channel matrix"));
        matrixDialog.setHeaderText(t("Введите коэффициенты от −1 до 1. Нулевая ячейка не соединяет каналы.",
                "Enter coefficients from −1 to 1. A zero cell leaves those channels unconnected."));
        matrixDialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        Label matrixError = label("", "danger");
        matrixError.setAccessibleText(t("Ошибка матрицы каналов", "Channel matrix error"));
        matrixError.setVisible(false); matrixError.setManaged(false);
        VBox matrixContent = new VBox(8, scroll, matrixError);
        matrixContent.setPadding(new Insets(4));
        matrixDialog.getDialogPane().setContent(matrixContent);
        styleDialog(matrixDialog);
        AtomicReference<ChannelMatrix> validatedMatrix = new AtomicReference<>();
        AtomicBoolean validationAttempted = new AtomicBoolean(false);
        Runnable validateMatrix = () -> {
            validatedMatrix.set(null);
            TextField invalidCell = null;
            String errorMessage = null;
            List<List<Double>> coefficients = new ArrayList<>();
            boolean anyNonZero = false;
            for (TextField[] row : cells) {
                List<Double> values = new ArrayList<>();
                for (TextField cell : row) {
                    try {
                        double coefficient = Double.parseDouble(cell.getText().trim());
                        if (!Double.isFinite(coefficient) || coefficient < -1.0 || coefficient > 1.0) {
                            invalidCell = cell;
                            errorMessage = t("Введите конечное число от −1 до 1.", "Enter a finite number from −1 to 1.");
                            break;
                        }
                        values.add(coefficient);
                        anyNonZero |= coefficient != 0.0;
                    } catch (NumberFormatException ignored) {
                        invalidCell = cell;
                        errorMessage = t("Введите число от −1 до 1.", "Enter a number from −1 to 1.");
                        break;
                    }
                }
                if (errorMessage != null) break;
                coefficients.add(values);
            }
            if (errorMessage == null && !anyNonZero) {
                invalidCell = cells[0][0];
                errorMessage = t("Оставьте хотя бы один ненулевой коэффициент.", "Keep at least one non-zero coefficient.");
            }
            if (errorMessage == null) {
                try {
                    ChannelMatrix matrix = ChannelMatrix.parse(DefaultConfigMap.map(
                            "inputs", List.copyOf(inputs), "outputs", List.copyOf(outputs), "coefficients", coefficients));
                    int boostedRow = -1;
                    for (int row = 0; row < matrix.coefficients().size(); row++) {
                        if (matrix.coefficients().get(row).stream().mapToDouble(Math::abs).sum() > 1.0 + 1e-9) {
                            boostedRow = row;
                            break;
                        }
                    }
                    if (!limiterAvailable && boostedRow >= 0) {
                        invalidCell = cells[boostedRow][0];
                        errorMessage = t("Сумма модулей коэффициентов выхода «" + outputs.get(boostedRow)
                                        + "» может усилить сигнал. Включите limiter или уменьшите коэффициенты.",
                                "The absolute gains for output '" + outputs.get(boostedRow)
                                        + "' can boost the signal. Enable the limiter or reduce those coefficients.");
                    } else validatedMatrix.set(matrix);
                } catch (IllegalArgumentException ignored) {
                    if (errorMessage == null) {
                        invalidCell = cells[0][0];
                        errorMessage = t("Проверьте размеры матрицы и значения коэффициентов.", "Check the matrix dimensions and coefficient values.");
                    }
                }
            }
            if (errorMessage == null) {
                matrixError.setText("");
                matrixError.setVisible(false); matrixError.setManaged(false);
                for (TextField[] row : cells) for (TextField cell : row) cell.setAccessibleHelp(null);
            } else {
                matrixError.setText(errorMessage);
                matrixError.setAccessibleText(errorMessage);
                matrixError.setVisible(true); matrixError.setManaged(true);
                if (invalidCell != null) {
                    invalidCell.setAccessibleHelp(errorMessage);
                    if (validationAttempted.get()) invalidCell.requestFocus();
                }
            }
        };
        javafx.scene.control.Button okButton = (javafx.scene.control.Button) matrixDialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            validationAttempted.set(true);
            validateMatrix.run();
            if (validatedMatrix.get() == null) event.consume();
        });
        for (TextField[] row : cells) for (TextField cell : row)
            cell.focusedProperty().addListener((observable, hadFocus, hasFocus) -> {
                if (validationAttempted.get() && hadFocus && !hasFocus) validateMatrix.run();
            });
        if (matrixDialog.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return null;
        return validatedMatrix.get() == null ? null : new LinkedHashMap<>(validatedMatrix.get().toMap());
    }

    protected List<String> routeMatrixBusPositions(Map<String, Object> config, String busId) {
        Map<String, Object> bus = maps(config.get("buses")).stream()
                .filter(candidate -> busId.equals(candidate.get("id"))).findFirst().orElse(null);
        return bus == null ? List.of() : matrixBusPositions(string(bus.get("channels"), "stereo"));
    }

    protected List<String> routeMatrixEndpointPositions(Map<String, Object> config, Endpoint endpoint) {
        if (endpoint.bus()) return routeMatrixBusPositions(config, endpoint.id());
        AudioNode node = endpoint.node();
        if (node == null && "system_default".equals(endpoint.id()))
            node = physicalSinks().stream().filter(candidate -> candidate.id() == snapshot.defaultSinkId()).findFirst().orElse(null);
        return node == null ? List.of() : matrixStreamPositions(node.channels());
    }

    protected ComboBox<Endpoint> endpointCombo(List<Endpoint> endpoints) {
        ComboBox<Endpoint> combo = new ComboBox<>(); combo.getItems().setAll(endpoints);
        combo.setCellFactory(list -> new javafx.scene.control.ListCell<>() { @Override protected void updateItem(Endpoint item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : item.label()); } });
        combo.setButtonCell(new javafx.scene.control.ListCell<>() { @Override protected void updateItem(Endpoint item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : item.label()); } });
        return combo;
    }

    protected void deleteRoute(String id) {
        updateRouteConfig(t("Удалить маршрут ", "Delete route ") + id, config -> {
            maps(config.get("routes")).removeIf(route -> id.equals(route.get("id")));
            for (Map<String, Object> profile : maps(config.get("profiles"))) profile.put("active_routes", stringList(profile.get("active_routes")).stream().filter(candidate -> !id.equals(candidate)).toList());
        });
    }

    protected void addBus() { editBus(null); }

    protected void addRoute(String busId, AudioNode sink) { editRoute(null); }

    protected void addDefaultFilter() {
        updateConfig(config -> {
            Map<String, Object> dsp = map(config.get("dsp"));
            Map<String, Object> eq = map(dsp.get("global_eq"));
            List<Map<String, Object>> filters = maps(eq.get("filters"));
            filters.add(DefaultConfigMap.map("type", "notch", "frequency_hz", 80, "gain_db", -2.0, "q", 2.0, "enabled", true));
        });
    }
}
