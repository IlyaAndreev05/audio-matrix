package io.github.ilyaandreev05.audiomatrix.graph;

/** A saved connection between stable node and port ids. */
public record GraphConnection(String id, String fromNode, String fromPort, String toNode, String toPort,
                              boolean enabled, double gain) {
    public GraphConnection {
        if (id == null || id.isBlank() || fromNode == null || fromPort == null || toNode == null || toPort == null)
            throw new IllegalArgumentException("Connection ids and endpoints must not be blank");
        if (!Double.isFinite(gain) || gain < 0 || gain > 1) throw new IllegalArgumentException("Connection gain must be between 0 and 1");
    }
}
