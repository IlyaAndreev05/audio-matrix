package io.github.ilyaandreev05.audiomatrix.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** A defensive config snapshot paired atomically with its in-memory revision. */
public final class VersionedConfig extends LinkedHashMap<String, Object> {
    private static final long serialVersionUID = 1L;
    private final long revision;

    VersionedConfig(Map<String, Object> value, long revision) {
        super(value);
        this.revision = revision;
    }

    public long revision() {
        return revision;
    }
}
