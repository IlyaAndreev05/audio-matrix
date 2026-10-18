package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.function.BiConsumer;

/** Independent gain and mute controls for each Mixer input port. */
final class GraphMixerControls {
    private GraphMixerControls() {}

    static VBox create(MixerNode mixer, String title, String muteLabel,
                       BiConsumer<String, Double> setGain, BiConsumer<String, Boolean> setMute) {
        VBox controls = new VBox(5);
        controls.getChildren().add(new Label(title));
        for (String port : mixer.inputGains().keySet())
            controls.getChildren().add(inputRow(mixer, port, muteLabel, setGain, setMute));
        return controls;
    }

    private static HBox inputRow(MixerNode mixer, String port, String muteLabel,
                                 BiConsumer<String, Double> setGain, BiConsumer<String, Boolean> setMute) {
        boolean muted = mixer.inputMuted(port);
        double stored = mixer.inputGains().get(port);
        double effective = muted ? 0 : stored;
        Label name = new Label(port);
        name.setMinWidth(90);
        Slider level = new Slider(0, 1, effective);
        level.setPrefWidth(135);
        Label percent = new Label(Math.round(effective * 100) + "%");
        percent.setMinWidth(38);
        level.valueProperty().addListener((observable, oldValue, value) ->
                percent.setText(Math.round(value.doubleValue() * 100) + "%"));
        level.setOnMouseReleased(event -> setGain.accept(port, round(level.getValue())));
        level.setOnKeyReleased(event -> setGain.accept(port, round(level.getValue())));
        CheckBox mute = new CheckBox(muteLabel);
        mute.setSelected(muted);
        mute.setOnAction(event -> setMute.accept(port, mute.isSelected()));
        HBox row = new HBox(7, name, level, percent, mute);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static double round(double value) {
        return Math.rint(value * 1000) / 1000;
    }
}
