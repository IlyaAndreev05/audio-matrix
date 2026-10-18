package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;
import java.util.Map;

public final class GainNode extends GraphNode {
    private final double gain;
    private final boolean muted;
    private final String endpointNodeId;
    public GainNode(String id, String name, List<String> channels, double gain, boolean muted) {
        this(id, name, channels, gain, muted, "");
    }
    public GainNode(String id, String name, List<String> channels, double gain, boolean muted, String endpointNodeId) {
        super(id, name, List.of(new AudioPort("in", AudioPort.Direction.INPUT, channels, true),
                new AudioPort("out", AudioPort.Direction.OUTPUT, channels, false)));
        if (!Double.isFinite(gain) || gain < 0 || gain > 1) throw new IllegalArgumentException("Gain must be between 0 and 1");
        this.gain = gain;
        this.muted = muted;
        this.endpointNodeId = endpointNodeId == null ? "" : endpointNodeId;
    }
    public double gain() { return gain; }
    public boolean muted() { return muted; }
    public String endpointNodeId() { return endpointNodeId; }
    @Override public String type() { return "gain"; }
    @Override public Map<String, Object> parameters() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("gain", gain);
        result.put("muted", muted);
        if (!endpointNodeId.isBlank()) result.put("endpoint_node_id", endpointNodeId);
        return result;
    }
}
