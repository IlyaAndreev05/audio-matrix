package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Confirms a command after its runtime action and releases failed persist-first application edits. */
final class GnomeCommandStateConfirmation {
    private GnomeCommandStateConfirmation() {}

    static CompletableFuture<GnomeControlBridge.CommandResult> confirm(
            GnomeControlBridge.Command command, GnomeControlBridge.CommandResult result,
            Function<GnomeControlBridge.Command, CompletableFuture<Void>> confirmState,
            Function<GnomeControlBridge.Command, CompletableFuture<Void>> releaseState) {
        // With a prior observation, the error ACK republishes it; never apply an unpersisted level.
        if ("error".equals(result.status()) && isPersistedApplicationLevel(command)) {
            try {
                CompletableFuture<Void> released = releaseState.apply(command);
                if (released == null) throw new IllegalStateException("Command release returned no completion result");
                return released.handle((ignored, error) -> error == null ? result
                        : new GnomeControlBridge.CommandResult("error",
                                result.message() + "; could not release command state: "
                                        + GnomeControlBridge.safeMessage(error)));
            } catch (RuntimeException error) {
                return CompletableFuture.completedFuture(new GnomeControlBridge.CommandResult("error",
                        result.message() + "; could not release command state: "
                                + GnomeControlBridge.safeMessage(error)));
            }
        }
        try {
            CompletableFuture<Void> confirmed = confirmState.apply(command);
            if (confirmed == null) throw new IllegalStateException("State publisher returned no completion result");
            return confirmed.handle((ignored, error) -> error == null ? result
                    : new GnomeControlBridge.CommandResult("error",
                            "Could not confirm command state: " + GnomeControlBridge.safeMessage(error)));
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(new GnomeControlBridge.CommandResult("error",
                    "Could not confirm command state: " + GnomeControlBridge.safeMessage(error)));
        }
    }

    private static boolean isPersistedApplicationLevel(GnomeControlBridge.Command command) {
        if (!"set_app_volume".equals(command.name()) && !"toggle_app_mute".equals(command.name())) return false;
        String value = command.value();
        if (value == null) return false;
        int separator = value.indexOf('\t');
        String target = separator < 0 ? value : value.substring(0, separator);
        return target.startsWith("app:") || target.startsWith("process:");
    }
}
