package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.Set;

/** Rejects stale structural GNOME edits while allowing continuous level updates to coalesce. */
public final class GnomeCommandRevision {
    private static final Set<String> REVISIONED = Set.of(
            "select_route", "set_quick_group_collapsed", "set_quick_control",
            "toggle_quick_control", "set_quick_control_choice", "set_mode", "set_channel_map");

    private GnomeCommandRevision() {}

    public static boolean isStale(GnomeControlBridge.Command command, long currentRevision) {
        return REVISIONED.contains(command.name()) && !command.revision().isBlank()
                && !Long.toString(currentRevision).equals(command.revision());
    }
}
