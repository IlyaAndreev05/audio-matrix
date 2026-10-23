package io.github.ilyaandreev05.audiomatrix.audio;

import io.github.ilyaandreev05.audiomatrix.graph.EventSoundStudioSource;

/** Defines which live playback streams belong to ordinary application discovery and controls. */
public final class ApplicationStreamEligibility {
    private ApplicationStreamEligibility() {}

    public static boolean isEligibleApplicationStream(AudioNode stream) {
        if (stream == null || stream.mediaClass() == null
                || !stream.mediaClass().startsWith("Stream/Output/Audio")) return false;
        String name = stream.name() == null ? "" : stream.name();
        return !AudioMatrixOwnedStream.matches(stream)
                && !EventSoundStudioSource.APPLICATION_ID.equals(stream.applicationId())
                && !EventSoundStudioSource.APPLICATION_ID.equals(stream.processName())
                && !name.startsWith(CapturedStreamBinding.NODE_PREFIX)
                && !isTransientShellSound(stream);
    }

    static boolean isTransientShellSound(AudioNode stream) {
        String application = stream.applicationName() == null ? "" : stream.applicationName();
        String process = stream.processName() == null ? "" : stream.processName();
        return "gnome-shell".equalsIgnoreCase(application) || "gnome-shell".equalsIgnoreCase(process)
                || process.startsWith("gsd-") || "xdg-desktop-portal".equalsIgnoreCase(application);
    }
}
