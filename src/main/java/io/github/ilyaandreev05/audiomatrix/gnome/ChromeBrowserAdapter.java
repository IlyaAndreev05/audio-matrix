package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Chrome tab controls are available only through the explicit, connected extension bridge. */
public final class ChromeBrowserAdapter implements BrowserAdapter {
    public static final String CAPTURE_APPLICATION_ID = "io.github.ilyaandreev05.audiomatrix.chrome-tab-capture";
    private static final Set<String> CHROME_APPLICATION_IDS = Set.of(
            "chrome", "chrome.desktop", "google-chrome", "google-chrome.desktop",
            "google-chrome-stable", "google-chrome-stable.desktop", "google-chrome-beta",
            "google-chrome-unstable", "com.google.chrome", "com.google.chrome.desktop");
    private static final Set<String> CHROME_PROCESS_NAMES = Set.of(
            "chrome", "google-chrome", "google-chrome-stable", "google-chrome-beta", "google-chrome-unstable");
    private static final Set<String> CHROME_DISPLAY_NAMES = Set.of(
            "chrome", "google chrome", "google chrome stable", "google chrome beta",
            "google chrome dev", "google chrome unstable");
    private static final Set<String> OTHER_BROWSER_IDENTITIES = Set.of(
            "chromium", "chromium-browser", "chromium-browser.desktop",
            "org.chromium.chromium", "org.chromium.chromium.desktop",
            "brave", "brave-browser", "brave-browser.desktop", "com.brave.browser", "brave browser",
            "firefox", "firefox-esr", "org.mozilla.firefox", "org.mozilla.firefox.desktop",
            "mozilla firefox", "firefox browser");
    private final BrowserSocketServer socket;

    public ChromeBrowserAdapter() { this(null); }

    public ChromeBrowserAdapter(BrowserSocketServer socket) { this.socket = socket; }

    @Override public String id() { return "chrome"; }
    @Override public String displayName() { return "Chrome"; }

    @Override public boolean matches(String applicationId, String process, String applicationName) {
        String appId = normalize(applicationId);
        String processName = normalizeExecutable(process);
        String displayName = normalize(applicationName);
        if (CAPTURE_APPLICATION_ID.equals(appId)
                || OTHER_BROWSER_IDENTITIES.contains(appId)
                || OTHER_BROWSER_IDENTITIES.contains(processName)
                || OTHER_BROWSER_IDENTITIES.contains(displayName)) return false;
        return CHROME_APPLICATION_IDS.contains(appId)
                || CHROME_PROCESS_NAMES.contains(processName)
                || CHROME_DISPLAY_NAMES.contains(displayName);
    }

    @Override public Map<String, Object> capabilities(int streamCount, boolean capturedTabStream) {
        boolean connected = socket != null && !socket.snapshots().isEmpty();
        boolean captured = capturedTabStream && (streamCount == 0 || streamCount == 1);
        String reason = captured ? "Отдельный поток захваченной вкладки доступен для регулировки."
                : connected ? "Доступны вкладки и общая громкость Chrome; независимая громкость вкладки требует подтверждённого единственного потока захваченной вкладки."
                : "Доступна общая громкость Chrome; подключите расширение для управления вкладками и явного захвата.";
        return Map.of("browser", "Chrome", "tabMetadata", connected, "tabMute", connected,
                "tabCapture", true, "captureRequiresUserGesture", true, "streamVolume", streamCount > 0,
                "independentTabVolume", captured, "reason", reason);
    }

    @Override public List<BrowserSnapshot> snapshots() { return socket == null ? List.of() : socket.snapshots(); }

    @Override public void addListener(Consumer<BrowserEvent> listener) {
        if (socket != null) socket.addListener(listener);
    }
    @Override public void removeListener(Consumer<BrowserEvent> listener) {
        if (socket != null) socket.removeListener(listener);
    }

    @Override public CompletableFuture<BrowserCommandAck> command(BrowserCommand command) {
        return socket == null
                ? CompletableFuture.failedFuture(new IllegalStateException("The Chrome browser bridge is unavailable."))
                : socket.command(command);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
    private static String normalizeExecutable(String value) {
        String name = normalize(value);
        int separator = name.lastIndexOf('/');
        return name.substring(separator + 1);
    }
}
