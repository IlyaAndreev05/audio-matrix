package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Validates and invokes one bridge command independently of transport and confirmation. */
final class GnomeBridgeCommandExecutor {
    private static final Set<String> SUPPORTED_COMMANDS = Set.of("open",
            "set_output_volume","toggle_output_mute","set_source_volume","toggle_source_mute","set_app_volume","toggle_app_mute","set_stream_volume",
            "set_graph_output_volume","toggle_graph_output_mute","set_graph_gain","toggle_graph_gain_mute","toggle_stream_mute","set_mixer_input_volume","toggle_mixer_input_mute","set_quick_control","toggle_quick_control","set_quick_control_choice",
            "set_quick_group_collapsed","reorder_quick_group","set_quick_pin_view","set_quick_matrix_view","select_route","select_profile","set_channel_map",
            "setNodeVolume","setNodeMuted","setRouteEnabled","setBrowserTabMuted");

    private GnomeBridgeCommandExecutor() {}

    static CompletableFuture<GnomeControlBridge.CommandResult> execute(
            GnomeControlBridge.Command command,
            Function<GnomeControlBridge.Command, CompletableFuture<Void>> handler) {
        if (command.apiVersion() != GnomeControlBridge.API_VERSION)
            return CompletableFuture.completedFuture(new GnomeControlBridge.CommandResult(
                    "error", "Unsupported Audio Matrix D-Bus API version " + command.apiVersion()));
        if (!SUPPORTED_COMMANDS.contains(command.name()))
            return CompletableFuture.completedFuture(new GnomeControlBridge.CommandResult(
                    "error", "Unsupported command: " + command.name()));
        try {
            CompletableFuture<Void> action = handler.apply(command);
            if (action == null)
                return CompletableFuture.completedFuture(new GnomeControlBridge.CommandResult(
                        "error", "Command handler returned no completion result"));
            return action.handle((ignored, error) -> error == null
                    ? new GnomeControlBridge.CommandResult("ok", "Applied")
                    : new GnomeControlBridge.CommandResult("error", GnomeControlBridge.safeMessage(error)));
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(new GnomeControlBridge.CommandResult(
                    "error", GnomeControlBridge.safeMessage(error)));
        }
    }
}
