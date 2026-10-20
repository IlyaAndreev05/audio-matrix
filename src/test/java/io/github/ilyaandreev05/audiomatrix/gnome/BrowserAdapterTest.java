package io.github.ilyaandreev05.audiomatrix.gnome;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrowserAdapterTest {
    @Test void chromeRequiresUserGestureAndReportsPerTabVolumeOnlyForIndependentStreams() {
        ChromeBrowserAdapter adapter = new ChromeBrowserAdapter();

        var shared = adapter.capabilities(1, false);
        var captured = adapter.capabilities(1, true);

        assertTrue((Boolean) shared.get("tabCapture"));
        assertTrue((Boolean) shared.get("captureRequiresUserGesture"));
        assertFalse((Boolean) shared.get("independentTabVolume"));
        assertTrue((Boolean) captured.get("independentTabVolume"));
        assertTrue(String.valueOf(shared.get("reason")).contains("общая громкость"));
    }

    @Test void firefoxExplainsItsTabVolumeLimitAndUsesIndependentSystemStreamsWhenPresent() {
        FirefoxBrowserAdapter adapter = new FirefoxBrowserAdapter();

        var one = adapter.capabilities(1, false);
        var two = adapter.capabilities(2, false);

        assertTrue((Boolean) one.get("tabMetadata"));
        assertTrue((Boolean) one.get("tabMute"));
        assertFalse((Boolean) one.get("tabCapture"));
        assertFalse((Boolean) one.get("independentTabVolume"));
        assertTrue(String.valueOf(one.get("reason")).contains("mute"));
        assertTrue((Boolean) two.get("independentTabVolume"));
    }
}
