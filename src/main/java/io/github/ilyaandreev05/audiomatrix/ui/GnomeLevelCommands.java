package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeGraphOutputGain;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeGraphSourceGain;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeOutputState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Volume and mute commands of the GNOME quick menu for outputs, sources and streams. */
final class GnomeLevelCommands {
    private GnomeLevelCommands() {}

    static CompletableFuture<Void> setOutputLevel(AppShellUiPart29 shell, String value, boolean toggleMute) {
        String[] fields = value.split("\\t", 2);
        if (fields[0].isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("Output name is missing"));
        AudioNode node = GnomeOutputState.resolve(shell.physicalSinks(), fields[0], AppShellBase.maps(shell.store.snapshot().get("devices")));
        if (node == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Output is unavailable"));
        GnomeGraphOutputGain.Target graphTarget = GnomeGraphOutputGain.find(shell.store.snapshot(), shell.snapshot, node);
        if (toggleMute) {
            if (graphTarget != null) return shell.updateRouteConfigCommand(
                    shell.t("Изменить громкость выхода в графе", "Change graph output level"),
                    config -> GnomeGraphOutputGain.set(config, graphTarget, null, true));
            boolean muted = !node.muted();
            return shell.backend.setMute(node.id(), muted).thenCompose(ignored -> shell.store.updateAsync(config -> {
                shell.ensureDevice(config, node).put("mute", muted);
            })).thenRun(shell::refreshBackend);
        }
        if (fields.length < 2) return CompletableFuture.failedFuture(new IllegalArgumentException("Output volume is missing"));
        double level;
        try { level = Double.parseDouble(fields[1]); }
        catch (NumberFormatException invalid) { return CompletableFuture.failedFuture(new IllegalArgumentException("Output volume is invalid")); }
        if (!Double.isFinite(level)) return CompletableFuture.failedFuture(new IllegalArgumentException("Output volume is invalid"));
        double target = Math.max(0, Math.min(1, level));
        if (graphTarget != null) return shell.updateRouteConfigCommand(
                shell.t("Изменить громкость выхода в графе", "Change graph output level"),
                config -> GnomeGraphOutputGain.set(config, graphTarget, target, false));
        CompletableFuture<Void> unmute = node.muted() ? shell.backend.setMute(node.id(), false) : CompletableFuture.completedFuture(null);
        return unmute.thenCompose(ignored -> shell.backend.setVolume(node.id(), target)).thenCompose(ignored -> shell.store.updateAsync(config -> {
            Map<String, Object> device = shell.ensureDevice(config, node);
            device.put("volume", target);
            device.put("mute", false);
        })).thenRun(shell::refreshBackend);
    }

    static CompletableFuture<Void> setSourceLevel(AppShellUiPart29 shell, String value, boolean toggleMute) {
        String[] fields = value.split("\\t", 4);
        if (fields.length < 3 || fields[0].isBlank() || fields[1].isBlank() || fields[2].isBlank())
            return CompletableFuture.failedFuture(new IllegalArgumentException("Source Gain reference is malformed"));
        Double level = null;
        if (!toggleMute) {
            if (fields.length < 4) return CompletableFuture.failedFuture(new IllegalArgumentException("Source volume is missing"));
            try { level = Double.parseDouble(fields[3]); }
            catch (NumberFormatException invalid) { return CompletableFuture.failedFuture(new IllegalArgumentException("Source volume is invalid")); }
            if (!Double.isFinite(level)) return CompletableFuture.failedFuture(new IllegalArgumentException("Source volume is invalid"));
        }
        Double target = level;
        GnomeGraphSourceGain.Target source = new GnomeGraphSourceGain.Target(fields[0], fields[1], fields[2]);
        return shell.updateRouteConfigCommand(shell.t("Изменить громкость источника графа", "Change graph source level"),
                config -> GnomeGraphSourceGain.set(config, source, target, toggleMute));
    }

    static CompletableFuture<Void> setApplicationLevel(AppShellUiPart29 shell, String value, boolean toggleMute) {
        String[] fields = value.split("\\t", 2);
        if (fields[0].isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("Application id is missing"));
        String key = fields[0];
        List<AudioNode> streams = shell.snapshot.streams().stream().filter(stream -> matchesApplication(key, stream)).toList();
        if (streams.isEmpty()) return CompletableFuture.failedFuture(new IllegalArgumentException("Application stream is unavailable"));
        boolean muteTarget = toggleMute && streams.stream().anyMatch(stream -> !stream.muted());
        double targetLevel = 0;
        if (!toggleMute) {
            if (fields.length < 2) return CompletableFuture.failedFuture(new IllegalArgumentException("Application volume is missing"));
            try { targetLevel = Double.parseDouble(fields[1]); }
            catch (NumberFormatException invalid) { return CompletableFuture.failedFuture(new IllegalArgumentException("Application volume is invalid")); }
            if (!Double.isFinite(targetLevel)) return CompletableFuture.failedFuture(new IllegalArgumentException("Application volume is invalid"));
            targetLevel = Math.max(0, Math.min(1, targetLevel));
        }
        final double requestedLevel = targetLevel;
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (AudioNode stream : streams)
            work.add(toggleMute ? shell.backend.setMute(stream.id(), muteTarget) : setUnmutedVolume(shell, stream, requestedLevel));
        return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new))
                .thenCompose(ignored -> key.startsWith("stream:") ? CompletableFuture.completedFuture(null)
                        : shell.store.updateAsync(config -> saveApplicationLevel(shell, config, key, toggleMute, muteTarget, requestedLevel)))
                .thenRun(shell::refreshBackend);
    }

    private static boolean matchesApplication(String key, AudioNode stream) {
        String app = stream.applicationId() == null ? "" : stream.applicationId();
        String process = stream.processName() == null ? "" : stream.processName();
        return key.equals("app:" + app) || key.equals("process:" + process) || key.equals("stream:" + stream.name());
    }

    private static CompletableFuture<Void> setUnmutedVolume(AppShellUiPart29 shell, AudioNode stream, double level) {
        CompletableFuture<Void> unmute = stream.muted() ? shell.backend.setMute(stream.id(), false) : CompletableFuture.completedFuture(null);
        return unmute.thenCompose(ignored -> shell.backend.setVolume(stream.id(), level));
    }

    private static void saveApplicationLevel(AppShellUiPart29 shell, Map<String, Object> config, String key, boolean mute, boolean muteTarget, double level) {
        Map<String, Object> rule = shell.ensureGnomeApplicationRule(config, key);
        if (mute) rule.put("mute", muteTarget);
        else {
            rule.put("volume", level);
            rule.put("mute", false);
        }
    }

    static CompletableFuture<Void> setStreamLevel(AppShellUiPart29 shell, String value, boolean toggleMute) {
        String[] fields = value.split("\\t", 2);
        if (fields.length < 1) return CompletableFuture.failedFuture(new IllegalArgumentException("Stream id is missing"));
        int id;
        try { id = Integer.parseInt(fields[0]); }
        catch (NumberFormatException error) { return CompletableFuture.failedFuture(new IllegalArgumentException("Stream id is invalid")); }
        AudioNode stream = shell.snapshot.streams().stream().filter(node -> node.id() == id).findFirst().orElse(null);
        if (stream == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Stream is unavailable"));
        if (toggleMute) return shell.backend.setMute(id, !stream.muted()).thenRun(shell::refreshBackend);
        if (fields.length < 2) return CompletableFuture.failedFuture(new IllegalArgumentException("Stream volume is missing"));
        double level;
        try { level = Double.parseDouble(fields[1]); }
        catch (NumberFormatException error) { return CompletableFuture.failedFuture(new IllegalArgumentException("Stream volume is invalid")); }
        if (!Double.isFinite(level)) return CompletableFuture.failedFuture(new IllegalArgumentException("Stream volume is invalid"));
        return setUnmutedVolume(shell, stream, Math.max(0, Math.min(1, level))).thenRun(shell::refreshBackend);
    }
}
