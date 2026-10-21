package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;
import java.util.Map;

/** Shared fixtures for the graph test suite. */
final class GraphTestFixtures {
    static final List<String> STEREO = List.of("FL", "FR");

    private GraphTestFixtures() {}

    static GraphConnection link(String from, String fromPort, String to, String toPort) {
        return new GraphConnection("link-" + from + "-" + to, from, fromPort, to, toPort, true, 1);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
