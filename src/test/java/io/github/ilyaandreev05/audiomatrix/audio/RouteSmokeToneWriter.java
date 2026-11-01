package io.github.ilyaandreev05.audiomatrix.audio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Keeps a private route-smoke tone running while its PipeWire playback stream is measured. */
final class RouteSmokeToneWriter {
    private static final int CHUNK_FRAMES = 480;
    private final Process process;
    private final double frequency;
    private final Thread thread;
    private volatile boolean running = true;

    private RouteSmokeToneWriter(Process process, double frequency) {
        this.process = process;
        this.frequency = frequency;
        this.thread = Thread.ofVirtual().name("route-smoke-tone").start(this::write);
    }

    static RouteSmokeToneWriter start(Process process, double frequency) {
        return new RouteSmokeToneWriter(process, frequency);
    }

    private void write() {
        ByteBuffer buffer = ByteBuffer.allocate(CHUNK_FRAMES * RouteSmokePcmSupport.CHANNELS * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        long cursor = 0;
        try {
            OutputStream stream = process.getOutputStream();
            while (running) {
                buffer.clear();
                for (int frame = 0; frame < CHUNK_FRAMES; frame++) {
                    double value = RouteSmokePcmSupport.TONE_AMPLITUDE * Math.sin(2 * Math.PI * frequency
                            * (cursor + frame) / RouteSmokePcmSupport.SAMPLE_RATE);
                    buffer.putFloat((float) value);
                    buffer.putFloat((float) value);
                }
                // The pipe back-pressure paces the writer; sleeping here would starve the stream.
                stream.write(buffer.array());
                cursor += CHUNK_FRAMES;
            }
        } catch (IOException stopped) {
            // The stream is gone; the smoke test notices the missing signal.
        }
    }

    boolean processAlive() {
        return process.isAlive();
    }

    boolean writerAlive() {
        return thread.isAlive();
    }


    void stop() {
        running = false;
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // The process may already be gone; the tone thread exits on the next write.
        }
        try {
            thread.join(2000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
