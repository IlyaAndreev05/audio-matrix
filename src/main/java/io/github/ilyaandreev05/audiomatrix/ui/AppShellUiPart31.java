package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.ThemeManager;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeControlBridge;
import java.util.concurrent.CompletableFuture;

/**
 * GNOME graph commands of the shell.
 *
 * <p>The dispatcher of the graph commands and the mixer and Matrix commands are implemented by
 * focused helpers of the same package.</p>
 */
abstract class AppShellUiPart31 extends AppShellUiPart30 {
    private final GnomeGraphCommandDispatcher graphCommands = new GnomeGraphCommandDispatcher(this);

    protected AppShellUiPart31(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    @Override
    protected CompletableFuture<Void> handleGnomeCommand(GnomeControlBridge.Command command) {
        return graphCommands.handle(command, this::fallbackGnomeCommand);
    }

    private CompletableFuture<Void> fallbackGnomeCommand(GnomeControlBridge.Command command) {
        return super.handleGnomeCommand(command);
    }

    @Override
    protected void updateGraphMixerInput(String routeId, String nodeId, String port, Double gain, Boolean muted) {
        GnomeGraphMixerCommands.updateMixerInput(this, routeId, nodeId, port, gain, muted);
    }

    @Override
    protected CompletableFuture<Void> setGnomeMixerInputLevel(String value, boolean toggleMute) {
        return GnomeGraphMixerCommands.setMixerInputLevel(this, value, toggleMute);
    }

    @Override
    protected CompletableFuture<Void> setGnomeMatrixView(String value) {
        return GnomeGraphMixerCommands.setMatrixView(this, value);
    }
}
