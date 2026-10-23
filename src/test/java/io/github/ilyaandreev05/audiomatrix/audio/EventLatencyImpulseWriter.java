package io.github.ilyaandreev05.audiomatrix.audio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Persistent, back-pressure-paced silence, then isolated one-frame impulses, then silence again. */
final class EventLatencyImpulseWriter implements AutoCloseable {
    static final int SAMPLE_RATE = RouteSmokePcmSupport.SAMPLE_RATE;
    static final int IMPULSE_COUNT = 7;
    private static final int CHUNK_FRAMES = 256;
    private static final int IMPULSE_SPACING_FRAMES = SAMPLE_RATE * 3 / 10;
    private final Process process;
    private final Thread thread;
    private final CompletableFuture<Void> impulsesWritten = new CompletableFuture<>();
    private volatile boolean armed;
    private volatile boolean running = true;

    EventLatencyImpulseWriter(Process process) {
        this.process = process;
        thread = Thread.ofVirtual().name("event-route-latency-pcm").start(this::write);
    }

    void emitImpulses() {
        armed = true;
    }

    void awaitImpulses() throws Exception {
        impulsesWritten.get(15, TimeUnit.SECONDS);
    }

    private void write() {
        ByteBuffer pcm = ByteBuffer.allocate(CHUNK_FRAMES * 2 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        long cursor = 0;
        long trainStart = -1;
        int emitted = 0;
        try {
            OutputStream output = process.getOutputStream();
            while (running) {
                if (armed && trainStart < 0) trainStart = cursor + SAMPLE_RATE / 2;
                pcm.clear();
                for (int frame = 0; frame < CHUNK_FRAMES; frame++) {
                    float value = 0;
                    if (trainStart >= 0 && emitted < IMPULSE_COUNT &&
                            cursor + frame == trainStart + (long) emitted * IMPULSE_SPACING_FRAMES) {
                        value = 0.25f;
                        emitted++;
                    }
                    pcm.putFloat(value).putFloat(value);
                }
                output.write(pcm.array());
                cursor += CHUNK_FRAMES;
                if (emitted == IMPULSE_COUNT) impulsesWritten.complete(null);
            }
        } catch (IOException error) {
            if (running) impulsesWritten.completeExceptionally(error);
        }
    }

    @Override
    public void close() {
        running = false;
        process.destroy();
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Destroying the player closes the pipe and unblocks its writer.
        }
        try {
            thread.join(2000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
