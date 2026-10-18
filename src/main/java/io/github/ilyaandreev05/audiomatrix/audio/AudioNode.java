package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.List;

public record AudioNode(int id, String name, String description, String mediaClass, String state,
                        String applicationName, String processName, String applicationId, int channels, List<Integer> sampleRates,
                        double volume, boolean muted, Integer pulseId, DeviceIdentity identity) {
    public AudioNode(int id, String name, String description, String mediaClass, String state,
                     String applicationName, String processName, String applicationId, int channels,
                     List<Integer> sampleRates, double volume, boolean muted, Integer pulseId) {
        this(id, name, description, mediaClass, state, applicationName, processName, applicationId, channels,
                sampleRates, volume, muted, pulseId, DeviceIdentity.from(java.util.Map.of(), channels));
    }
    public String kind() {
        if (mediaClass.startsWith("Stream/")) return "stream";
        if (mediaClass.startsWith("Audio/Sink")) return "output";
        if (mediaClass.startsWith("Audio/Source")) return "input";
        return "other";
    }
    public boolean active() { return !"suspended".equalsIgnoreCase(state); }
}
