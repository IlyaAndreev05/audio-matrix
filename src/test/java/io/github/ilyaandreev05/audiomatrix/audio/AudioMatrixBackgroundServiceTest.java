package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AudioMatrixBackgroundServiceTest {
    @Test void userServiceRunsTheSingleInstanceAndDoesNotChangeTheSystemOutput() throws Exception {
        String unit = Files.readString(Path.of("src/main/systemd/user/audiomatrix.service"));
        String sinkUnit = Files.readString(Path.of("src/main/systemd/user/audiomatrix-system-sink.service"));
        assertTrue(unit.contains("ExecStart=/opt/audiomatrix/bin/AudioMatrix --background"));
        assertTrue(unit.contains("ExecStop=/opt/audiomatrix/bin/AudioMatrix --quit"));
        assertTrue(unit.contains("Requires=audiomatrix-system-sink.service"));
        assertTrue(unit.contains("Restart=on-failure"));
        assertTrue(unit.contains("WantedBy=graphical-session.target"));
        assertFalse(unit.contains("set-default"));
        assertFalse(unit.contains("set-volume"));
        assertFalse(unit.contains("set-mute"));
        assertTrue(sinkUnit.contains("ExecStart=/bin/sh /opt/audiomatrix/bin/audiomatrix-system-sink.sh"));
        assertTrue(sinkUnit.contains("PartOf=graphical-session.target"));
    }

    @Test void serviceHelpersBackUpUserStateAndUseOnlyUserServices() throws Exception {
        String enable = Files.readString(Path.of("scripts/enable-audiomatrix-service.sh"));
        String disable = Files.readString(Path.of("scripts/disable-audiomatrix-service.sh"));
        assertTrue(enable.contains("systemctl --user enable --now audiomatrix.service"));
        assertTrue(enable.contains("systemctl --user restart pipewire.service"));
        assertTrue(enable.contains("previous-system-sink"));
        assertTrue(disable.contains("systemctl --user disable --now audiomatrix.service"));
        assertTrue(disable.contains("pipewire-config-backup"));
        assertFalse(enable.contains("sudo "));
        assertFalse(disable.contains("sudo "));
        assertFalse(enable.contains("pactl set-default-sink"));
        assertFalse(disable.contains("wpctl set-default"));
    }
}
