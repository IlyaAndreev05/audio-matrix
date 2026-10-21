package io.github.ilyaandreev05.audiomatrix.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** Holds the live configuration document, its fingerprint and the retained revision baselines. */
final class ConfigStoreDocument {
    private static final int REVISION_HISTORY_LIMIT = 128;
    private final LinkedHashMap<Long, Map<String, Object>> revisionHistory = new LinkedHashMap<>();
    private volatile Map<String, Object> current;
    private volatile String fingerprint;
    private volatile long revision;
    private volatile String diagnostic = "";

    Map<String, Object> current() { return current; }
    long revision() { return revision; }
    String fingerprint() { return fingerprint; }
    String diagnostic() { return diagnostic; }
    void diagnostic(String message) { diagnostic = message; }

    /** Returns a detached copy of the live document. */
    Map<String, Object> copy() { return ConfigFileIO.deepCopy(current); }

    /** Returns a defensive copy of a retained revision, or {@code null} when it was evicted. */
    Map<String, Object> snapshotAtRevision(long requestedRevision) {
        Map<String, Object> baseline = revisionHistory.get(requestedRevision);
        return baseline == null ? null : ConfigFileIO.deepCopy(baseline);
    }

    void commit(Map<String, Object> next, String newFingerprint) {
        current = ConfigFileIO.deepCopy(next);
        fingerprint = newFingerprint;
        revision++;
        rememberRevision();
        diagnostic = "";
    }

    private void rememberRevision() {
        revisionHistory.put(revision, ConfigFileIO.deepCopy(current));
        while (revisionHistory.size() > REVISION_HISTORY_LIMIT)
            revisionHistory.remove(revisionHistory.keySet().iterator().next());
    }
}
