package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;
import java.util.Map;

public non-sealed class SourceNode extends GraphNode {
    private final String selector;
    public SourceNode(String id, String name, String selector, List<String> channels) {
        super(id, name, List.of(new AudioPort("out", AudioPort.Direction.OUTPUT, channels, false)));
        if (selector == null || selector.isBlank()) throw new IllegalArgumentException("Source selector must not be blank");
        this.selector = selector;
    }
    public String selector() { return selector; }
    @Override public String type() { return "source"; }
    @Override public Map<String, Object> parameters() { return Map.of("selector", selector); }
}
