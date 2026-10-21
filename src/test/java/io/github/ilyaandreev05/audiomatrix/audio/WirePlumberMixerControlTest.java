package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WirePlumberMixerControlTest {
    @Test void linearLevelsAreConvertedOnlyForTheWpctlFallback() {
        assertEquals(0.0, WirePlumberMixerControl.wpctlCubicValue(0.0), 1e-12);
        assertEquals(Math.cbrt(0.5), WirePlumberMixerControl.wpctlCubicValue(0.5), 1e-12);
        assertEquals(1.0, WirePlumberMixerControl.wpctlCubicValue(1.0), 1e-12);
        assertEquals(0.4, 0.5 * 0.8, 1e-12);
        assertEquals(Math.cbrt(0.4), WirePlumberMixerControl.wpctlCubicValue(0.5 * 0.8), 1e-12);
        assertEquals(0.5, WirePlumberMixerControl.linearFromWpctlCubic(Math.cbrt(0.5)), 1e-12);
        assertEquals(0.0, WirePlumberMixerControl.linearFromWpctlCubic(-0.1), 1e-12);
        assertEquals(1.0, WirePlumberMixerControl.linearFromWpctlCubic(1.2), 1e-12);
    }

    @Test void nativeMixerProtocolAcceptsOnlyUnitLinearLevels() {
        assertEquals(0.0, clamp(-0.5), 1e-12);
        assertEquals(0.5, clamp(0.5), 1e-12);
        assertEquals(1.0, clamp(2.0), 1e-12);
    }

    @Test void queuedAbsoluteVolumeUpdatesCoalescePerNode() {
        List<VolumeWrite> writes = new ArrayList<>();
        WirePlumberMixerControl.VolumeWriteQueue queue =
                new WirePlumberMixerControl.VolumeWriteQueue(
                        (nodeId, level) -> writes.add(new VolumeWrite(nodeId, level)));
        ManualExecutor executor = new ManualExecutor();

        CompletableFuture<Void> first = queue.setVolumeAsync(4, 0.1, executor);
        CompletableFuture<Void> second = queue.setVolumeAsync(4, 0.8, executor);
        CompletableFuture<Void> otherNode = queue.setVolumeAsync(5, 0.3, executor);
        assertEquals(2, executor.queuedTaskCount());
        assertTrue(writes.isEmpty());

        executor.runNext();
        first.join();
        second.join();
        assertEquals(List.of(new VolumeWrite(4, 0.8)), writes);
        executor.runNext();
        otherNode.join();
        assertEquals(List.of(new VolumeWrite(4, 0.8), new VolumeWrite(5, 0.3)), writes);
    }

    @Test void synchronousVolumeSupersedesQueuedAsyncVolumeBeforeWorkerStarts() throws Exception {
        List<VolumeWrite> writes = new ArrayList<>();
        WirePlumberMixerControl.VolumeWriteQueue queue =
                new WirePlumberMixerControl.VolumeWriteQueue(
                        (nodeId, level) -> writes.add(new VolumeWrite(nodeId, level)));
        ManualExecutor executor = new ManualExecutor();

        CompletableFuture<Void> async = queue.setVolumeAsync(7, 0.2, executor);
        queue.setVolume(7, 0.9);
        async.join();
        assertEquals(List.of(new VolumeWrite(7, 0.9)), writes);

        executor.runNext();
        assertEquals(List.of(new VolumeWrite(7, 0.9)), writes);
    }

    @Test void newestAsyncVolumeFollowsAnAlreadyRunningWrite() throws Exception {
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        List<VolumeWrite> writes = new ArrayList<>();
        WirePlumberMixerControl.VolumeWriteQueue queue =
                new WirePlumberMixerControl.VolumeWriteQueue((nodeId, level) -> {
                    writes.add(new VolumeWrite(nodeId, level));
                    if (level == 0.2) {
                        firstWriteStarted.countDown();
                        if (!releaseFirstWrite.await(2, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting to release the first volume write");
                        }
                    }
                });
        ManualExecutor executor = new ManualExecutor();

        CompletableFuture<Void> first = queue.setVolumeAsync(9, 0.2, executor);
        Thread worker = new Thread(executor::runNext);
        worker.start();
        CompletableFuture<Void> latest;
        try {
            assertTrue(firstWriteStarted.await(2, TimeUnit.SECONDS));
            latest = queue.setVolumeAsync(9, 0.7, executor);
            assertEquals(0, executor.queuedTaskCount());
        } finally {
            releaseFirstWrite.countDown();
        }
        worker.join(2_000);
        assertFalse(worker.isAlive(), "Volume writer did not finish");
        first.join();
        latest.join();
        assertEquals(List.of(new VolumeWrite(9, 0.2), new VolumeWrite(9, 0.7)), writes);
    }

    private record VolumeWrite(int nodeId, double level) {}

    private static final class ManualExecutor extends AbstractExecutorService {
        private final List<Runnable> queued = new ArrayList<>();
        private boolean shutdown;

        @Override public synchronized void shutdown() {
            shutdown = true;
        }

        @Override public synchronized List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> remaining = new ArrayList<>(queued);
            queued.clear();
            return remaining;
        }

        @Override public synchronized boolean isShutdown() {
            return shutdown;
        }

        @Override public synchronized boolean isTerminated() {
            return shutdown && queued.isEmpty();
        }

        @Override public synchronized boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override public synchronized void execute(Runnable command) {
            if (shutdown) throw new RejectedExecutionException("Executor has been shut down");
            queued.add(command);
        }

        int queuedTaskCount() {
            synchronized (this) { return queued.size(); }
        }

        void runNext() {
            Runnable command;
            synchronized (this) { command = queued.remove(0); }
            command.run();
        }
    }

    @Test void privatePipeWireAppliesLinearVolumeAndFastMuteWithoutHardware() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE")));
        int nodeId = Integer.parseInt(System.getenv("AUDIO_MATRIX_LIVE_MIXER_NODE_ID"));
        WirePlumberMixerControl.setVolume(nodeId, 0.5);
        assertEquals(Math.cbrt(0.5), wpctlVolume(nodeId), 0.025);
        WirePlumberMixerControl.setVolume(nodeId, 0.0);
        assertEquals(0.0, wpctlVolume(nodeId), 0.01);
        WirePlumberMixerControl.setVolume(nodeId, 1.0);
        assertEquals(1.0, wpctlVolume(nodeId), 0.01);

        WirePlumberMixerControl.setVolume(nodeId, 0.0);
        WirePlumberMixerControl.setMuted(nodeId, true);
        WirePlumberMixerControl.setMuted(nodeId, false);
        assertEquals(0.0, wpctlVolume(nodeId), 0.01);

        WirePlumberMixerControl.setVolume(nodeId, 0.5);
        List<Long> elapsedMillis = new ArrayList<>();
        for (int cycle = 0; cycle < 20; cycle++) {
            long started = System.nanoTime();
            WirePlumberMixerControl.setMuted(nodeId, cycle % 2 == 0);
            elapsedMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
        WirePlumberMixerControl.setMuted(nodeId, false);
        elapsedMillis.sort(Long::compareTo);
        long p95 = elapsedMillis.get((int) Math.ceil(elapsedMillis.size() * 0.95) - 1);
        assertTrue(p95 <= 100, "Private PipeWire mute p95 was " + p95 + " ms");
        assertEquals(Math.cbrt(0.5), wpctlVolume(nodeId), 0.025);
    }

    private static double wpctlVolume(int nodeId) throws Exception {
        Process command = new ProcessBuilder("wpctl", "get-volume", Integer.toString(nodeId))
                .redirectErrorStream(true).start();
        try (BufferedReader output = new BufferedReader(new InputStreamReader(command.getInputStream()))) {
            String line = output.lines().reduce("", (left, right) -> left + " " + right);
            assertTrue(command.waitFor(3, TimeUnit.SECONDS), "wpctl get-volume did not return");
            assertEquals(0, command.exitValue(), line);
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("Volume:\\s*([0-9.]+)").matcher(line);
            assertTrue(matcher.find(), "Could not parse wpctl output: " + line);
            return Double.parseDouble(matcher.group(1));
        }
    }

    private static double clamp(double level) {
        return Math.max(0.0, Math.min(1.0, level));
    }
}
