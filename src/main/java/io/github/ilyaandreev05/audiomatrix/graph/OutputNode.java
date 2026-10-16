package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OutputNode extends GraphNode {
    private final String deviceId;
    private final ChannelMatrix channelMatrix;
    public OutputNode(String id, String name, String deviceId, List<String> channels) {
        this(id, name, deviceId, channels, null);
    }
    public OutputNode(String id, String name, String deviceId, List<String> channels, ChannelMatrix channelMatrix) {
        super(id, name, List.of(new AudioPort("in", AudioPort.Direction.INPUT, channels, false)));
        if (deviceId == null || deviceId.isBlank()) throw new IllegalArgumentException("Output device id must not be blank");
        if (channelMatrix != null && !channelMatrix.inputs().equals(channels))
            throw new IllegalArgumentException("Output channel matrix inputs must match the node input channels");
        this.deviceId = deviceId;
        this.channelMatrix = channelMatrix;
    }
    public String deviceId() { return deviceId; }
    public ChannelMatrix channelMatrix() { return channelMatrix; }
    @Override public String type() { return "output"; }
    @Override public Map<String, Object> parameters() {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("device_id", deviceId);
        if (channelMatrix != null) result.put("channel_matrix", channelMatrix.toMap());
        return result;
    }
}
