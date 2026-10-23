package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/** Firefox supplies real tab metadata and mute through its optional extension, never independent PCM. */
public final class FirefoxBrowserAdapter implements BrowserAdapter {
    private static final Set<String> COMMANDS = Set.of("mute", "focus");
    private static final Set<String> APPLICATION_IDS = Set.of("firefox", "firefox.desktop", "firefox-esr",
            "firefox-esr.desktop", "org.mozilla.firefox", "org.mozilla.firefox.desktop",
            "org.mozilla.firefoxdeveloperedition", "org.mozilla.firefoxdeveloperedition.desktop");
    private static final Set<String> PROCESS_NAMES = Set.of("firefox", "firefox-esr", "firefox-bin");
    private static final Set<String> DISPLAY_NAMES = Set.of("firefox", "mozilla firefox", "firefox browser",
            "firefox developer edition", "firefox nightly");
    private final BrowserSocketServer socket;
    private final ConcurrentMap<Consumer<BrowserEvent>, Consumer<BrowserEvent>> listeners = new ConcurrentHashMap<>();

    public FirefoxBrowserAdapter() { this(null); }
    public FirefoxBrowserAdapter(BrowserSocketServer socket) { this.socket = socket; }

    @Override public String id() { return "firefox"; }
    @Override public String displayName() { return "Firefox"; }

    @Override public boolean matches(String applicationId, String process, String applicationName) {
        String executable = normalize(process);
        int separator = executable.lastIndexOf('/');
        return APPLICATION_IDS.contains(normalize(applicationId))
                || PROCESS_NAMES.contains(executable.substring(separator + 1))
                || DISPLAY_NAMES.contains(normalize(applicationName));
    }

    @Override public Map<String, Object> capabilities(int streamCount, boolean capturedTabStream) {
        boolean connected = !snapshots().isEmpty();
        return Map.of("browser", "Firefox", "tabMetadata", connected, "tabMute", connected,
                "tabCapture", false, "captureRequiresUserGesture", false, "streamVolume", streamCount > 0,
                "independentTabVolume", false,
                "reason", connected
                        ? "Доступны реальные вкладки Firefox и выключение их звука. Firefox не предоставляет независимую PCM-громкость вкладки."
                        : "Доступна общая громкость Firefox. Подключите дополнение Audio Matrix и локальный мост для списка вкладок и выключения их звука.");
    }

    @Override public List<BrowserSnapshot> snapshots() {
        return socket == null ? List.of() : socket.snapshots().stream()
                .filter(snapshot -> id().equals(snapshot.browser())).toList();
    }

    @Override public void addListener(Consumer<BrowserEvent> listener) {
        if (socket == null || listener == null) return;
        Consumer<BrowserEvent> filtered = event -> {
            if (id().equals(event.browser())) listener.accept(event);
        };
        if (listeners.putIfAbsent(listener, filtered) == null) socket.addListener(filtered);
    }

    @Override public void removeListener(Consumer<BrowserEvent> listener) {
        Consumer<BrowserEvent> filtered = listeners.remove(listener);
        if (socket != null && filtered != null) socket.removeListener(filtered);
    }

    @Override public CompletableFuture<BrowserCommandAck> command(BrowserCommand command) {
        if (socket == null)
            return CompletableFuture.failedFuture(new IllegalStateException("Локальный мост Firefox не подключён."));
        if (command == null || !id().equals(command.browser())
                || !COMMANDS.contains(command.command()))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Firefox поддерживает только выключение звука и открытие вкладки."));
        return socket.command(command);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
