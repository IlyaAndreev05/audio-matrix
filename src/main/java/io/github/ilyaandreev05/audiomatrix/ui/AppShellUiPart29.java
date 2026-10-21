package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.ThemeManager;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeControlBridge;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Commands of the GNOME quick menu of the shell.
 *
 * <p>The command router, the level commands, the quick menu commands and the runtime graph
 * commands are implemented by focused helpers of the same package.</p>
 */
abstract class AppShellUiPart29 extends AppShellUiPart28 {
    protected AppShellUiPart29(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    public CompletableFuture<Void> openGraphEditor() {
        return GnomeWindowOpener.open(getScene(), () -> showPage(Page.GRAPH));
    }

    @Override
    protected CompletableFuture<Void> handleGnomeCommand(GnomeControlBridge.Command command) {
        return GnomeCommandRouter.handle(this, command);
    }

    protected CompletableFuture<Void> updateGraphRuntimeThenStore(java.util.function.Consumer<Map<String, Object>> change) {
        return GnomeGraphRuntimeCommands.updateGraphRuntimeThenStore(this, change);
    }

    @Override
    protected CompletableFuture<Void> setGraphNodeVolume(String routeId, String nodeId,
            String instanceKey, double value) {
        return GnomeGraphRuntimeCommands.setGraphNodeVolume(this, routeId, nodeId, instanceKey, value);
    }

    @Override
    protected CompletableFuture<Void> setGraphNodeMuted(String routeId, String nodeId,
            String instanceKey, boolean muted) {
        return GnomeGraphRuntimeCommands.setGraphNodeMuted(this, routeId, nodeId, instanceKey, muted);
    }

    protected CompletableFuture<Void> setGnomeQuickGroupCollapsed(String value) {
        return GnomeQuickCommands.setQuickGroupCollapsed(this, value);
    }

    @Override
    protected CompletableFuture<Void> setGnomeQuickControl(String value, boolean toggle) {
        return GnomeQuickCommands.setQuickControl(this, value, toggle);
    }

    @Override
    protected CompletableFuture<Void> setGnomeQuickControlChoice(String value) {
        return GnomeQuickCommands.setQuickControlChoice(this, value);
    }

    @Override
    protected CompletableFuture<Void> setGnomeOutputLevel(String value, boolean toggleMute) {
        return GnomeLevelCommands.setOutputLevel(this, value, toggleMute);
    }

    protected CompletableFuture<Void> setGnomeSourceLevel(String value, boolean toggleMute) {
        return GnomeLevelCommands.setSourceLevel(this, value, toggleMute);
    }

    @Override
    protected CompletableFuture<Void> setGnomeApplicationLevel(String value, boolean toggleMute) {
        return GnomeLevelCommands.setApplicationLevel(this, value, toggleMute);
    }

    @Override
    protected CompletableFuture<Void> setGnomeStreamLevel(String value, boolean toggleMute) {
        return GnomeLevelCommands.setStreamLevel(this, value, toggleMute);
    }

    @Override
    protected Map<String, Object> ensureGnomeApplicationRule(Map<String, Object> config, String groupId) {
        return GnomeApplicationRuleResolver.ensure(config, groupId);
    }

    @Override
    protected CompletableFuture<Void> setGnomeChannelMap(String value) {
        return GnomeQuickCommands.setChannelMap(this, value);
    }
}
