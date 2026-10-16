package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;
import java.util.Objects;

/** A named, directional audio port with an explicit channel layout. */
public record AudioPort(String id, Direction direction, List<String> channels, boolean acceptsMultiple) {
    public AudioPort {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Port id must not be blank");
        Objects.requireNonNull(direction, "direction");
        channels = List.copyOf(channels);
        if (channels.isEmpty()) throw new IllegalArgumentException("A port needs at least one channel");
        if (channels.stream().distinct().count() != channels.size()) throw new IllegalArgumentException("Port channels must be unique");
    }

    public enum Direction { INPUT, OUTPUT }
}
