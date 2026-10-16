package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalibrationSignalTest {
    @Test
    void steppedSineProducesBoundedStereoPcmAndValidWavHeader() {
        int steps = 8;
        int dwellMs = 200;
        byte[] wav = CalibrationSignal.steppedSineWave(30, 300, steps, dwellMs);
        ByteBuffer data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals('R', wav[0]);
        assertEquals('W', wav[8]);
        assertEquals(2, Short.toUnsignedInt(data.getShort(22)));
        assertEquals(CalibrationSignal.SAMPLE_RATE, data.getInt(24));
        assertEquals(steps * dwellMs * CalibrationSignal.SAMPLE_RATE / 1000 * 4, data.getInt(40));

        int peak = 0;
        boolean hasSignal = false;
        for (int offset = 44; offset < wav.length; offset += 4) {
            short left = data.getShort(offset);
            short right = data.getShort(offset + 2);
            assertEquals(left, right);
            peak = Math.max(peak, Math.abs((int) left));
            hasSignal |= left != 0;
        }
        assertTrue(hasSignal);
        assertTrue(peak <= Math.ceil(CalibrationSignal.AMPLITUDE * Short.MAX_VALUE));
    }

    @Test
    void steppedSineBoundsFrequencyDurationAndStepCount() {
        assertThrows(IllegalArgumentException.class, () -> CalibrationSignal.steppedSineWave(10, 300, 8, 200));
        assertThrows(IllegalArgumentException.class, () -> CalibrationSignal.steppedSineWave(30, 1500, 8, 200));
        assertThrows(IllegalArgumentException.class, () -> CalibrationSignal.steppedSineWave(300, 30, 8, 200));
        assertThrows(IllegalArgumentException.class, () -> CalibrationSignal.steppedSineWave(30, 300, 1, 200));
        assertThrows(IllegalArgumentException.class, () -> CalibrationSignal.steppedSineWave(30, 300, 8, 301));
    }

    @Test
    void manualImpulseIsShortShapedStereoPcmAtTheDocumentedPeakCap() {
        byte[] wav = CalibrationSignal.manualImpulseWave();
        ByteBuffer data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int frames = CalibrationSignal.SAMPLE_RATE * CalibrationSignal.IMPULSE_DURATION_MS / 1000;

        assertEquals('R', wav[0]);
        assertEquals('W', wav[8]);
        assertEquals(2, Short.toUnsignedInt(data.getShort(22)));
        assertEquals(CalibrationSignal.SAMPLE_RATE, data.getInt(24));
        assertEquals(frames * 4, data.getInt(40));
        assertEquals(0, data.getShort(44));
        assertEquals(0, data.getShort(44 + (frames - 1) * 4));

        int peak = 0;
        for (int offset = 44; offset < wav.length; offset += 4) {
            short left = data.getShort(offset);
            short right = data.getShort(offset + 2);
            assertEquals(left, right);
            peak = Math.max(peak, Math.abs((int) left));
        }
        assertTrue(peak > 0);
        assertTrue(peak <= Math.ceil(CalibrationSignal.IMPULSE_AMPLITUDE * Short.MAX_VALUE));
    }
}
