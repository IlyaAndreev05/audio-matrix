package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.List;
import java.util.Map;

/** Creates the shared browser adapters over the same owner-only browser socket endpoint. */
public final class BrowserAdapterRegistry {
    private BrowserAdapterRegistry() {}

    public static List<BrowserAdapter> createAdapters(BrowserSocketServer socket) {
        return List.of(new ChromeBrowserAdapter(socket), new FirefoxBrowserAdapter(socket));
    }

    public static Map<String, Object> capabilities(String applicationId, String process, String applicationName,
                                                   int streamCount) {
        BrowserAdapter adapter = createAdapters(null).stream().filter(item ->
                item.matches(applicationId, process, applicationName)).findFirst().orElse(null);
        if (adapter == null) return Map.of();
        boolean captured = streamCount > 0 && ChromeBrowserAdapter.CAPTURE_APPLICATION_ID.equals(applicationId);
        return adapter.capabilities(streamCount, captured);
    }
}
