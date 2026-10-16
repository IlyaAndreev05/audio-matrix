package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixLevelMathTest {
    @Test
    void appliesEffectsMasterAsASeparateLinearBusStage() {
        assertEquals(0.4, MixLevelMath.effectiveBusVolume(0.8, 0.5), 1e-12);
        assertEquals(0, MixLevelMath.effectiveBusVolume(1, 0), 1e-12);
        assertTrue(MixLevelMath.effectiveBusMute(false, true));
        assertTrue(MixLevelMath.effectiveBusMute(true, false));
        assertFalse(MixLevelMath.effectiveBusMute(false, false));
    }

    @Test
    void convertsRouteGainToLinearVolumeWithoutDiscardingPositiveGain() {
        assertEquals(1, MixLevelMath.effectiveRouteVolume(0.5, 6.020599913), 1e-8);
        assertEquals(Math.pow(10, 24.0 / 20.0), MixLevelMath.effectiveRouteVolume(1, 24), 1e-10);
        assertEquals(0, MixLevelMath.effectiveRouteVolume(0, 24), 0);
        assertEquals(0, MixLevelMath.toDb(1), 1e-12);
        assertEquals(Double.NEGATIVE_INFINITY, MixLevelMath.toDb(0));
    }
}
