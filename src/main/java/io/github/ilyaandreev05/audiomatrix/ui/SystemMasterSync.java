package io.github.ilyaandreev05.audiomatrix.ui;

/**
 * Pure decision helpers for mirroring the observed system sink into {@code mixer.global_volume}
 * and {@code mixer.global_mute}.
 *
 * <p>The observed mute is the authoritative one. The configured mute is only the saved target; it
 * must never be written back as if it had been observed. A late pre-command snapshot is ignored
 * until the runtime lane sees one captured after the last command write.</p>
 */
final class SystemMasterSync {
    static final double VOLUME_TOLERANCE = 0.005;

    private SystemMasterSync() {}

    /** The compared fields; {@code writeVolume}/{@code writeMute} are false for an equal state. */
    record Decision(boolean writeVolume, double volume, boolean writeMute, boolean muted) {
        boolean changed() { return writeVolume || writeMute; }
    }

    static boolean usableVolume(double observedVolume) {
        return Double.isFinite(observedVolume);
    }

    static boolean shouldDefer(boolean commandInFlight, long capturedAtMillis, long lastCommandWriteMillis) {
        return commandInFlight || capturedAtMillis <= lastCommandWriteMillis;
    }

    static double clamp(double volume) {
        return Math.max(0, Math.min(1, volume));
    }

    /** Returns {@code null} when the saved configuration already matches the observation. */
    static Decision compare(double observedVolume, boolean observedMuted,
                            double configuredVolume, boolean configuredMuted) {
        if (!usableVolume(observedVolume)) return null;
        double volume = clamp(observedVolume);
        boolean writeVolume = !Double.isFinite(configuredVolume)
                || Math.abs(volume - clamp(configuredVolume)) > VOLUME_TOLERANCE;
        boolean writeMute = observedMuted != configuredMuted;
        if (!writeVolume && !writeMute) return null;
        return new Decision(writeVolume, volume, writeMute, observedMuted);
    }
}
