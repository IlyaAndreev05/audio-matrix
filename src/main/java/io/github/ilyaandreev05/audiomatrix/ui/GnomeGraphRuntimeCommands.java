package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.gnome.GnomeControlBridge;
import javafx.application.Platform;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Runtime graph commands that the GNOME editor and the graph inspector share. */
final class GnomeGraphRuntimeCommands {
    private GnomeGraphRuntimeCommands() {}

    static CompletableFuture<Void> updateGraphRuntimeThenStore(AppShellUiPart29 shell, java.util.function.Consumer<Map<String, Object>> change) {
        return GnomeNodeCommandHandler.applyRuntimeThenStore(shell.store, change,
                shell.routeManager.active() && shell.snapshot.connected(),
                candidate -> shell.applyRuntimeGraph(candidate, shell.snapshot, false, false, true),
                candidate -> shell.applyRuntimeGraph(candidate, shell.snapshot, false, false, false),
                error -> Platform.runLater(() -> shell.setNotice(shell.t("Настройка применена, но не сохранена: ",
                        "Runtime setting applied but could not be saved: ") + AppShellBase.rootMessage(error), true)),
                () -> Platform.runLater(() -> shell.setNotice(shell.t("Настройка применена.", "Setting applied."), false)));
    }

    static CompletableFuture<Void> setGraphNodeVolume(AppShellUiPart29 shell, String routeId, String nodeId,
            String instanceKey, double value) {
        GnomeNodeCommandHandler.Target target =
                new GnomeNodeCommandHandler.Target(routeId, nodeId, instanceKey);
        return shell.handleGnomeCommand(new GnomeControlBridge.Command(GnomeControlBridge.API_VERSION,
                "graph-editor:" + java.util.UUID.randomUUID(), Long.toString(shell.store.revision()),
                "setNodeVolume", GnomeNodeCommandHandler.encodeVolume(target, value)));
    }

    static CompletableFuture<Void> setGraphNodeMuted(AppShellUiPart29 shell, String routeId, String nodeId,
            String instanceKey, boolean muted) {
        GnomeNodeCommandHandler.Target target =
                new GnomeNodeCommandHandler.Target(routeId, nodeId, instanceKey);
        return shell.handleGnomeCommand(new GnomeControlBridge.Command(GnomeControlBridge.API_VERSION,
                "graph-editor:" + java.util.UUID.randomUUID(), Long.toString(shell.store.revision()),
                "setNodeMuted", GnomeNodeCommandHandler.encodeMuted(target, muted)));
    }
}
