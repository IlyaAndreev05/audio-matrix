package io.github.ilyaandreev05.audiomatrix.gnome;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrowserAdapterTest {
    @Test void chromeMatchesKnownApplicationIdsProcessesAndDisplayNames() {
        ChromeBrowserAdapter adapter = new ChromeBrowserAdapter();
        assertEquals("chrome", adapter.id());
        assertEquals("chrome", adapter.applicationId());
        assertEquals("Chrome", adapter.displayName());

        assertTrue(adapter.matches("google-chrome", "chrome", "Google Chrome"));
        assertTrue(adapter.matches("com.google.Chrome", null, null));
        assertTrue(adapter.matches(null, "/opt/google/chrome/chrome", null));
        assertTrue(adapter.matches(null, null, "Google Chrome"));
    }

    @Test void chromeDoesNotClaimOtherBrowsersOrItsCaptureHostAsBrowserParent() {
        ChromeBrowserAdapter adapter = new ChromeBrowserAdapter();

        assertFalse(adapter.matches("org.chromium.Chromium", "chromium", "Chromium"));
        assertFalse(adapter.matches("com.brave.Browser", "brave", "Brave Browser"));
        assertFalse(adapter.matches("org.mozilla.firefox", "firefox", "Firefox"));
        assertFalse(adapter.matches(ChromeBrowserAdapter.CAPTURE_APPLICATION_ID, "chrome", "Google Chrome"));
        assertFalse(adapter.matches("org.chromium.Chromium", "chrome", "Google Chrome"));
        assertTrue(BrowserAdapterRegistry.capabilities("org.chromium.Chromium", "chromium", "Chromium", 2).isEmpty());
        assertTrue(BrowserAdapterRegistry.capabilities("com.brave.Browser", "brave", "Brave Browser", 2).isEmpty());
    }

    @Test void chromeReportsIndependentVolumeOnlyForConfirmedUnambiguousCapture() {
        ChromeBrowserAdapter adapter = new ChromeBrowserAdapter();
        var shared = adapter.capabilities(3, false);
        var captured = adapter.capabilities(1, true);
        var capturedWithoutParentStreams = adapter.capabilities(0, true);
        var unconfirmed = adapter.capabilities(1, false);
        var ambiguous = adapter.capabilities(2, true);

        assertTrue((Boolean) shared.get("tabCapture"));
        assertFalse((Boolean) shared.get("tabMetadata"));
        assertFalse((Boolean) shared.get("tabMute"));
        assertTrue((Boolean) shared.get("captureRequiresUserGesture"));
        assertTrue((Boolean) shared.get("streamVolume"));
        assertFalse((Boolean) shared.get("independentTabVolume"));
        assertTrue((Boolean) captured.get("independentTabVolume"));
        assertTrue((Boolean) capturedWithoutParentStreams.get("independentTabVolume"));
        assertFalse((Boolean) capturedWithoutParentStreams.get("streamVolume"));
        assertFalse((Boolean) unconfirmed.get("independentTabVolume"));
        assertFalse((Boolean) ambiguous.get("independentTabVolume"));
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
