package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.concurrent.CompletableFuture;

/** Per-stream generation, manual intent and a single serialized runtime write tail. */
final class ApplicationStreamControlState {
    record Control(double volume, boolean muted) {}
    private record Intent(String id, long generation, Control control) {}
    private final ApplicationStreamRuleApplier.VolumeSetter volumeSetter;
    private final ApplicationStreamRuleApplier.MuteSetter muteSetter;
    private String nodeName = "";
    private long generation;
    private Control applied;
    private Intent manual;
    private long appliedAtMillis;
    private boolean uncertain;
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

    ApplicationStreamControlState(ApplicationStreamRuleApplier.VolumeSetter volumeSetter,
                                  ApplicationStreamRuleApplier.MuteSetter muteSetter) {
        this.volumeSetter = volumeSetter;
        this.muteSetter = muteSetter;
    }

    synchronized CompletableFuture<Void> manual(AudioNode stream, double volume, String id) {
        observe(stream);
        Intent intent = new Intent(id, generation, new Control(volume, false));
        manual = intent; // Synchronous registration, before any queued runtime work.
        CompletableFuture<Void> next = tail.handle((ignored, error) -> null).thenCompose(ignored -> {
            synchronized (this) {
                if (generation != intent.generation()) return replaced(stream);
                if (manual != intent) return CompletableFuture.completedFuture(null);
                return write(stream, generation, intent.control(), System.currentTimeMillis(), true, intent);
            }
        });
        tail = next;
        return next;
    }

    synchronized void clear(AudioNode stream, String id) {
        if (manual == null || !manual.id().equals(id) || manual.generation() != generation
                || !nodeName.equals(stream.name())) return;
        manual = null;
        applied = null;
        uncertain = true;
    }

    synchronized void observe(AudioNode stream) {
        if (!stream.name().equals(nodeName)) {
            nodeName = stream.name();
            generation++;
            applied = null;
            manual = null;
            uncertain = !tail.isDone();
        }
    }

    synchronized boolean disappeared() {
        nodeName = "";
        generation++;
        applied = null;
        manual = null;
        uncertain = true;
        return tail.isDone();
    }

    synchronized CompletableFuture<Void> reconcile(AudioNode stream, Control saved, long observedAt, boolean force) {
        observe(stream);
        long expectedGeneration = generation;
        CompletableFuture<Void> next = tail.handle((ignored, error) -> null).thenCompose(ignored -> {
            synchronized (this) {
                if (expectedGeneration != generation) return replaced(stream);
                Control desired = saved;
                if (manual != null) {
                    if (manual.control().equals(saved)) manual = null;
                    else desired = manual.control();
                }
                if (desired == null) {
                    applied = null;
                    uncertain = true;
                    return CompletableFuture.completedFuture(null);
                }
                return write(stream, expectedGeneration, desired, observedAt, force, manual);
            }
        });
        tail = next;
        return next;
    }

    private CompletableFuture<Void> write(AudioNode stream, long expectedGeneration, Control desired,
                                          long observedAt, boolean force, Intent expectedIntent) {
        if (generation != expectedGeneration) return replaced(stream);
        Control previous = applied;
        boolean fresh = previous == null || observedAt > appliedAtMillis;
        boolean writeVolume = force || uncertain
                || (previous != null && Double.compare(previous.volume(), desired.volume()) != 0)
                || (fresh && (!stream.muted() || previous == null)
                    && (!Double.isFinite(stream.volume()) || Math.abs(stream.volume() - desired.volume()) > .01));
        boolean writeMute = force || uncertain || (previous != null && previous.muted() != desired.muted())
                || (fresh && stream.muted() != desired.muted()) || (writeVolume && desired.muted());
        if (!writeVolume && !writeMute) {
            applied = desired;
            if (previous == null) appliedAtMillis = observedAt;
            return CompletableFuture.completedFuture(null);
        }
        applied = null;
        uncertain = true; // A failure or a partially applied control must never be cached as success.
        CompletableFuture<Void> first = desired.muted()
                ? (writeVolume ? volumeSetter.setVolume(stream.id(), desired.volume()) : CompletableFuture.completedFuture(null))
                : (writeMute ? muteSetter.setMute(stream.id(), false, desired.volume()) : CompletableFuture.completedFuture(null));
        return first.thenCompose(ignored -> {
            synchronized (this) {
                if (generation != expectedGeneration) return replaced(stream);
                // A newly registered intent must not be overwritten by an older in-flight reconcile.
                if (manual != expectedIntent) return CompletableFuture.completedFuture(null);
                if (desired.muted()) return writeMute ? muteSetter.setMute(stream.id(), true, desired.volume())
                        : CompletableFuture.completedFuture(null);
                return writeVolume ? volumeSetter.setVolume(stream.id(), desired.volume())
                        : CompletableFuture.completedFuture(null);
            }
        }).thenRun(() -> {
            synchronized (this) {
                if (generation != expectedGeneration)
                    throw new IllegalStateException("Playback stream was replaced: " + stream.name());
                if (manual != expectedIntent) return;
                applied = desired;
                appliedAtMillis = System.currentTimeMillis();
                uncertain = false;
            }
        });
    }

    private static CompletableFuture<Void> replaced(AudioNode stream) {
        return CompletableFuture.failedFuture(new IllegalStateException("Playback stream was replaced: " + stream.name()));
    }
}
