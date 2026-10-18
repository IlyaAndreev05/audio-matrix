package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.*;
import java.util.function.*;

/** Edits the one shared tactile graph; muting never replaces remembered levels. */
final class GraphVibroControls {
    private GraphVibroControls() {}
    static VBox create(Map<String, Object> config, BiFunction<String, String, String> t,
                       Consumer<Consumer<Map<String, Object>>> save) {
        VBox box = new VBox(9);
        box.getStyleClass().add("card");
        box.getChildren().add(new Label(t.apply("📌 Общий тактильный тракт", "📌 Common tactile path")));
        AudioGraph graph = GraphCommonVibro.graph(config);
        if (graph == null) { box.getChildren().add(new Label(t.apply("Тактильный граф не настроен", "Tactile graph is not configured"))); return box; }
        MixerNode mixer = graph.nodes().stream().filter(node -> GraphCommonVibro.VIBRO_MIXER_ID.equals(node.id()) && node instanceof MixerNode)
                .map(MixerNode.class::cast).findFirst().orElse(null);
        if (mixer != null) for (String port : List.of("bass", "events")) {
            if (!mixer.inputGains().containsKey(port)) continue;
            CheckBox enabled = new CheckBox("bass".equals(port) ? t.apply("Басы", "Bass") : t.apply("События", "Events"));
            enabled.setSelected(!mixer.inputMuted(port));
            enabled.setOnAction(event -> save.accept(candidate -> change(candidate, mixer.id(), node -> {
                MixerNode current = (MixerNode) node;
                Map<String, Boolean> mutes = new LinkedHashMap<>(current.inputMutes());
                mutes.put(port, !enabled.isSelected());
                return new MixerNode(current.id(), current.name(), current.inputs().getFirst().channels(), current.inputGains(), mutes);
            })));
            box.getChildren().add(enabled);
        }
        GainNode gain = graph.nodes().stream().filter(node -> GraphCommonVibro.VIBRO_GAIN_ID.equals(node.id()) && node instanceof GainNode)
                .map(GainNode.class::cast).findFirst().orElse(null);
        if (gain != null) {
            Slider level = new Slider(0, 1, gain.muted() ? 0 : gain.gain());
            level.setAccessibleText(t.apply("Общий уровень Vibro", "Global Vibro level"));
            Label value = new Label(Math.round(level.getValue() * 100) + "%");
            level.valueProperty().addListener((o, a, b) -> value.setText(Math.round(b.doubleValue() * 100) + "%"));
            Runnable commit = () -> save.accept(candidate -> change(candidate, gain.id(), node -> {
                GainNode current = (GainNode) node;
                return new GainNode(current.id(), current.name(), current.inputs().getFirst().channels(), level.getValue(), false, current.endpointNodeId());
            }));
            level.setOnMouseReleased(event -> commit.run()); level.setOnKeyReleased(event -> commit.run());
            CheckBox mute = new CheckBox("Mute"); mute.setSelected(gain.muted());
            mute.setOnAction(event -> save.accept(candidate -> change(candidate, gain.id(), node -> {
                GainNode current = (GainNode) node;
                return new GainNode(current.id(), current.name(), current.inputs().getFirst().channels(), current.gain(), mute.isSelected(), current.endpointNodeId());
            })));
            box.getChildren().add(new HBox(8, level, value, mute));
        }
        box.getChildren().add(new Label(t.apply("Один тракт для Монитора, Наушников и Свободного. Уровни сохраняются при переключении профиля.",
                "Shared by Monitor, Headphones and Free. Levels survive profile changes.")));
        return box;
    }
    private static void change(Map<String, Object> config, String id, UnaryOperator<GraphNode> mutation) {
        AudioGraph graph = GraphCommonVibro.graph(config);
        if (graph == null) throw new IllegalArgumentException("Common Vibro graph no longer exists");
        List<GraphNode> nodes = new ArrayList<>(graph.nodes());
        for (int index = 0; index < nodes.size(); index++) if (id.equals(nodes.get(index).id())) {
            nodes.set(index, mutation.apply(nodes.get(index)));
            GraphCommonVibro.save(config, new AudioGraph(graph.id(), graph.name(), nodes, graph.connections()));
            return;
        }
        throw new IllegalArgumentException("Vibro control no longer exists");
    }
}
