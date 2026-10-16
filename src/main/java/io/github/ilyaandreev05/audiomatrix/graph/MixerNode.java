package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit many-to-one summing point with a gain for every input. */
public final class MixerNode extends GraphNode {
    private final Map<String, Double> inputGains;
    public MixerNode(String id, String name, List<String> channels, Map<String, Double> inputGains) {
        super(id, name, ports(channels, inputGains));
        this.inputGains = Map.copyOf(new LinkedHashMap<>(inputGains));
        for (Map.Entry<String, Double> entry : this.inputGains.entrySet())
            if (entry.getValue() == null || !Double.isFinite(entry.getValue()) || entry.getValue() < 0 || entry.getValue() > 1)
                throw new IllegalArgumentException("Mixer input gains must be between 0 and 1");
    }
    private static List<AudioPort> ports(List<String> channels, Map<String, Double> gains) {
        if (gains == null || gains.size() < 2) throw new IllegalArgumentException("A mixer needs at least two named inputs");
        List<AudioPort> ports = new ArrayList<>();
        gains.keySet().forEach(id -> ports.add(new AudioPort(id, AudioPort.Direction.INPUT, channels, false)));
        ports.add(new AudioPort("out", AudioPort.Direction.OUTPUT, channels, false));
        return ports;
    }
    public Map<String, Double> inputGains() { return inputGains; }
    @Override public String type() { return "mixer"; }
    @Override public Map<String, Object> parameters() { return new LinkedHashMap<>(inputGains); }
}
