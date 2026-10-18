package io.github.ilyaandreev05.audiomatrix.audio;

import io.github.ilyaandreev05.audiomatrix.graph.EventSoundStudioSource;

import java.util.List;

/** PipeWire source exposed by the isolated audiomatrix_events virtual sink. */
final class EventInputMonitor {
    static final String NODE_NAME = "audiomatrix_events";

    private EventInputMonitor() {}

    static AudioNode node() {
        return new AudioNode(-1, NODE_NAME, "Audio Matrix event input monitor", "Audio/Source", "running",
                "Event Sound Studio", "pw-loopback", EventSoundStudioSource.APPLICATION_ID,
                2, List.of(48_000), 1.0, false, null);
    }

    static AudioNode sourceView(AudioNode sink) {
        return new AudioNode(sink.id(), NODE_NAME, sink.description(), "Audio/Source", sink.state(),
                "Event Sound Studio", "pw-loopback", EventSoundStudioSource.APPLICATION_ID,
                sink.channels(), sink.sampleRates(), sink.volume(), sink.muted(), sink.pulseId());
    }

    static boolean isSink(String mediaClass, String name) {
        return "Audio/Sink".equals(mediaClass) && NODE_NAME.equals(name);
    }

    static boolean isNode(AudioNode node) {
        return node != null && NODE_NAME.equals(node.name());
    }
}
