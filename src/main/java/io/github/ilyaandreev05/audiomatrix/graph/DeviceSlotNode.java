package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;
import java.util.Map;

/** One logical input/output bound explicitly to a persistent device catalogue entry. */
public final class DeviceSlotNode extends GraphNode {
    private final String deviceId;
    private final double volume;
    private final boolean muted;

    public DeviceSlotNode(String id, String name, String deviceId, List<String> channels) {
        this(id, name, deviceId, channels, 0.0, false);
    }

    public DeviceSlotNode(String id, String name, String deviceId, List<String> channels,
                          double volume, boolean muted) {
        super(id, name, List.of(new AudioPort("in", AudioPort.Direction.INPUT, channels, false),
                new AudioPort("out", AudioPort.Direction.OUTPUT, channels, false)));
        this.deviceId = deviceId == null ? "" : deviceId;
        if (!Double.isFinite(volume) || volume < 0 || volume > 1)
            throw new IllegalArgumentException("Device output volume must be between 0 and 1");
        this.volume = volume;
        this.muted = muted;
    }

    /** Blank means unassigned and must remain silent, never the system default sink. */
    public String deviceId() { return deviceId; }
    public double volume() { return volume; }
    public boolean muted() { return muted; }
    @Override public String type() { return "device_slot"; }
    @Override public Map<String, Object> parameters() {
        return Map.of("device_id", deviceId, "volume", volume, "muted", muted);
    }
}
