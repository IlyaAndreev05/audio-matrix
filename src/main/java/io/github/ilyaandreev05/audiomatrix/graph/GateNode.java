package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;
import java.util.Map;

public final class GateNode extends GraphNode {
    private final boolean open;
    public GateNode(String id, String name, List<String> channels, boolean open) {
        super(id, name, List.of(new AudioPort("in", AudioPort.Direction.INPUT, channels, true),
                new AudioPort("out", AudioPort.Direction.OUTPUT, channels, false)));
        this.open = open;
    }
    public boolean open() { return open; }
    @Override public String type() { return "gate"; }
    @Override public Map<String, Object> parameters() { return Map.of("open", open); }
}
