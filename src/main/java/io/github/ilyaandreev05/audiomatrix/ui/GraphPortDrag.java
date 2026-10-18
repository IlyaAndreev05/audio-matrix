package io.github.ilyaandreev05.audiomatrix.ui;

import javafx.scene.control.Button;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.function.BiConsumer;

/** Drag source and target helpers for typed graph ports. */
final class GraphPortDrag {
    private static final String PREFIX = "audio-matrix-port:";

    private GraphPortDrag() {}

    static void installOutput(Button button, String nodeId, String portId) {
        button.setOnDragDetected(event -> {
            Dragboard board = button.startDragAndDrop(TransferMode.LINK);
            ClipboardContent content = new ClipboardContent();
            content.putString(encode(nodeId, portId));
            board.setContent(content);
            event.consume();
        });
    }

    static void installInput(Button button, BiConsumer<String, String> connect) {
        button.setOnDragOver(event -> {
            if (event.getGestureSource() != button && event.getDragboard().hasString()
                    && decode(event.getDragboard().getString()).isPresent())
                event.acceptTransferModes(TransferMode.LINK);
            event.consume();
        });
        button.setOnDragDropped(event -> {
            Optional<Endpoint> source = event.getDragboard().hasString()
                    ? decode(event.getDragboard().getString()) : Optional.empty();
            source.ifPresent(endpoint -> connect.accept(endpoint.nodeId(), endpoint.portId()));
            event.setDropCompleted(source.isPresent());
            event.consume();
        });
    }

    static String encode(String nodeId, String portId) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return PREFIX + encoder.encodeToString(nodeId.getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(portId.getBytes(StandardCharsets.UTF_8));
    }

    static Optional<Endpoint> decode(String value) {
        if (value == null || !value.startsWith(PREFIX)) return Optional.empty();
        try {
            String[] fields = value.substring(PREFIX.length()).split("\\.", -1);
            if (fields.length != 2) return Optional.empty();
            Base64.Decoder decoder = Base64.getUrlDecoder();
            String nodeId = new String(decoder.decode(fields[0]), StandardCharsets.UTF_8);
            String portId = new String(decoder.decode(fields[1]), StandardCharsets.UTF_8);
            if (nodeId.isBlank() || portId.isBlank()) return Optional.empty();
            return Optional.of(new Endpoint(nodeId, portId));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    record Endpoint(String nodeId, String portId) {}
}
