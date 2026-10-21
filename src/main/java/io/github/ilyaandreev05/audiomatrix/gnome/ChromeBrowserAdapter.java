package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Chrome tab controls are available only through the explicit, connected extension bridge. */
public final class ChromeBrowserAdapter implements BrowserAdapter {
    public static final String CAPTURE_APPLICATION_ID = "io.github.ilyaandreev05.audiomatrix.chrome-tab-capture";
    private final BrowserSocketServer socket;

    public ChromeBrowserAdapter() { this(null); }

    public ChromeBrowserAdapter(BrowserSocketServer socket) { this.socket = socket; }

    @Override public String id() { return "chrome"; }
    @Override public String displayName() { return "Chrome"; }

    @Override public boolean matches(String applicationId, String process, String applicationName) {
        String identity = (safe(applicationId) + " " + safe(process) + " " + safe(applicationName)).toLowerCase(Locale.ROOT);
        return identity.contains("chrome") || identity.contains("chromium") || identity.contains("brave");
    }

    @Override public Map<String, Object> capabilities(int streamCount, boolean capturedTabStream) {
        boolean connected = socket != null && !socket.snapshots().isEmpty();
        boolean captured = capturedTabStream && streamCount > 0;
        String reason = captured ? "Отдельный поток захваченной вкладки доступен для регулировки."
                : connected ? "Доступны вкладки и общая громкость Chrome; независимая громкость вкладки появится после явного захвата."
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

    private static String safe(String value) { return value == null ? "" : value; }
}
