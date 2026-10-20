package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.Locale;
import java.util.Map;

/** Chrome extension can list and mute tabs; captured tabs become real, separately controlled PipeWire streams. */
public final class ChromeBrowserAdapter implements BrowserAdapter {
    public static final String CAPTURE_APPLICATION_ID = "io.github.ilyaandreev05.audiomatrix.chrome-tab-capture";

    @Override public String id() { return "chrome"; }

    @Override public boolean matches(String applicationId, String process, String applicationName) {
        String identity = (safe(applicationId) + " " + safe(process) + " " + safe(applicationName)).toLowerCase(Locale.ROOT);
        return identity.contains("chrome") || identity.contains("chromium") || identity.contains("brave");
    }

    @Override public Map<String, Object> capabilities(int streamCount, boolean capturedTabStream) {
        boolean separateStreams = streamCount > 1 || capturedTabStream;
        String reason = capturedTabStream ? "Отдельный поток захваченной вкладки доступен для регулировки."
                : separateStreams ? "Браузер предоставляет несколько независимых системных потоков."
                : "Доступна общая громкость Chrome; отдельный поток вкладки появится после запуска захвата из расширения.";
        return Map.of("browser", "Chrome", "tabMetadata", true, "tabMute", true,
                "tabCapture", true, "captureRequiresUserGesture", true, "streamVolume", streamCount > 0,
                "independentTabVolume", separateStreams, "reason", reason);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
