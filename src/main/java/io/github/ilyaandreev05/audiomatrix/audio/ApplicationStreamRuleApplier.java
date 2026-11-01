package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import io.github.ilyaandreev05.audiomatrix.audio.ApplicationStreamControlState.Control;


/** Applies saved application and captured-tab levels to live playback streams. */
public final class ApplicationStreamRuleApplier {
    private final VolumeSetter volumeSetter;
    private final MuteSetter muteSetter;
    private final ConcurrentMap<Integer, ApplicationStreamControlState> targets = new ConcurrentHashMap<>();

    public ApplicationStreamRuleApplier(PipeWireBackend backend) {
        this(backend::setStreamVolume, (id, muted, savedVolume) -> backend.setStreamMute(id, muted));
    }

    ApplicationStreamRuleApplier(VolumeSetter volumeSetter, MuteSetter muteSetter) {
        this.volumeSetter = volumeSetter;
        this.muteSetter = muteSetter;
    }

    @FunctionalInterface
    interface VolumeSetter {
        CompletableFuture<Void> setVolume(int nodeId, double volume);
    }

    @FunctionalInterface
    interface MuteSetter {
        CompletableFuture<Void> setMute(int nodeId, boolean muted, double savedVolume);
    }

    public synchronized CompletableFuture<Void> setManualVolume(AudioNode stream, double linearVolume, String intentId) {
        if (stream == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Playback stream is required"));
        if (intentId == null || intentId.isBlank())
            return CompletableFuture.failedFuture(new IllegalArgumentException("Manual volume intent id is required"));
        if (!Double.isFinite(linearVolume) || linearVolume < 0 || linearVolume > 1)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Manual stream volume must be between zero and one"));

        return target(stream).manual(stream, linearVolume, intentId);
    }

    public synchronized void clearManualVolume(AudioNode stream, String intentId) {
        if (stream == null || intentId == null || intentId.isBlank()) return;
        ApplicationStreamControlState state = targets.get(stream.id());
        if (state != null) state.clear(stream, intentId);
    }

    public CompletableFuture<Void> apply(PipeWireBackend.Snapshot current, List<Map<String, Object>> rules,
                                         BiConsumer<AudioNode, Throwable> onFailure) {
        return apply(current, rules, Map.of(), false, onFailure);
    }

    public CompletableFuture<Void> apply(PipeWireBackend.Snapshot current, List<Map<String, Object>> rules,
                                         Map<String, CapturedStreamBinding> bindings,
                                         BiConsumer<AudioNode, Throwable> onFailure) {
        return apply(current, rules, bindings, false, onFailure);
    }

    public synchronized CompletableFuture<Void> apply(PipeWireBackend.Snapshot current, List<Map<String, Object>> rules,
                                                      Map<String, CapturedStreamBinding> bindings, boolean force,
                                                      BiConsumer<AudioNode, Throwable> onFailure) {
        if (current == null) return CompletableFuture.completedFuture(null);
        if (!current.connected()) {
            targets.forEach((id, state) -> {
                if (state.disappeared()) targets.remove(id, state);
            });
            return CompletableFuture.completedFuture(null);
        }
        Map<String, CapturedStreamBinding> capturedBindings = bindings == null ? Map.of() : bindings;
        for (Map.Entry<String, CapturedStreamBinding> entry : capturedBindings.entrySet()) {
            if (entry.getValue() == null || !entry.getKey().equals(entry.getValue().nodeName()))
                throw new IllegalArgumentException("Captured binding map keys must equal their exact nodeName");
        }

        Map<String, Map<String, Object>> rulesById = CapturedStreamRuleResolver.indexRules(rules);
        Set<Integer> liveIds = new HashSet<>();
        for (AudioNode stream : current.streams()) {
            liveIds.add(stream.id());
        }
        targets.forEach((id, state) -> {
            if (!liveIds.contains(id) && state.disappeared()) targets.remove(id, state);
        });

        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (AudioNode stream : current.streams()) {
            CapturedStreamBinding binding = capturedBindings.get(stream.name());
            Control desired = binding == null ? null
                    : capturedControl(binding, stream, current.streams(), rulesById);
            if (desired == null && isCaptureOrOwned(stream)) continue;
            if (desired == null) {
                Map<String, Object> rule = ApplicationRuleMatcher.find(rules, stream);
                if (rule != null) desired = new Control(ruleGain(rule), Boolean.TRUE.equals(rule.get("mute")));
            }

            if (desired == null && !targets.containsKey(stream.id())) continue;
            writes.add(target(stream).reconcile(stream, desired, current.capturedAtMillis(), force)
                    .whenComplete((ignored, error) -> {
                if (error != null && onFailure != null) onFailure.accept(stream, unwrap(error));
            }));
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    private ApplicationStreamControlState target(AudioNode stream) {
        return targets.computeIfAbsent(stream.id(), ignored -> new ApplicationStreamControlState(volumeSetter, muteSetter));
    }

    private static Control capturedControl(CapturedStreamBinding binding, AudioNode captureStream,
                                                   List<AudioNode> streams,
                                                   Map<String, Map<String, Object>> rulesById) {
        Map<String, Object> rule = CapturedStreamRuleResolver.findRule(binding, captureStream, streams, rulesById);
        if (rule == null) return null;
        double appGain = ruleGain(rule);
        double effectiveGain = appGain * binding.tabGain();
        boolean muted = Boolean.TRUE.equals(rule.get("mute")) || binding.tabMuted();
        return new Control(effectiveGain, muted);
    }

    private static double ruleGain(Map<String, Object> rule) {
        Object configured = rule.get("volume");
        double gain = configured instanceof Number number ? number.doubleValue() : 1.0;
        return Double.isFinite(gain) ? Math.max(0.0, Math.min(1.0, gain)) : 1.0;
    }


    private static boolean isCaptureOrOwned(AudioNode stream) {
        return AudioMatrixOwnedStream.matches(stream)
                || stream.name().startsWith(CapturedStreamBinding.NODE_PREFIX);
    }


    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null)
            current = current.getCause();
        return current;
    }
}
