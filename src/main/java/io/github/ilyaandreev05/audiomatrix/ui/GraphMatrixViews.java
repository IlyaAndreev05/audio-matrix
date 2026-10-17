package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.AudioPort;
import io.github.ilyaandreev05.audiomatrix.graph.MatrixNode;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.CubicCurve;

import java.util.List;
import java.util.function.BiConsumer;

/** Synchronized table and flow views over one Matrix node's active links. */
final class GraphMatrixViews {
    private static final List<Color> INPUT_COLORS = List.of(
            Color.web("#16a394"), Color.web("#3978ff"), Color.web("#d77942"),
            Color.web("#a275d0"), Color.web("#b4a032"));

    private GraphMatrixViews() {}

    static TabPane create(MatrixNode matrix, String tableTitle, String arrowsTitle, String tableLabel,
                          BiConsumer<String, Boolean> changeLink) {
        TabPane tabs = new TabPane();
        Tab table = new Tab(tableTitle, matrixTable(matrix, tableLabel, changeLink));
        Tab arrows = new Tab(arrowsTitle, arrowView(matrix, tableLabel, changeLink));
        table.setClosable(false);
        arrows.setClosable(false);
        tabs.getTabs().setAll(table, arrows);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        return tabs;
    }

    private static GridPane matrixTable(MatrixNode matrix, String title, BiConsumer<String, Boolean> changeLink) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(5);
        grid.add(new Label(title), 0, 0);
        for (int column = 0; column < matrix.outputs().size(); column++)
            grid.add(new Label(matrix.outputs().get(column).id()), column + 1, 0);
        for (int row = 0; row < matrix.inputs().size(); row++) {
            AudioPort input = matrix.inputs().get(row);
            grid.add(new Label(input.id()), 0, row + 1);
            for (int column = 0; column < matrix.outputs().size(); column++)
                grid.add(linkToggle(matrix, input, matrix.outputs().get(column), changeLink), column + 1, row + 1);
        }
        return grid;
    }

    private static VBox arrowView(MatrixNode matrix, String title, BiConsumer<String, Boolean> changeLink) {
        VBox view = new VBox(8);
        view.setPadding(new Insets(8, 0, 0, 0));
        view.getChildren().addAll(new Label(title), arrowDiagram(matrix), matrixTable(matrix, title, changeLink));
        return view;
    }

    private static Pane arrowDiagram(MatrixNode matrix) {
        double left = 110;
        double right = 390;
        double top = 24;
        double row = 44;
        double height = Math.max(matrix.inputs().size(), matrix.outputs().size()) * row + 20;
        Pane diagram = new Pane();
        diagram.setMinSize(500, height);
        diagram.setPrefSize(500, height);
        for (String link : matrix.activeLinks()) addArrow(diagram, matrix, link, left, right, top, row);
        addPortsAndLabels(diagram, matrix, left, right, top, row);
        return diagram;
    }

    private static void addArrow(Pane diagram, MatrixNode matrix, String link,
                                 double left, double right, double top, double row) {
        String[] ports = link.split("->", 2);
        if (ports.length != 2) return;
        int input = portIndex(matrix.inputs(), ports[0]);
        int output = portIndex(matrix.outputs(), ports[1]);
        if (input < 0 || output < 0) return;
        double startY = top + input * row;
        double endY = top + output * row;
        CubicCurve curve = new CubicCurve(left, startY, 205, startY, 295, endY, right, endY);
        curve.setFill(Color.TRANSPARENT);
        curve.setStroke(INPUT_COLORS.get(input % INPUT_COLORS.size()));
        curve.setStrokeWidth(2.5);
        curve.setMouseTransparent(true);
        diagram.getChildren().add(curve);
    }

    private static void addPortsAndLabels(Pane diagram, MatrixNode matrix,
                                           double left, double right, double top, double row) {
        for (int index = 0; index < matrix.inputs().size(); index++) {
            double y = top + index * row;
            Label label = new Label(matrix.inputs().get(index).id());
            label.setLayoutX(2);
            label.setLayoutY(y - 12);
            diagram.getChildren().addAll(label, new Circle(left, y, 5, INPUT_COLORS.get(index % INPUT_COLORS.size())));
        }
        for (int index = 0; index < matrix.outputs().size(); index++) {
            double y = top + index * row;
            Label label = new Label(matrix.outputs().get(index).id());
            label.setLayoutX(right + 10);
            label.setLayoutY(y - 12);
            diagram.getChildren().addAll(new Circle(right, y, 5, Color.web("#3978ff")), label);
        }
    }

    private static CheckBox linkToggle(MatrixNode matrix, AudioPort input, AudioPort output,
                                       BiConsumer<String, Boolean> changeLink) {
        String link = input.id() + "->" + output.id();
        CheckBox toggle = new CheckBox();
        toggle.setSelected(matrix.activeLinks().contains(link));
        toggle.setAccessibleText(matrix.name() + " " + input.id() + " → " + output.id());
        toggle.setOnAction(event -> changeLink.accept(link, toggle.isSelected()));
        return toggle;
    }

    private static int portIndex(List<AudioPort> ports, String id) {
        for (int index = 0; index < ports.size(); index++)
            if (ports.get(index).id().equals(id)) return index;
        return -1;
    }
}
