package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.GraphSubgraphLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextInputDialog;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Creates a subgraph from the live selection without clearing it before the async save runs. */
final class GraphSubgraphCreation {
    private GraphSubgraphCreation() {}

    static void show(AudioGraph graph, Set<String> selection, Consumer<Dialog<?>> styler,
                     BiFunction<String, String, String> t,
                     BiConsumer<String, Consumer<Map<String, Object>>> update) {
        List<String> chosen = graph.nodes().stream().map(GraphNode::id).filter(selection::contains).toList();
        if (chosen.size() < 2) return;
        TextInputDialog dialog = new TextInputDialog(t.apply("Новый подграф", "New subgraph"));
        dialog.setTitle(t.apply("Свернуть в подграф", "Collapse into subgraph"));
        dialog.setHeaderText(t.apply("Выбранные ноды останутся в графе, но будут показаны одним блоком.",
                "Selected nodes stay in the graph but appear as one block."));
        styler.accept(dialog);
        dialog.showAndWait().map(String::trim).filter(name -> !name.isBlank()).ifPresent(name -> {
            selection.removeAll(chosen);
            update.accept(t.apply("Создать подграф", "Create subgraph"),
                    config -> GraphSubgraphLibrary.create(config, graph, name, chosen));
        });
    }
}
