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
    private final VolumeSetter volumeSetter;
    private final MuteSetter muteSetter;
    private final ConcurrentMap<Integer, TargetState> targets = new ConcurrentHashMap<>();

    public ApplicationStreamRuleApplier(PipeWireBackend backend) {
        this(backend::setVolume, backend::setMute);
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
        targets.forEach((id, state) -> {
            if (!liveIds.contains(id)) {
                synchronized (state) {
                    state.nodeName = "";
                    state.generation++;
                    state.applied = null;
                    state.uncertain = true;
                    if (state.tail.isDone()) targets.remove(id, state);
                }
            }
        });

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
                desired = new DesiredControl(ruleGain(rule), Boolean.TRUE.equals(rule.get("mute")));
            }

            TargetState state = targets.computeIfAbsent(stream.id(), ignored -> new TargetState());
            writes.add(schedule(state, stream, desired, current.capturedAtMillis(), force)
                    .whenComplete((ignored, error) -> {
                if (error != null && onFailure != null) onFailure.accept(stream, unwrap(error));
            }));
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> schedule(TargetState state, AudioNode stream, DesiredControl desired,
                                              long observedAtMillis, boolean force) {
        synchronized (state) {
            if (!stream.name().equals(state.nodeName)) {
                state.nodeName = stream.name();
                state.generation++;
                state.applied = null;
                state.uncertain = !state.tail.isDone();
            }
            long generation = state.generation;
            CompletableFuture<Void> next = state.tail.handle((ignored, priorError) -> null)
                    .thenCompose(ignored -> write(state, generation, stream, desired, observedAtMillis, force));
            state.tail = next;
            return next;
        }
    }

    private CompletableFuture<Void> write(TargetState state, long generation, AudioNode stream,
                                           DesiredControl desired, long observedAtMillis, boolean force) {
        synchronized (state) {
            if (generation != state.generation) return replaced(stream);
            DesiredControl applied = state.applied;
            // A snapshot taken before our completed write must not override its known result.
            boolean freshObservation = applied == null || observedAtMillis > state.appliedAtMillis;
            boolean volumeChanged = applied != null && Double.compare(applied.volume(), desired.volume()) != 0;
            boolean writeVolume = force || state.uncertain || volumeChanged
                    || (freshObservation && (!stream.muted() || applied == null)
                    && !volumeMatches(stream.volume(), desired.volume()));
            boolean writeMute = force || state.uncertain || (applied != null && applied.muted() != desired.muted())
                    || (freshObservation && stream.muted() != desired.muted()) || (writeVolume && desired.muted());
            if (!writeVolume && !writeMute) {
                state.applied = desired;
                if (applied == null) state.appliedAtMillis = observedAtMillis;
                return CompletableFuture.completedFuture(null);
            }

            // Failed or partially completed controls leave no trustworthy cached state.
            state.applied = null;
            state.uncertain = true;
            CompletableFuture<Void> volume = writeVolume
                    ? volumeSetter.setVolume(stream.id(), desired.volume())
                    : CompletableFuture.completedFuture(null);
            return volume.thenCompose(ignored -> {
                synchronized (state) {
                    if (generation != state.generation) return replaced(stream);
                    return writeMute ? muteSetter.setMute(stream.id(), desired.muted(), desired.volume())
                            : CompletableFuture.completedFuture(null);
                }
            }).thenRun(() -> {
                synchronized (state) {
                    if (generation != state.generation)
                        throw new IllegalStateException("Playback stream was replaced: " + stream.name());
                    state.applied = desired;
                    state.appliedAtMillis = System.currentTimeMillis();
                    state.uncertain = false;
                }
            });
        }
    }

    private static CompletableFuture<Void> replaced(AudioNode stream) {
        return CompletableFuture.failedFuture(
                new IllegalStateException("Playback stream was replaced: " + stream.name()));
    }

    private static DesiredControl capturedControl(CapturedStreamBinding binding, AudioNode captureStream,
                                                   List<AudioNode> streams,
                                                   Map<String, Map<String, Object>> rulesById) {
        Map<String, Object> rule = CapturedStreamRuleResolver.findRule(binding, captureStream, streams, rulesById);
        if (rule == null) return null;
        double appGain = ruleGain(rule);
        double effectiveGain = appGain * binding.tabGain();
        boolean muted = Boolean.TRUE.equals(rule.get("mute")) || binding.tabMuted();
        return new DesiredControl(effectiveGain, muted);
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
            state.applied = null;
            state.uncertain = true;
        }
    }

    private static boolean isCaptureOrOwned(AudioNode stream) {
        return AudioMatrixOwnedStream.matches(stream)
                || stream.name().startsWith(CapturedStreamBinding.NODE_PREFIX);
    }

    private static boolean volumeMatches(double observed, double desired) {
        return Double.isFinite(observed) && Math.abs(observed - desired) <= 0.01;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null)
            current = current.getCause();
        return current;
    }

    private record DesiredControl(double volume, boolean muted) {}

    private static final class TargetState {
        private String nodeName = "";
        private DesiredControl applied;
        private long appliedAtMillis;
        private long generation;
        private boolean uncertain;
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    }
}
