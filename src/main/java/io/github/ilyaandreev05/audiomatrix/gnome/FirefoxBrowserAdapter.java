package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.Locale;
import java.util.Map;

/** Firefox remains an ordinary PipeWire application; Audio Matrix does not bridge its tabs. */
public final class FirefoxBrowserAdapter implements BrowserAdapter {
    @Override public String id() { return "firefox"; }
    @Override public String displayName() { return "Firefox"; }

    @Override public boolean matches(String applicationId, String process, String applicationName) {
        String identity = (safe(applicationId) + " " + safe(process) + " " + safe(applicationName)).toLowerCase(Locale.ROOT);
        return identity.contains("firefox") || identity.contains("mozilla");
    }

    @Override public Map<String, Object> capabilities(int streamCount, boolean capturedTabStream) {
        return Map.of("browser", "Firefox", "tabMetadata", false, "tabMute", false,
                "tabCapture", false, "captureRequiresUserGesture", false, "streamVolume", streamCount > 0,
                "independentTabVolume", false,
                "reason", "Firefox остаётся обычным приложением: доступны только системные аудиопотоки, без управления вкладками.");
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
