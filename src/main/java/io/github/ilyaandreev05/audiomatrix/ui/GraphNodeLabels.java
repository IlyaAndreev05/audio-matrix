package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.RouterNode;
import java.util.function.BiFunction;

final class GraphNodeLabels {
    private GraphNodeLabels() {}

    static String typeName(GraphNode node, BiFunction<String, String, String> translate) {
        if (node instanceof DynamicGeneratorNode) return translate.apply("Генератор источников", "Dynamic generator");
        if (node instanceof MixerNode) return translate.apply("Merge · сумматор", "Merge mixer");
        if (node instanceof RouterNode router && router.multiple()) return "MultiSwitch";
        return switch (node.type()) {
            case "source" -> translate.apply("Источник", "Source");
            case "output" -> translate.apply("Выход", "Output");
            case "device_slot" -> translate.apply("Выход", "Output");
            case "gain" -> "Volume";
            case "gate" -> translate.apply("Затвор", "Gate");
            case "selector" -> translate.apply("Переключатель", "Selector");
            case "multi_selector" -> "MultiSelector";
            case "matrix" -> "Matrix";
            case "filter" -> translate.apply("Фильтр", "Filter");
            default -> node.type();
        };
    }
}
