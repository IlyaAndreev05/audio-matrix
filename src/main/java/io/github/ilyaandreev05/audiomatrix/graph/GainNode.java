package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;
import java.util.Map;

public final class GainNode extends GraphNode {
    private final double gain;
    private final boolean muted;
    public GainNode(String id, String name, List<String> channels, double gain, boolean muted) {
        super(id, name, List.of(new AudioPort("in", AudioPort.Direction.INPUT, channels, true),
                new AudioPort("out", AudioPort.Direction.OUTPUT, channels, false)));
        if (!Double.isFinite(gain) || gain < 0 || gain > 1) throw new IllegalArgumentException("Gain must be between 0 and 1");
        this.gain = gain;
        this.muted = muted;
    }
    public double gain() { return gain; }
    public boolean muted() { return muted; }
    @Override public String type() { return "gain"; }
    @Override public Map<String, Object> parameters() { return Map.of("gain", gain, "muted", muted); }
}
