package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.gnome.GnomeGraphOutputGain;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeGraphSourceGain;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import java.util.function.BiConsumer;

final class GraphGainControl {
    private GraphGainControl() {}

    static GainNode endpoint(AudioGraph graph, GraphNode node) {
        if (node instanceof SourceNode) return GnomeGraphSourceGain.forSource(graph, node.id());
        if (node instanceof OutputNode output) return GnomeGraphOutputGain.forOutput(graph, output);
        return null;
    }

    static HBox create(GainNode gain, String levelLabel, BiConsumer<Double, Boolean> save) {
        Slider level = new Slider(0, 1, gain.muted() ? 0 : gain.gain());
        level.setPrefWidth(180);
        level.setAccessibleText(levelLabel);
        Label value = new Label(Math.round(level.getValue() * 100) + "%");
        value.getStyleClass().add("muted");
        level.valueProperty().addListener((o, a, v) -> value.setText(Math.round(v.doubleValue() * 100) + "%"));
        Runnable persist = () -> save.accept(Math.round(level.getValue() * 1000) / 1000.0, false);
        level.setOnMouseReleased(event -> persist.run());
        level.setOnKeyReleased(event -> persist.run());
        CheckBox mute = new CheckBox("Mute");
        mute.setSelected(gain.muted());
        mute.setOnAction(event -> save.accept(null, mute.isSelected()));
        return new HBox(8, level, value, mute);
    }
}
