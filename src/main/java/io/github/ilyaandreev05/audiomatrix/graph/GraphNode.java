package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;
import java.util.Map;

/** Common graph identity; each node type owns its ports and behavior. */
public sealed abstract class GraphNode permits SourceNode, OutputNode, GainNode, GateNode, RouterNode, FilterNode, MixerNode {
    private final String id;
    private final String name;
    private final List<AudioPort> ports;

    protected GraphNode(String id, String name, List<AudioPort> ports) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Node id must not be blank");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Node name must not be blank");
        this.id = id;
        this.name = name;
        this.ports = List.copyOf(ports);
        if (this.ports.stream().map(AudioPort::id).distinct().count() != this.ports.size())
            throw new IllegalArgumentException("Port ids must be unique within a node");
    }

    public String id() { return id; }
    public String name() { return name; }
    public List<AudioPort> ports() { return ports; }
    public abstract String type();
    public abstract Map<String, Object> parameters();
    public final List<AudioPort> inputs() { return ports.stream().filter(p -> p.direction() == AudioPort.Direction.INPUT).toList(); }
    public final List<AudioPort> outputs() { return ports.stream().filter(p -> p.direction() == AudioPort.Direction.OUTPUT).toList(); }
}
