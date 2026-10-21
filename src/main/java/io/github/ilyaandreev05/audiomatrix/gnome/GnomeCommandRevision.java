package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.Set;

/** Rejects stale structural GNOME edits while allowing continuous level updates to coalesce. */
public final class GnomeCommandRevision {
    private static final Set<String> REVISIONED = Set.of(
            "select_route", "select_profile", "setRouteEnabled",
            "set_quick_group_collapsed", "reorder_quick_group", "set_quick_pin_view", "set_quick_control",
            "toggle_quick_control", "set_quick_control_choice", "set_graph_output_volume",
            "set_graph_gain", "toggle_graph_gain_mute",
            "toggle_graph_output_mute", "set_mode", "set_channel_map");
    private static final Set<String> EXACT_REVISIONED = Set.of(
            "select_route", "select_profile", "set_quick_group_collapsed", "reorder_quick_group",
            "setRouteEnabled");

    private GnomeCommandRevision() {}

    /** Applies exact revision checks to structural edits and preserves legacy checks for other controls. */
    public static boolean isStale(GnomeControlBridge.Command command, long currentRevision) {
        if (requiresExactRevision(command.name()))
            return !Long.toString(currentRevision).equals(command.revision());
        return REVISIONED.contains(command.name()) && !command.revision().isBlank()
                && !Long.toString(currentRevision).equals(command.revision());
    }

    public static boolean requiresExactRevision(String name) {
        return EXACT_REVISIONED.contains(name);

    }
    public static long commandRevision(GnomeControlBridge.Command command) {
        if (command.revision().isBlank())
            throw new IllegalArgumentException("GNOME command revision is required");
        try {
            long revision = Long.parseLong(command.revision());
            if (revision < 0 || !Long.toString(revision).equals(command.revision()))
                throw new NumberFormatException();
            return revision;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("GNOME command revision is invalid", invalid);
        }
    }

    /** Unprefixed request IDs are isolated to that request and cannot supersede one another. */
    public static String clientId(GnomeControlBridge.Command command) {
        String requestId = command.requestId();
        int separator = requestId.indexOf(':');
        return separator > 0 ? requestId.substring(0, separator) : requestId;
    }
    public static void requireAbsoluteSetterRevision(GnomeControlBridge.Command command, long currentRevision,
            boolean baselineRetained, Object baselineProperty, Object currentProperty,
            boolean newerSameClientIntent) {
        long commandRevision = commandRevision(command);
        if (commandRevision == currentRevision) return;
        if (commandRevision > currentRevision)
            throw new IllegalStateException("GNOME command is based on a future Audio Matrix revision");
        if (!baselineRetained)
            throw new IllegalStateException("GNOME command baseline is no longer available");
        if (!java.util.Objects.equals(baselineProperty, currentProperty) && !newerSameClientIntent)
            throw new IllegalStateException("GNOME command conflicts with a newer value for this target");
    }
}

