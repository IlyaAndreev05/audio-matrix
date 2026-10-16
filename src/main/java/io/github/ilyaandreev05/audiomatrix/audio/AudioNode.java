package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.List;

public record AudioNode(int id, String name, String description, String mediaClass, String state,
                        String applicationName, String processName, String applicationId, int channels, List<Integer> sampleRates,
                        double volume, boolean muted, Integer pulseId) {
    public String kind() {
        if (mediaClass.startsWith("Stream/")) return "stream";
        if (mediaClass.startsWith("Audio/Sink")) return "output";
        if (mediaClass.startsWith("Audio/Source")) return "input";
        return "other";
    }
    public boolean active() { return !"suspended".equalsIgnoreCase(state); }
}
