package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RouteSmokePcmSupportTest {
    @TempDir Path directory;

    @Test
    void stereoRmsDoesNotCancelOppositePolarityChannels() throws Exception {
        int frames = RouteSmokePcmSupport.SAMPLE_RATE / 2;
        ByteBuffer pcm = ByteBuffer.allocate(frames * 2 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < frames; frame++) {
            float value = (float) (0.25 * Math.sin(2 * Math.PI * 440 * frame / RouteSmokePcmSupport.SAMPLE_RATE));
            pcm.putFloat(value).putFloat(-value);
        }
        Path file = directory.resolve("stereo.f32");
        Files.write(file, pcm.array());
        assertEquals(0.25 / Math.sqrt(2), RouteSmokePcmSupport.rms(file, 0.5), 1e-7);
    }

    @Test
    void rmsMeasuresOnlyTheCompleteRecentWindowIncludingEveryFrequency() throws Exception {
        int frames = RouteSmokePcmSupport.SAMPLE_RATE;
        ByteBuffer pcm = ByteBuffer.allocate(frames * 2 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < frames; frame++) {
            float value = frame < frames / 2 ? 1.0f : 0.1f;
            pcm.putFloat(value).putFloat(value);
        }
        Path file = directory.resolve("recent.f32");
        Files.write(file, pcm.array());
        // DC would be invisible to a tone-only silence check, but must be included in RMS.
        assertEquals(0.1, RouteSmokePcmSupport.rms(file, 0.5), 1e-7);
    }

    @Test
    void incompleteOrNonFiniteCaptureCannotMasqueradeAsSilence() throws Exception {
        Path incomplete = directory.resolve("incomplete.f32");
        Files.write(incomplete, new byte[8]);
        assertThrows(IOException.class, () -> RouteSmokePcmSupport.rms(incomplete, 0.5));
        ByteBuffer pcm = ByteBuffer.allocate(RouteSmokePcmSupport.SAMPLE_RATE * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        pcm.putFloat(Float.NaN);
        Path nonFinite = directory.resolve("nonfinite.f32");
        Files.write(nonFinite, pcm.array());
        assertThrows(IOException.class, () -> RouteSmokePcmSupport.rms(nonFinite, 0.5));
    }
}
