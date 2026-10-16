package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;
import java.util.Map;

public final class OutputNode extends GraphNode {
    private final String deviceId;
    public OutputNode(String id, String name, String deviceId, List<String> channels) {
        super(id, name, List.of(new AudioPort("in", AudioPort.Direction.INPUT, channels, false)));
        if (deviceId == null || deviceId.isBlank()) throw new IllegalArgumentException("Output device id must not be blank");
        this.deviceId = deviceId;
    }
    public String deviceId() { return deviceId; }
    @Override public String type() { return "output"; }
    @Override public Map<String, Object> parameters() { return Map.of("device_id", deviceId); }
}
