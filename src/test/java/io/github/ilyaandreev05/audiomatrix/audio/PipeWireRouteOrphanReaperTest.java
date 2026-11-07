package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireRouteOrphanReaperTest {
    @Test
    void matchesOnlyHelpersStartedFromAnOwnedTemporaryConfiguration() {
        assertTrue(PipeWireRoutePart02.isOrphanedOwnedProcess("pipewire -c /tmp/audio-matrix-filter-1.conf"));
        assertTrue(PipeWireRoutePart02.isOrphanedOwnedProcess(
                "/usr/bin/pipewire -c /tmp/audio-matrix-matrix-42.conf"));
        assertTrue(PipeWireRoutePart02.isOrphanedOwnedProcess(
                "pw-loopback -n audiomatrix_route_x -o @/tmp/audio-matrix-route.conf"));
    }

    @Test
    void ignoresTheSharedLoopbacksAndUnrelatedCommands() {
        assertFalse(PipeWireRoutePart02.isOrphanedOwnedProcess("pw-loopback -n audiomatrix_system"));
        assertFalse(PipeWireRoutePart02.isOrphanedOwnedProcess("pw-loopback -n audiomatrix_events -l 10 -c 2"));
        assertFalse(PipeWireRoutePart02.isOrphanedOwnedProcess("/usr/bin/pipewire -c filter-chain.conf"));
        assertFalse(PipeWireRoutePart02.isOrphanedOwnedProcess("pipewire"));
        assertFalse(PipeWireRoutePart02.isOrphanedOwnedProcess(""));
        assertFalse(PipeWireRoutePart02.isOrphanedOwnedProcess(null));
    }
}
