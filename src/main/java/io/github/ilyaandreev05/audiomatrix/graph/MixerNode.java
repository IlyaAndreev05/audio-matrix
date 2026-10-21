package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/** Explicit many-to-one summing point with a gain for every input. */
public final class MixerNode extends GraphNode {
    private final Map<String, Double> inputGains;
    private final Map<String, Boolean> inputMutes;
    public MixerNode(String id, String name, List<String> channels, Map<String, Double> inputGains) {
        this(id, name, channels, inputGains, Map.of());
    }
    public MixerNode(String id, String name, List<String> channels, Map<String, Double> inputGains,
                     Map<String, Boolean> inputMutes) {
        super(id, name, ports(channels, inputGains));
        this.inputGains = Collections.unmodifiableMap(new LinkedHashMap<>(inputGains));
        for (Map.Entry<String, Double> entry : this.inputGains.entrySet())
            if (entry.getValue() == null || !Double.isFinite(entry.getValue()) || entry.getValue() < 0 || entry.getValue() > 1)
                throw new IllegalArgumentException("Mixer input gains must be between 0 and 1");
        if (!this.inputGains.keySet().containsAll(inputMutes.keySet()))
            throw new IllegalArgumentException("Mixer input mutes must reference an existing input");
        Map<String, Boolean> mutes = new LinkedHashMap<>();
        this.inputGains.keySet().forEach(port -> mutes.put(port, Boolean.TRUE.equals(inputMutes.get(port))));
        this.inputMutes = Collections.unmodifiableMap(mutes);
    }
    private static List<AudioPort> ports(List<String> channels, Map<String, Double> gains) {
        if (gains == null || gains.isEmpty()) throw new IllegalArgumentException("A mixer needs at least one named input");
        List<AudioPort> ports = new ArrayList<>();
        gains.keySet().forEach(id -> ports.add(new AudioPort(id, AudioPort.Direction.INPUT, channels, false)));
        ports.add(new AudioPort("out", AudioPort.Direction.OUTPUT, channels, false));
        return ports;
    }
    public Map<String, Double> inputGains() { return inputGains; }
    /** Returns a copy with one effective-only fan-in port, preserving configured input levels. */
    public MixerNode withAdditionalInput(String inputId) {
        if (inputId == null || inputId.isBlank()) throw new IllegalArgumentException("Mixer input id must not be blank");
        if (inputGains.containsKey(inputId)) return this;
        Map<String, Double> gains = new LinkedHashMap<>(inputGains);
        gains.put(inputId, 1.0);
        Map<String, Boolean> mutes = new LinkedHashMap<>(inputMutes);
        mutes.put(inputId, false);
        return new MixerNode(id(), name(), inputs().getFirst().channels(), gains, mutes);
    }
    public Map<String, Boolean> inputMutes() { return inputMutes; }
    public boolean inputMuted(String port) { return inputMutes.getOrDefault(port, false); }
    @Override public String type() { return "mixer"; }
    @Override public Map<String, Object> parameters() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("input_gains", inputGains);
        parameters.put("input_mutes", inputMutes);
        return parameters;
    }
}
