package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.Locale;

/** Detects application playback that must never be recaptured by the ordinary-app generator. */
public final class AudioMatrixOwnedStream {
    private static final String APPLICATION_ID = "io.github.ilyaandreev05.audiomatrix";
    private AudioMatrixOwnedStream() {}

    public static boolean matches(AudioNode node) {
        return startsOwnedName(node.name()) || APPLICATION_ID.equals(node.applicationId())
                || "audio matrix".equals(normalize(node.applicationName()))
                || isApplicationProcess(node.processName());
    }

    private static boolean startsOwnedName(String value) {
        return value != null && value.startsWith("audiomatrix_");
    }

    private static boolean isApplicationProcess(String value) {
        String process = normalize(value);
        return process.equals("audiomatrix") || process.equals("audio matrix") || process.equals("audiomatrixapp");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', ' ');
    }
}
