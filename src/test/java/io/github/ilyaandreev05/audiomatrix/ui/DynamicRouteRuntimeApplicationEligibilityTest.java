package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.CapturedStreamBinding;
import io.github.ilyaandreev05.audiomatrix.gnome.ChromeBrowserAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicRouteRuntimeApplicationEligibilityTest {
    @Test
    void dynamicApplicationDiscoveryExcludesInternalStreamsAndKeepsDesktopPlayback() {
        AudioNode firefox = stream(1, "Firefox", "Firefox", "firefox", "org.mozilla.firefox");
        AudioNode capturedTab = stream(2, CapturedStreamBinding.NODE_PREFIX + "session-1",
                "Chrome Tab Capture", "capture-host", ChromeBrowserAdapter.CAPTURE_APPLICATION_ID);
        AudioNode shellSound = stream(3, "gnome-shell", "GNOME Shell", "gnome-shell", "");
        AudioNode portalSound = stream(4, "portal", "xdg-desktop-portal", "xdg-desktop-portal", "");
        AudioNode eventSound = stream(5, "events", "Event Sound Studio", "eventsoundstudio",
                "io.github.ilyaandreev05.eventsoundstudio");

        assertFalse(DynamicRouteRuntime.excluded(firefox), "A real desktop app remains discoverable");
        assertTrue(DynamicRouteRuntime.excluded(capturedTab),
                "Captured browser PCM is owned by its tab binding, not an ordinary app instance");
        assertTrue(DynamicRouteRuntime.excluded(shellSound), "Transient GNOME shell sounds are not app instances");
        assertTrue(DynamicRouteRuntime.excluded(portalSound), "Desktop portal sounds are not app instances");
        assertTrue(DynamicRouteRuntime.excluded(eventSound), "Event Sound Studio remains on its independent branch");
    }

    private static AudioNode stream(int id, String name, String application, String process, String applicationId) {
        return new AudioNode(id, name, name, "Stream/Output/Audio", "running", application, process,
                applicationId, 2, List.of(48_000), 1.0, false, null);
    }
}
