package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AudioMatrixEventsServiceTest {
    @Test void eventSinkHasStableNameAndNeitherLoopbackEndCanAutoconnectToPhysicalOutput() throws Exception {
        String script = Files.readString(Path.of("src/main/systemd/user/audiomatrix-events-loopback.sh"));
        String unit = Files.readString(Path.of("src/main/systemd/user/audiomatrix-events.service"));

        assertTrue(script.contains("audiomatrix_events"));
        assertTrue(script.contains("media.class"));
        assertTrue(script.contains("\"node.autoconnect\":false"));
        assertTrue(script.contains("audiomatrix_events_discard"));
        assertFalse(script.contains("target.object"), "the event service must not target a physical sink");
        assertTrue(unit.contains("WantedBy=default.target"));
        assertFalse(unit.contains("WantedBy=graphical-session.target"), "the package must not auto-enable the user service");
    }
}
