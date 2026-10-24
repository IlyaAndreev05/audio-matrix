package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.ilyaandreev05.audiomatrix.gnome.GnomeBridgeTestSupport.*;

/** Presentation commands keep their own completion without blocking the audio command tail. */
class GnomeBridgePresentationCommandTest {
    @Test void aPendingOpenWindowNeverBlocksALaterVolumeCommand() throws Exception {
        LinkedBlockingQueue<Signal> signals = new LinkedBlockingQueue<>();
        CompletableFuture<Void> windowShown = new CompletableFuture<>();
        try (GnomeControlBridge bridge = new GnomeControlBridge(command -> "open".equals(command.name())
                ? windowShown : CompletableFuture.completedFuture(null), List.of(),
                command -> CompletableFuture.completedFuture(null),
                (name, signature, values) -> signals.add(new Signal(name, signature, values)))) {
            bridge.emitObservedState(Map.of("revision", "1", "volume", 0.8));
            next(signals);
            CompletableFuture<Void> open = bridge.dispatch(open("client:open"));
            CompletableFuture<Void> volume = bridge.dispatch(volume("client:volume", 0.4));
            volume.get(2, TimeUnit.SECONDS);
            JsonNode volumeAck = state(next(signals));
            assertEquals("client:volume", volumeAck.path("commandAcks").get(0).path("requestId").asText(),
                    "the volume acknowledgment does not wait for the presentation command");
            assertEquals("client:volume", next(signals).arguments().get(1));
            assertFalse(open.isDone(), "the pending window still owns its own completion");
            windowShown.complete(null);
            open.get(2, TimeUnit.SECONDS);
            JsonNode openAck = state(next(signals));
            // Acknowledgments are cumulative: the volume request stays at index zero.
            assertEquals("client:open", openAck.path("commandAcks").get(1).path("requestId").asText());
            assertEquals("client:open", next(signals).arguments().get(1));
        }
    }

    @Test void aFailedOpenDoesNotPoisonTheAudioCommandTail() throws Exception {
        LinkedBlockingQueue<Signal> signals = new LinkedBlockingQueue<>();
        try (GnomeControlBridge bridge = new GnomeControlBridge(command -> "open".equals(command.name())
                ? CompletableFuture.failedFuture(new IllegalStateException("Window unavailable"))
                : CompletableFuture.completedFuture(null), List.of(),
                command -> CompletableFuture.completedFuture(null),
                (name, signature, values) -> signals.add(new Signal(name, signature, values)))) {
            bridge.emitObservedState(Map.of("revision", "1", "volume", 0.8));
            next(signals);
            bridge.dispatch(open("client:open")).get(2, TimeUnit.SECONDS);
            JsonNode failedOpen = state(next(signals));
            assertEquals("error", failedOpen.path("commandAcks").get(0).path("status").asText());
            assertEquals("Window unavailable", failedOpen.path("commandAcks").get(0).path("error").asText());
            assertEquals("error", next(signals).arguments().get(2));
            bridge.dispatch(volume("client:volume", 0.4)).get(2, TimeUnit.SECONDS);
            JsonNode accepted = state(next(signals));
            assertEquals("ok", accepted.path("commandAcks").get(1).path("status").asText(),
                    "a failed presentation command keeps the audio queue usable");
            assertEquals("ok", next(signals).arguments().get(2));
        }
    }
}
