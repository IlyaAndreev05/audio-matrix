package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/** Applies saved volume and mute rules to matching live playback streams. */
public final class ApplicationStreamRuleApplier {
    private final BiFunction<Integer, Double, CompletableFuture<Void>> volumeWriter;
    private final BiFunction<Integer, Boolean, CompletableFuture<Void>> muteWriter;
    private final ConcurrentMap<Integer, String> appliedRules = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, CompletableFuture<Void>> inFlightRules = new ConcurrentHashMap<>();

    public ApplicationStreamRuleApplier(PipeWireBackend backend) {
        this(backend::setVolume, backend::setMute);
    }

    ApplicationStreamRuleApplier(BiFunction<Integer, Double, CompletableFuture<Void>> volumeWriter,
                                 BiFunction<Integer, Boolean, CompletableFuture<Void>> muteWriter) {
        this.volumeWriter = volumeWriter;
        this.muteWriter = muteWriter;
    }

    public CompletableFuture<Void> apply(PipeWireBackend.Snapshot current, List<Map<String, Object>> rules,
                                          BiConsumer<AudioNode, Throwable> onFailure) {
        return apply(current, rules, Map.of(), false, onFailure);
    }

    public CompletableFuture<Void> apply(PipeWireBackend.Snapshot current, List<Map<String, Object>> rules,
                                          Map<String, PipeWireRouteTypes.CapturedStreamBinding> capturedBindings,
                                          BiConsumer<AudioNode, Throwable> onFailure) {
        return apply(current, rules, capturedBindings, false, onFailure);
    }

    public CompletableFuture<Void> apply(PipeWireBackend.Snapshot current, List<Map<String, Object>> rules,
                                          Map<String, PipeWireRouteTypes.CapturedStreamBinding> capturedBindings,
                                          boolean force, BiConsumer<AudioNode, Throwable> onFailure) {
        return applyInternal(current, rules, capturedBindings, force, onFailure);
    }

    private synchronized CompletableFuture<Void> applyInternal(
            PipeWireBackend.Snapshot current, List<Map<String, Object>> rules,
            Map<String, PipeWireRouteTypes.CapturedStreamBinding> capturedBindings,
            boolean force, BiConsumer<AudioNode, Throwable> onFailure) {
        if (current == null || !current.connected()) return CompletableFuture.completedFuture(null);
        Map<String, PipeWireRouteTypes.CapturedStreamBinding> bindings =
                capturedBindings == null ? Map.of() : capturedBindings;

        Set<Integer> liveIds = current.streams().stream().map(AudioNode::id).collect(Collectors.toSet());
        appliedRules.keySet().removeIf(id -> !liveIds.contains(id));
        inFlightRules.keySet().removeIf(id -> !liveIds.contains(id));
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (AudioNode stream : current.streams()) {
            PipeWireRouteTypes.CapturedStreamBinding binding = bindings.get(stream.name());
            if (binding != null && !stream.name().equals(binding.nodeName())) {
                pending.add(failed(stream, new IllegalArgumentException("Captured stream name did not match its exact binding"), onFailure));
                continue;
            }
            if (binding == null && AudioMatrixOwnedStream.matches(stream)) {
                appliedRules.remove(stream.id());
                inFlightRules.remove(stream.id());
                continue;
            }
            Map<String, Object> rule = binding == null ? ApplicationRuleMatcher.find(rules, stream)
                    : ApplicationRuleMatcher.find(rules, stream, binding);
            if (binding != null && rule == null) {
                pending.add(failed(stream, new IllegalStateException(
                        "Captured tab has no matching canonical application volume rule"), onFailure));
                continue;
            }
            if (rule == null) {
                appliedRules.remove(stream.id());
                continue;
            }

            String ruleId = String.valueOf(rule.getOrDefault("id", ""));
            Object configuredVolume = rule.get("volume");
            double appVolume = configuredVolume instanceof Number number ? number.doubleValue() : 1.0;
            appVolume = Double.isFinite(appVolume) ? Math.max(0, Math.min(1, appVolume)) : 1.0;
            boolean appMuted = Boolean.TRUE.equals(rule.get("mute"));
            double volume = appVolume;
            boolean muted = appMuted;
            if (binding != null) {
                if (!Double.isFinite(binding.tabGain()) || binding.tabGain() < 0 || binding.tabGain() > 1) {
                    pending.add(failed(stream, new IllegalArgumentException("Captured tab gain must be within [0,1]"), onFailure));
                    continue;
                }
                volume *= binding.tabGain();
                muted |= binding.tabMuted();
            }
            String signature = ruleId + "|" + appVolume + "|" + appMuted + "|"
                    + (binding == null ? "" : binding.instanceKey() + "|" + binding.tabGain() + "|" + binding.tabMuted());
            if (!force && signature.equals(appliedRules.get(stream.id()))) {
                CompletableFuture<Void> existing = inFlightRules.get(stream.id());
                if (existing != null) pending.add(existing);
                continue;
            }
            appliedRules.put(stream.id(), signature);

            CompletableFuture<?>[] changes = new CompletableFuture<?>[] {
                    force || !Double.isFinite(stream.volume()) || Math.abs(stream.volume() - volume) > 1e-6
                            ? volumeWriter.apply(stream.id(), volume) : CompletableFuture.completedFuture(null),
                    force || stream.muted() != muted
                            ? muteWriter.apply(stream.id(), muted) : CompletableFuture.completedFuture(null)
            };
            CompletableFuture<Void> applied = CompletableFuture.allOf(changes);
            inFlightRules.put(stream.id(), applied);
            pending.add(applied);
            applied.whenComplete((ignored, error) -> {
                inFlightRules.remove(stream.id(), applied);
                if (error != null && appliedRules.remove(stream.id(), signature) && onFailure != null)
                    onFailure.accept(stream, error);
            });
        }
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
    }

    private static CompletableFuture<Void> failed(AudioNode stream, Throwable error,
                                                    BiConsumer<AudioNode, Throwable> onFailure) {
        if (onFailure != null) onFailure.accept(stream, error);
        return CompletableFuture.failedFuture(error);
    }
}
