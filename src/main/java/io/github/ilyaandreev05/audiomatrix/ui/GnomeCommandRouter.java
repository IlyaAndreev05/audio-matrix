package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.gnome.GnomeCommandRevision;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeControlBridge;
import io.github.ilyaandreev05.audiomatrix.graph.GraphProfileLibrary;
import io.github.ilyaandreev05.audiomatrix.graph.GraphRouteLibrary;
import java.util.concurrent.CompletableFuture;

/** Routes the commands of the GNOME quick menu to the shell commands they trigger. */
final class GnomeCommandRouter {
    private GnomeCommandRouter() {}

    static CompletableFuture<Void> handle(AppShellUiPart29 shell, GnomeControlBridge.Command command) {
        if ("open".equals(command.name())) return shell.openGraphEditor();
        if (GnomeCommandRevision.isStale(command, shell.store.revision()))
            return CompletableFuture.failedFuture(new IllegalStateException("GNOME command is based on an old Audio Matrix revision"));
        return switch (command.name()) {
            case "select_route" -> shell.updateRouteConfigCommand(shell.t("Выбран маршрут из GNOME", "Route selected from GNOME"), config -> GraphRouteLibrary.activate(config, command.value()));
            case "select_profile" -> selectProfile(shell, command.value());
            case "set_quick_group_collapsed" -> shell.setGnomeQuickGroupCollapsed(command.value());
            case "reorder_quick_group" -> shell.updateRouteConfigCommand(shell.t("Изменён порядок групп GNOME", "GNOME group order changed"), config -> GnomeGroupOrder.apply(config, command.value()));
            case "set_quick_pin_view" -> shell.setGnomeQuickPinView(command.value());
            case "set_quick_matrix_view" -> shell.setGnomeMatrixView(command.value());
            case "set_output_volume" -> shell.setGnomeOutputLevel(command.value(),false);
            case "toggle_output_mute" -> shell.setGnomeOutputLevel(command.value(),true);
            case "set_source_volume" -> shell.setGnomeSourceLevel(command.value(),false);
            case "toggle_source_mute" -> shell.setGnomeSourceLevel(command.value(),true);
            case "set_app_volume" -> shell.setGnomeApplicationLevel(command.value(),false);
            case "toggle_app_mute" -> shell.setGnomeApplicationLevel(command.value(),true);
            case "set_stream_volume" -> shell.setGnomeStreamLevel(command.value(),false);
            case "toggle_stream_mute" -> shell.setGnomeStreamLevel(command.value(),true);
            case "set_mixer_input_volume" -> shell.setGnomeMixerInputLevel(command.value(),false);
            case "toggle_mixer_input_mute" -> shell.setGnomeMixerInputLevel(command.value(),true);
            case "set_quick_control" -> shell.setGnomeQuickControl(command.value(),false);
            case "toggle_quick_control" -> shell.setGnomeQuickControl(command.value(),true);
            case "set_quick_control_choice" -> shell.setGnomeQuickControlChoice(command.value());
            case "set_graph_gain" -> shell.updateRouteConfigCommand(shell.t("Изменить Gain из GNOME", "Change graph Gain from GNOME"), config -> GraphGainCommand.set(config, command.value(), false));
            case "toggle_graph_gain_mute" -> shell.updateRouteConfigCommand(shell.t("Изменить Mute Gain from GNOME", "Change graph Gain mute from GNOME"), config -> GraphGainCommand.set(config, command.value(), true));
            case "set_channel_map" -> shell.setGnomeChannelMap(command.value());
            default -> CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported command: "+command.name()));
        };
    }

    private static CompletableFuture<Void> selectProfile(AppShellUiPart29 shell, String value) {
        GnomeNodeCommandHandler.Payload payload = GnomeNodeCommandHandler.parse("select_profile", value);
        if (!(payload instanceof GnomeNodeCommandHandler.SelectProfile profile))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Profile command is malformed"));
        return shell.updateGraphRuntimeThenStore(config -> GraphProfileLibrary.activateProfile(config, profile.profileId()));
    }
}
