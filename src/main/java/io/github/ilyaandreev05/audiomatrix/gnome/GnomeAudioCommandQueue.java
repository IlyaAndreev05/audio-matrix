package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/** One active audio operation plus bounded FIFO pending work; presentation never occupies an audio slot. */
final class GnomeAudioCommandQueue implements AutoCloseable {
    static final int MAX_PENDING = 128;
    private final Function<GnomeControlBridge.Command, CompletableFuture<Void>> execute;
    private final BiFunction<GnomeControlBridge.Command, String, CompletableFuture<Void>> reject;
    private final ArrayDeque<Entry> pending = new ArrayDeque<>();
    private final Set<CompletableFuture<Void>> presentations = new HashSet<>();
    private Entry active;
    private boolean closed;

    GnomeAudioCommandQueue(Function<GnomeControlBridge.Command, CompletableFuture<Void>> execute,
            BiFunction<GnomeControlBridge.Command, String, CompletableFuture<Void>> reject) {
        this.execute = execute;
        this.reject = reject;
    }

    synchronized CompletableFuture<Void> dispatch(GnomeControlBridge.Command command) {
        if (closed) return CompletableFuture.completedFuture(null);
        if ("open".equals(command.name())) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            presentations.add(completion);
            run(command).whenComplete((ignored, error) -> {
                synchronized (this) {
                    presentations.remove(completion);
                    completion.complete(null);
                }
            });
            return completion;
        }
        Entry entry = new Entry(command, GnomeVolumeCommandKey.from(command));
        Entry last = pending.peekLast();
        if (last != null && entry.key != null && entry.key.equals(last.key)) {
            pending.removeLast();
            // Install the replacement before notifying displaced callers (callbacks may dispatch).
            pending.addLast(entry);
            reject.apply(last.command, "superseded by newer volume for this target")
                    .whenComplete((ignored, error) -> last.completion.complete(null));
        } else {
            if (active != null && pending.size() >= MAX_PENDING)
                return reject.apply(command, "Audio Matrix command queue is full");
            pending.addLast(entry);
        }
        startNext();
        return entry.completion;
    }

    private CompletableFuture<Void> run(GnomeControlBridge.Command command) {
        try {
            CompletableFuture<Void> work = execute.apply(command);
            return work == null ? reject.apply(command, "Command handler returned no completion result") : work;
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private void startNext() {
        if (closed || active != null || pending.isEmpty()) return;
        Entry entry = pending.removeFirst();
        active = entry;
        run(entry.command).whenComplete((ignored, error) -> {
            synchronized (this) {
                if (active != entry) return;
                // Keep the active marker until completion callbacks return, preserving FIFO reentrancy.
                entry.completion.complete(null);
                active = null;
                startNext();
            }
        });
    }

    @Override public synchronized void close() {
        closed = true;
        if (active != null) {
            active.completion.complete(null);
            active = null;
        }
        for (Entry entry : pending) entry.completion.complete(null);
        pending.clear();
        for (CompletableFuture<Void> completion : presentations) completion.complete(null);
        presentations.clear();
    }

    private static final class Entry {
        final GnomeControlBridge.Command command;
        final GnomeVolumeCommandKey key;
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        Entry(GnomeControlBridge.Command command, GnomeVolumeCommandKey key) {
            this.command = command;
            this.key = key;
        }
    }
}
