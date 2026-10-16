package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/** Applies saved volume and mute rules to matching live playback streams. */
public final class ApplicationStreamRuleApplier {
    private final PipeWireBackend backend;
    private final ConcurrentMap<Integer, String> appliedRules = new ConcurrentHashMap<>();

    public ApplicationStreamRuleApplier(PipeWireBackend backend) {
        this.backend = backend;
    }

    public void apply(PipeWireBackend.Snapshot current, List<Map<String, Object>> rules,
                      BiConsumer<AudioNode, Throwable> onFailure) {
        if (current == null || !current.connected()) return;

        Set<Integer> liveIds = current.streams().stream().map(AudioNode::id).collect(Collectors.toSet());
        appliedRules.keySet().removeIf(id -> !liveIds.contains(id));
        for (AudioNode stream : current.streams()) {
            Map<String, Object> rule = ApplicationRuleMatcher.find(rules, stream);
            if (rule == null) {
                appliedRules.remove(stream.id());
                continue;
            }

            String ruleId = String.valueOf(rule.getOrDefault("id", ""));
            Object configuredVolume = rule.get("volume");
            double volume = configuredVolume instanceof Number number ? number.doubleValue() : 1.0;
            volume = Double.isFinite(volume) ? Math.max(0, Math.min(1, volume)) : 1.0;
            boolean muted = Boolean.TRUE.equals(rule.get("mute"));
            String signature = ruleId + "|" + volume + "|" + muted;
            if (signature.equals(appliedRules.get(stream.id()))) continue;
            appliedRules.put(stream.id(), signature);

            CompletableFuture<?>[] changes = new CompletableFuture<?>[] {
                    !Double.isFinite(stream.volume()) || Math.abs(stream.volume() - volume) > 0.01
                            ? backend.setVolume(stream.id(), volume) : CompletableFuture.completedFuture(null),
                    stream.muted() != muted
                            ? backend.setMute(stream.id(), muted) : CompletableFuture.completedFuture(null)
            };
            CompletableFuture.allOf(changes).whenComplete((ignored, error) -> {
                if (error != null && appliedRules.remove(stream.id(), signature) && onFailure != null)
                    onFailure.accept(stream, error);
            });
        }
    }
}
