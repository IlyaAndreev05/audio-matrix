package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.Map;

/** Browser integration contract: describe only controls that the browser or PipeWire actually exposes. */
public interface BrowserAdapter {
    String id();
    boolean matches(String applicationId, String process, String applicationName);
    Map<String, Object> capabilities(int streamCount, boolean capturedTabStream);
}
