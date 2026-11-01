package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import java.util.List;

/** The exact streams and values selected by a temporary GNOME stream operation. */
record RuntimeStreamReadback(List<AudioNode> targets, Double volume, boolean muted, long generation) {
    // wpctl displays its cubic level to two decimal places; allow half a display unit.
    private static final double CUBIC_TOLERANCE = 0.00501;

    static RuntimeStreamReadback capture(List<AudioNode> targets, String value, boolean toggleMute) {
        return capture(targets, value, toggleMute, -1);
    }

    static RuntimeStreamReadback capture(List<AudioNode> targets, String value, boolean toggleMute, long generation) {
        boolean muted = toggleMute && targets.stream().anyMatch(node -> !node.muted());
        if (toggleMute) return new RuntimeStreamReadback(List.copyOf(targets), null, muted, generation);
        String[] fields = value.split("\\t", 2);
        if (fields.length < 2) return null;
        try {
            double level = Double.parseDouble(fields[1]);
            if (!Double.isFinite(level)) return null;
            return new RuntimeStreamReadback(List.copyOf(targets), Math.max(0, Math.min(1, level)), false, generation);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    void verifyGeneration(long observed) {
        if (generation != -1 && (generation < 0 || generation != observed))
            throw new IllegalStateException("Stream readback unavailable: PipeWire connection changed");
    }

    void verify(PipeWireBackend.Snapshot observation) {
        if (!observation.connected()) throw new IllegalStateException("Stream readback unavailable: " + observation.error());
        if (targets.isEmpty()) throw new IllegalStateException("Stream readback unavailable: no target streams");
        for (AudioNode target : targets) {
            AudioNode actual = observation.streams().stream().filter(node -> node.id() == target.id()
                    && java.util.Objects.equals(node.name(), target.name())
                    && java.util.Objects.equals(node.mediaClass(), target.mediaClass())
                    && java.util.Objects.equals(node.applicationId(), target.applicationId())
                    && java.util.Objects.equals(node.processName(), target.processName())).findFirst().orElse(null);
            if (actual == null) throw new IllegalStateException("Stream readback unavailable for target " + target.id());
            if (actual.muted() != muted)
                throw new IllegalStateException("Stream " + target.id() + " mute readback mismatch: expected " + muted + ", observed " + actual.muted());
            if (volume != null && (!Double.isFinite(actual.volume()) || actual.volume() < 0 || actual.volume() > 1
                    || Math.abs(Math.cbrt(actual.volume()) - Math.cbrt(volume)) > CUBIC_TOLERANCE))
                throw new IllegalStateException("Stream " + target.id() + " volume readback mismatch: expected " + volume + ", observed " + actual.volume());
        }
    }
}
