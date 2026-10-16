package io.github.ilyaandreev05.audiomatrix.audio;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Commands for explicitly targeted sensor capture and low-level calibration playback. */
final class PipeWireSweepCommands {
    private static final String MANUAL_IMPULSE_VOLUME = "0.03";

    private PipeWireSweepCommands() {}

    static List<String> record(AudioNode source, Path destination) throws IOException {
        if (source == null || !"input".equals(source.kind()) || source.id() <= 0 || source.channels() < 1 || source.channels() > 2)
            throw new IOException("Choose an online mono or stereo PipeWire input source");
        if (destination == null) throw new IOException("A temporary recording path is required");
        return List.of("pw-record", "--target", Integer.toString(source.id()), "--media-category", "Capture",
                "--rate", "48000", "--channels", Integer.toString(source.channels()), "--format", "s16",
                "--container", "wav", destination.toString());
    }

    static List<String> play(AudioNode sink, Path sweepFile) throws IOException {
        if (sink == null || !"output".equals(sink.kind()) || sink.id() <= 0)
            throw new IOException("Choose an online PipeWire output sink");
        if (sweepFile == null) throw new IOException("A generated sweep file is required");
        return List.of("pw-play", "--target", Integer.toString(sink.id()), "--volume", "0.10", sweepFile.toString());
    }

    static List<String> playManualImpulse(AudioNode sink, Path impulseFile) throws IOException {
        if (sink == null || !"output".equals(sink.kind()) || sink.id() <= 0)
            throw new IOException("Choose an online PipeWire output sink");
        if (impulseFile == null) throw new IOException("A generated manual impulse file is required");
        return List.of("pw-play", "--target", Integer.toString(sink.id()), "--volume", MANUAL_IMPULSE_VOLUME,
                impulseFile.toString());
    }
}
