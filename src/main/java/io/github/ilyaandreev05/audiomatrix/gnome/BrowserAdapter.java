package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Browser metadata and commands backed only by capabilities the browser actually exposes. */
public interface BrowserAdapter {
    String id();
    /** Logical browser-parent identity only; never a PipeWire applicationId or application-rule key. */
    default String applicationId() { return id(); }
    default String displayName() { return id(); }
    boolean matches(String applicationId, String process, String applicationName);
    Map<String, Object> capabilities(int streamCount, boolean capturedTabStream);

    default List<BrowserSnapshot> snapshots() { return List.of(); }
    default void addListener(Consumer<BrowserEvent> listener) {}
    default void removeListener(Consumer<BrowserEvent> listener) {}
    default CompletableFuture<BrowserCommandAck> command(BrowserCommand command) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("This browser adapter has no browser commands."));
    }

    record BrowserSnapshot(String browser, String instanceId, String profileId, List<BrowserTab> tabs) {
        public BrowserSnapshot { tabs = List.copyOf(tabs); }
    }

    /** `tabs` contains full membership for snapshot, or affected rows for an incremental event. */
    record BrowserEvent(String type, String browser, String instanceId, String profileId, List<BrowserTab> tabs) {
        public BrowserEvent { tabs = List.copyOf(tabs); }
    }

    /** tabId is transient; captured volume is represented only by a confirmed stream key. */
    record BrowserTab(String sessionToken, int tabId, String title, boolean audible, boolean muted,
                      String volumeCapability, String captureStreamKey) {}

    /** Commands are keyed by browser/profile/session identity, never by the transient tabId. */
    record BrowserCommand(String requestId, String browser, String instanceId, String profileId,
                          String sessionToken, String command, Boolean muted) {}

    record BrowserCommandAck(String requestId, String status, String error) {}
}
