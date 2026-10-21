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


/** Applies saved application and captured-tab levels to live playback streams. */
public final class ApplicationStreamRuleApplier {
    private final PipeWireBackend backend;
    private final ConcurrentMap<Integer, TargetState> targets = new ConcurrentHashMap<>();

    public ApplicationStreamRuleApplier(PipeWireBackend backend) {
        this.backend = backend;
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

    public CompletableFuture<Void> apply(PipeWireBackend.Snapshot current, List<Map<String, Object>> rules,
                                         Map<String, CapturedStreamBinding> bindings, boolean force,
                                         BiConsumer<AudioNode, Throwable> onFailure) {
        if (current == null || !current.connected()) return CompletableFuture.completedFuture(null);
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
        targets.keySet().removeIf(id -> !liveIds.contains(id));

        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (AudioNode stream : current.streams()) {
            CapturedStreamBinding binding = capturedBindings.get(stream.name());
            DesiredControl desired = binding == null ? null
                    : capturedControl(binding, stream, current.streams(), rulesById);
            if (desired == null && isCaptureOrOwned(stream)) continue;
            if (desired == null) {
                Map<String, Object> rule = ApplicationRuleMatcher.find(rules, stream);
                if (rule == null) {
                    forgetRule(stream);
                    continue;
                }
                desired = new DesiredControl(ruleGain(rule), Boolean.TRUE.equals(rule.get("mute")),
                        String.valueOf(rule.getOrDefault("id", "")) + "|" + rule.get("volume")
                                + "|" + Boolean.TRUE.equals(rule.get("mute")));
            }

            TargetState state = targets.computeIfAbsent(stream.id(), ignored -> new TargetState());
            writes.add(schedule(state, stream, desired,
                    force || !observedMatches(stream, desired)).whenComplete((ignored, error) -> {
                if (error != null && onFailure != null) onFailure.accept(stream, unwrap(error));
            }));
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> schedule(TargetState state, AudioNode stream, DesiredControl desired,
                                              boolean force) {
        synchronized (state) {
            if (!stream.name().equals(state.nodeName)) {
                state.nodeName = stream.name();
                state.appliedSignature = "";
            }
            state.desired = desired;
            state.generation++;
            state.force |= force;
            CompletableFuture<Void> next = state.tail.handle((ignored, priorError) -> null)
                    .thenCompose(ignored -> {
                        DesiredControl target;
                        long generation;
                        boolean mustWrite;
                        synchronized (state) {
                            target = state.desired;
                            generation = state.generation;
                            mustWrite = state.force || !target.signature().equals(state.appliedSignature);
                            state.force = false;
                        }
                        if (!mustWrite) return CompletableFuture.completedFuture(null);
                        CompletableFuture<Void> volume = !Double.isFinite(stream.volume())
                                || Math.abs(stream.volume() - target.volume()) > 0.01
                                ? backend.setVolume(stream.id(), target.volume())
                                : CompletableFuture.completedFuture(null);
                        return volume.thenCompose(unused -> stream.muted() != target.muted()
                                        ? backend.setMute(stream.id(), target.muted())
                                        : CompletableFuture.completedFuture(null))
                                .thenRun(() -> {
                                    synchronized (state) {
                                        if (generation == state.generation)
                                            state.appliedSignature = target.signature();
                                    }
                                });
                    });
            state.tail = next;
            return next;
        }
    }

    private static DesiredControl capturedControl(CapturedStreamBinding binding, AudioNode captureStream,
                                                   List<AudioNode> streams,
                                                   Map<String, Map<String, Object>> rulesById) {
        Map<String, Object> rule = CapturedStreamRuleResolver.findRule(binding, captureStream, streams, rulesById);
        if (rule == null) return null;
        double appGain = ruleGain(rule);
        double effectiveGain = appGain * binding.tabGain();
        boolean muted = Boolean.TRUE.equals(rule.get("mute")) || binding.tabMuted();
        String signature = binding.applicationRuleId() + "|" + appGain + "|" + binding.tabGain()
                + "|" + Boolean.TRUE.equals(rule.get("mute")) + "|" + binding.tabMuted()
                + "|" + binding.sourceNodeId() + "|" + binding.instanceKey();
        return new DesiredControl(effectiveGain, muted, signature);
    }

    private static double ruleGain(Map<String, Object> rule) {
        Object configured = rule.get("volume");
        double gain = configured instanceof Number number ? number.doubleValue() : 1.0;
        return Double.isFinite(gain) ? Math.max(0.0, Math.min(1.0, gain)) : 1.0;
    }

    private void forgetRule(AudioNode stream) {
        TargetState state = targets.get(stream.id());
        if (state == null) return;
        synchronized (state) {
            state.appliedSignature = "";
        }
    }

    private static boolean isCaptureOrOwned(AudioNode stream) {
        return AudioMatrixOwnedStream.matches(stream)
                || stream.name().startsWith(CapturedStreamBinding.NODE_PREFIX);
    }

    private static boolean observedMatches(AudioNode stream, DesiredControl desired) {
        return Double.isFinite(stream.volume()) && Math.abs(stream.volume() - desired.volume()) <= 0.01
                && stream.muted() == desired.muted();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null)
            current = current.getCause();
        return current;
    }

    private record DesiredControl(double volume, boolean muted, String signature) {}

    private static final class TargetState {
        private String nodeName = "";
        private String appliedSignature = "";
        private DesiredControl desired;
        private long generation;
        private boolean force;
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    }
}
