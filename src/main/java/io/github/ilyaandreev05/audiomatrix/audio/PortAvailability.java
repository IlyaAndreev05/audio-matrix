package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.Locale;

/** PipeWire's confidence about whether a physical card port can currently be selected. */
public enum PortAvailability {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN;

    static PortAvailability parse(String value) {
        if (value == null) return UNKNOWN;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "yes" -> AVAILABLE;
            case "no" -> UNAVAILABLE;
            default -> UNKNOWN;
        };
    }

    String propertyValue() {
        return switch (this) {
            case AVAILABLE -> "yes";
            case UNAVAILABLE -> "no";
            case UNKNOWN -> "unknown";
        };
    }
}
