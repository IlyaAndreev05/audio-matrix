package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.GraphSubgraphLibrary;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Collapse/delete actions shared by the graph inspector. */
final class GraphSubgraphUi {
    private GraphSubgraphUi() {}
    static void setCollapsed(String routeId, String subgraphId, boolean collapsed,
                             BiFunction<String, String, String> t,
                             BiConsumer<String, Consumer<Map<String, Object>>> update) {
        update.accept(t.apply(collapsed ? "Свернуть подграф" : "Развернуть подграф",
                collapsed ? "Collapse subgraph" : "Expand subgraph"),
                config -> GraphSubgraphLibrary.setCollapsed(config, routeId, subgraphId, collapsed));
    }
    static void delete(String routeId, String subgraphId, BiFunction<String, String, String> t,
                       BiConsumer<String, Consumer<Map<String, Object>>> update) {
        update.accept(t.apply("Удалить подграф", "Delete subgraph"),
                config -> GraphSubgraphLibrary.delete(config, routeId, subgraphId));
    }
}
