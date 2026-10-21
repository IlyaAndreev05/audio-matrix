package io.github.ilyaandreev05.audiomatrix.gnome;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrowserAdapterTest {
    @Test void chromeRequiresUserGestureAndReportsIndependentVolumeOnlyForConfirmedCapture() {
        ChromeBrowserAdapter adapter = new ChromeBrowserAdapter();
        assertEquals("chrome", adapter.applicationId());
        assertEquals("Chrome", adapter.displayName());

        var shared = adapter.capabilities(3, false);
        var captured = adapter.capabilities(1, true);
        var inactiveCapture = adapter.capabilities(0, true);

        assertTrue((Boolean) shared.get("tabCapture"));
        assertFalse((Boolean) shared.get("tabMetadata"));
        assertFalse((Boolean) shared.get("tabMute"));
        assertTrue((Boolean) shared.get("captureRequiresUserGesture"));
        assertTrue((Boolean) shared.get("streamVolume"));
        assertFalse((Boolean) shared.get("independentTabVolume"));
        assertTrue((Boolean) captured.get("independentTabVolume"));
        assertFalse((Boolean) inactiveCapture.get("independentTabVolume"));
        assertTrue(String.valueOf(shared.get("reason")).contains("общая громкость"));
    }

    @Test void firefoxKeepsOrdinaryStreamVolumeWithoutClaimingTabControls() {
        FirefoxBrowserAdapter adapter = new FirefoxBrowserAdapter();

        var one = adapter.capabilities(1, false);
        var many = adapter.capabilities(4, false);

        assertFalse((Boolean) one.get("tabMetadata"));
        assertFalse((Boolean) one.get("tabMute"));
        assertFalse((Boolean) one.get("tabCapture"));
        assertTrue((Boolean) one.get("streamVolume"));
        assertTrue((Boolean) many.get("streamVolume"));
        assertFalse((Boolean) one.get("independentTabVolume"));
        assertFalse((Boolean) many.get("independentTabVolume"));
    }
}
