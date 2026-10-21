package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeCommandRevision;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeControlBridge;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GnomeNodeRevisionBehaviorTest {
    @TempDir Path temp;

    @Test void staleVolumeRebasesAcrossMuteButConflictsAfterTheSameVolumeChanges() throws Exception {
        try (ConfigStore store = new ConfigStore(temp.resolve("revisions.json"))) {
            store.save(DefaultConfig.create());
            long baselineRevision = store.revision();
            Map<String, Object> baseline = store.snapshotAtRevision(baselineRevision);
            String nodeId = GraphCommonVibro.VIBRO_GAIN_ID;
            GnomeNodeCommandHandler.PropertyKey key =
                    new GnomeNodeCommandHandler.PropertyKey("main", nodeId, "", "volume");
            PipeWireBackend.Snapshot offline = PipeWireBackend.Snapshot.offline("Not connected");
            Object baselineProperty = GnomeGraphNodeCommandExecutor.propertyValue(
                    baseline, key, null, offline, List.of());
            GnomeNodeCommandHandler.Target target = new GnomeNodeCommandHandler.Target("main", nodeId, "");
            GnomeControlBridge.Command command = new GnomeControlBridge.Command(5, "slider:drag-1",
                    Long.toString(baselineRevision), "setNodeVolume",
                    GnomeNodeCommandHandler.encodeVolume(target, 0.4));

            store.updateAsync(config -> GraphNodeCommand.setMuted(config, "main", nodeId, true)).join();
            Object volumeAfterMute = GnomeGraphNodeCommandExecutor.propertyValue(
                    store.snapshot(), key, null, offline, List.of());
            assertDoesNotThrow(() -> GnomeCommandRevision.requireAbsoluteSetterRevision(command,
                    store.revision(), store.snapshotAtRevision(baselineRevision) != null,
                    baselineProperty, volumeAfterMute, false));

            store.updateAsync(config -> GraphNodeCommand.setVolume(config, "main", nodeId, 0.25)).join();
            Object changedVolume = GnomeGraphNodeCommandExecutor.propertyValue(
                    store.snapshot(), key, null, offline, List.of());
            assertThrows(IllegalStateException.class, () -> GnomeCommandRevision.requireAbsoluteSetterRevision(
                    command, store.revision(), store.snapshotAtRevision(baselineRevision) != null,
                    baselineProperty, changedVolume, false));
            assertThrows(IllegalStateException.class, () -> GnomeCommandRevision.requireAbsoluteSetterRevision(
                    command, store.revision(), false, baselineProperty, baselineProperty, true));
            assertEquals("slider", GnomeCommandRevision.clientId(command));
        }
    }
}
