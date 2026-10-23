package io.github.ilyaandreev05.audiomatrix.config;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One shared, low-frequency fingerprint poller used only when native watching is unavailable. */
final class ConfigWatchPoller {
    private static final Set<ConfigWatcher> WATCHERS = ConcurrentHashMap.newKeySet();
    private static ScheduledExecutorService executor;
    private static ScheduledFuture<?> task;

    private ConfigWatchPoller() {}

    static synchronized void register(ConfigWatcher watcher, IOException nativeFailure) throws IOException {
        try {
            WATCHERS.add(watcher);
            ensureStarted();
        } catch (RuntimeException failure) {
            WATCHERS.remove(watcher);
            stopIfIdle();
            IOException fallbackFailure = new IOException("Could not start configuration polling fallback", failure);
            fallbackFailure.addSuppressed(nativeFailure);
            throw fallbackFailure;
        } catch (Error failure) {
            WATCHERS.remove(watcher);
            stopIfIdle();
            failure.addSuppressed(nativeFailure);
            throw failure;
        }
    }

    static synchronized void unregister(ConfigWatcher watcher) {
        WATCHERS.remove(watcher);
        stopIfIdle();
    }

    private static void ensureStarted() {
        if (executor != null) return;
        ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "audio-matrix-config-watch-poll");
            thread.setDaemon(true);
            return thread;
        });
        try {
            ScheduledFuture<?> scheduled = created.scheduleWithFixedDelay(
                    ConfigWatchPoller::pollAll, 0, 300, TimeUnit.MILLISECONDS);
            executor = created;
            task = scheduled;
        } catch (RuntimeException | Error failure) {
            created.shutdownNow();
            throw failure;
        }
    }

    private static void pollAll() {
        for (ConfigWatcher watcher : WATCHERS) {
            try {
                watcher.pollForExternalChange();
            } catch (RuntimeException failure) {
                watcher.reportStopped(new IOException("Configuration polling fallback failed", failure));
            } catch (Error failure) {
                IOException diagnostic = new IOException("Configuration polling fallback failed", failure);
                for (ConfigWatcher affected : WATCHERS) affected.reportStopped(diagnostic);
                synchronized (ConfigWatchPoller.class) {
                    task = null;
                    ScheduledExecutorService active = executor;
                    executor = null;
                    if (active != null) active.shutdownNow();
                }
                throw failure;
            }
        }
    }

    private static void stopIfIdle() {
        if (!WATCHERS.isEmpty()) return;
        ScheduledFuture<?> scheduled = task;
        task = null;
        ScheduledExecutorService active = executor;
        executor = null;
        if (scheduled != null) scheduled.cancel(false);
        if (active != null) active.shutdownNow();
    }
}
