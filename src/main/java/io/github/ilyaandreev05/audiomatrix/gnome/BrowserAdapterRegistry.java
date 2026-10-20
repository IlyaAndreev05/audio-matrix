package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.List;
import java.util.Map;

final class BrowserAdapterRegistry {
    private static final List<BrowserAdapter> ADAPTERS = List.of(new ChromeBrowserAdapter(), new FirefoxBrowserAdapter());
    private BrowserAdapterRegistry() {}

    static Map<String, Object> capabilities(String applicationId, String process, String applicationName,
                                            int streamCount) {
        BrowserAdapter adapter = ADAPTERS.stream().filter(item ->
                item.matches(applicationId, process, applicationName)).findFirst().orElse(null);
        if (adapter == null) return Map.of();
        boolean captured = ChromeBrowserAdapter.CAPTURE_APPLICATION_ID.equals(applicationId);
        return adapter.capabilities(streamCount, captured);
    }
}
