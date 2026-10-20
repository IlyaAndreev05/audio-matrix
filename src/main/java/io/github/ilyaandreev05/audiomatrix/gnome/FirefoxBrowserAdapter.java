package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.Locale;
import java.util.Map;

/** Firefox exposes tab title/focus/mute through the optional extension, but not tab capture. */
public final class FirefoxBrowserAdapter implements BrowserAdapter {
    @Override public String id() { return "firefox"; }

    @Override public boolean matches(String applicationId, String process, String applicationName) {
        String identity = (safe(applicationId) + " " + safe(process) + " " + safe(applicationName)).toLowerCase(Locale.ROOT);
        return identity.contains("firefox") || identity.contains("mozilla");
    }

    @Override public Map<String, Object> capabilities(int streamCount, boolean capturedTabStream) {
        boolean separateStreams = streamCount > 1;
        String reason = separateStreams ? "Firefox предоставляет несколько независимых системных потоков."
                : "Расширение Firefox может показывать вкладки и менять их mute в браузере; Audio Matrix получает только системные потоки, отдельный ползунок вкладки появится лишь при нескольких независимых потоках.";
        return Map.of("browser", "Firefox", "tabMetadata", true, "tabMute", true,
                "tabCapture", false, "captureRequiresUserGesture", false, "streamVolume", streamCount > 0,
                "independentTabVolume", separateStreams, "reason", reason);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
