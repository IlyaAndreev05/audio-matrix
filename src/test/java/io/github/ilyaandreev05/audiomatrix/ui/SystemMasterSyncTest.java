package io.github.ilyaandreev05.audiomatrix.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Observed-versus-configured rules for the system mixer volume and mute mirror. */
class SystemMasterSyncTest {

    @Test void observedMuteWinsInBothDirections() {
        SystemMasterSync.Decision muted = SystemMasterSync.compare(0.5, true, 0.5, false);
        assertNotNull(muted, "observed mute true with saved false is a change");
        assertTrue(muted.writeMute());
        assertFalse(muted.writeVolume(), "an unchanged volume is not written again");
        assertEquals(true, muted.muted());

        SystemMasterSync.Decision unmuted = SystemMasterSync.compare(0.5, false, 0.5, true);
        assertNotNull(unmuted, "observed mute false with saved true is a change");
        assertTrue(unmuted.writeMute());
        assertFalse(unmuted.writeVolume());
        assertEquals(false, unmuted.muted());
    }

    @Test void equalVolumeAndMuteWriteNothing() {
        assertNull(SystemMasterSync.compare(0.5, false, 0.5, false));
        assertNull(SystemMasterSync.compare(0.502, true, 0.5, true),
                "a volume inside the 0.005 tolerance is not a change");
    }

    @Test void volumeOnlyChangeKeepsTheObservedMute() {
        SystemMasterSync.Decision decision = SystemMasterSync.compare(0.25, true, 0.8, true);
        assertNotNull(decision);
        assertTrue(decision.writeVolume());
        assertFalse(decision.writeMute());
        assertEquals(0.25, decision.volume(), 1e-9);
        assertEquals(true, decision.muted(), "the observed mute is reported even when only the volume differs");
    }

    @Test void simultaneousVolumeAndMuteDifferenceWritesBothFields() {
        SystemMasterSync.Decision decision = SystemMasterSync.compare(0.25, false, 0.8, true);
        assertNotNull(decision);
        assertTrue(decision.writeVolume());
        assertTrue(decision.writeMute());
        assertEquals(0.25, decision.volume(), 1e-9);
        assertEquals(false, decision.muted());
    }

    @Test void muteOnlyChangeKeepsTheObservedVolume() {
        SystemMasterSync.Decision decision = SystemMasterSync.compare(0.8, true, 0.8, false);
        assertNotNull(decision);
        assertFalse(decision.writeVolume());
        assertTrue(decision.writeMute());
        assertEquals(0.8, decision.volume(), 1e-9);
    }

    @Test void unusableObservationIsIgnored() {
        assertNull(SystemMasterSync.compare(Double.NaN, true, 0.8, false));
        assertFalse(SystemMasterSync.usableVolume(Double.POSITIVE_INFINITY));
    }

    @Test void stalePreCommandObservationIsDeferred() {
        assertTrue(SystemMasterSync.shouldDefer(false, 1_000, 1_000),
                "a snapshot captured at the command write time is stale");
        assertTrue(SystemMasterSync.shouldDefer(false, 999, 1_000));
        assertTrue(SystemMasterSync.shouldDefer(true, 2_000, 1_000),
                "no sync runs while our own audio command is in flight");
        assertFalse(SystemMasterSync.shouldDefer(false, 1_001, 1_000),
                "the first snapshot captured after the write is fresh");
    }

    @Test void volumeIsClampedToTheDeviceRange() {
        assertEquals(0, SystemMasterSync.clamp(-0.4), 1e-9);
        assertEquals(1, SystemMasterSync.clamp(1.4), 1e-9);
        SystemMasterSync.Decision decision = SystemMasterSync.compare(1.7, false, 0.5, false);
        assertNotNull(decision);
        assertEquals(1, decision.volume(), 1e-9);
    }
}
