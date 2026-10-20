package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;
import java.util.Map;

/** A registry-backed source that expands to matching application streams as they appear. */
public final class DynamicGeneratorNode extends SourceNode {
    public DynamicGeneratorNode(String id, String name, String selector, List<String> channels) {
        super(id, name, selector, channels);
    }

    @Override public String type() { return "dynamic_generator"; }

    @Override public Map<String, Object> parameters() {
        return Map.of("selector", selector(), "registry", "applications", "template", "default-application");
    }
}
