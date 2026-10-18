package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;
import java.util.Map;

/** One logical input/output bound explicitly to a persistent device catalogue entry. */
public final class DeviceSlotNode extends GraphNode {
    private final String deviceId;

    public DeviceSlotNode(String id, String name, String deviceId, List<String> channels) {
        super(id, name, List.of(new AudioPort("in", AudioPort.Direction.INPUT, channels, false),
                new AudioPort("out", AudioPort.Direction.OUTPUT, channels, false)));
        this.deviceId = deviceId == null ? "" : deviceId;
    }

    /** Blank means unassigned and must remain silent, never the system default sink. */
    public String deviceId() { return deviceId; }
    @Override public String type() { return "device_slot"; }
    @Override public Map<String, Object> parameters() { return Map.of("device_id", deviceId); }
}
