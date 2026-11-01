package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Headless access to the production OSD-to-config decision, not a JavaFX/OSD integration test. */
public final class SystemMasterLiveSmokeSupport {
    private SystemMasterLiveSmokeSupport() {}

    public static CompletableFuture<Void> mirrorObservedSink(ConfigStore store, AudioNode sink) {
        return store.updateAsync(config -> {
            @SuppressWarnings("unchecked") Map<String, Object> mixer = (Map<String, Object>) config.get("mixer");
            SystemMasterSync.Decision decision = SystemMasterSync.compare(sink.volume(), sink.muted(),
                    ((Number) mixer.get("global_volume")).doubleValue(), Boolean.TRUE.equals(mixer.get("global_mute")));
            if (decision == null) return;
            if (decision.writeVolume()) mixer.put("global_volume", Math.round(decision.volume() * 100.0) / 100.0);
            if (decision.writeMute()) mixer.put("global_mute", decision.muted());
        });
    }
}
