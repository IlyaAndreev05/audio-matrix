package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GnomeBridgePersistedApplicationFailureTest {
    @Test void failedAppPersistenceAcknowledgesErrorWithoutRuntimeApplyAndDoesNotPoisonQueue() throws Exception {
        LinkedBlockingQueue<GnomeBridgeTestSupport.Signal> signals = new LinkedBlockingQueue<>();
        AtomicInteger confirmations = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        Map<String, CompletableFuture<Void>> persistence = Map.of(
                "ui:save-app", new CompletableFuture<>(),
                "ui:save-process", new CompletableFuture<>());
        try (GnomeControlBridge bridge = new GnomeControlBridge(command -> switch (command.name()) {
            case "set_app_volume", "toggle_app_mute" -> persistence.get(command.requestId());
            case "set_stream_volume" -> CompletableFuture.failedFuture(new IllegalStateException("stream disappeared"));
            default -> CompletableFuture.completedFuture(null);
        }, List.of(), command -> {
            confirmations.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }, (name, signature, arguments) -> signals.add(
                new GnomeBridgeTestSupport.Signal(name, signature, arguments)),
                command -> {
                    releases.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                })) {
            bridge.emitObservedState(Map.of("applications", List.of(
                    Map.of("id", "org.player", "volume", .8, "muted", false))));
            assertEquals("State", GnomeBridgeTestSupport.next(signals).name());

            List<GnomeControlBridge.Command> failedSaves = List.of(
                    new GnomeControlBridge.Command(5, "ui:save-app", "1",
                            "set_app_volume", "app:org.player\t0.35"),
                    new GnomeControlBridge.Command(5, "ui:save-process", "2",
                            "toggle_app_mute", "process:player"));
            int ackIndex = 0;
            for (GnomeControlBridge.Command failedSave : failedSaves) {
                CompletableFuture<Void> failedCompletion = bridge.dispatch(failedSave);
                persistence.get(failedSave.requestId()).completeExceptionally(
                        new IllegalStateException("Configuration write failed"));
                failedCompletion.get(2, TimeUnit.SECONDS);

                JsonNode failedState = GnomeBridgeTestSupport.state(GnomeBridgeTestSupport.next(signals));
                JsonNode failedAck = failedState.path("commandAcks").get(ackIndex++);
                assertEquals(failedSave.requestId(), failedAck.path("requestId").asText());
                assertEquals("error", failedAck.path("status").asText());
                assertEquals("Configuration write failed", failedAck.path("error").asText());
                assertEquals(.8, failedState.path("applications").get(0).path("volume").asDouble());
                GnomeBridgeTestSupport.Signal failedResult = GnomeBridgeTestSupport.next(signals);
                assertEquals("Result", failedResult.name());
                assertEquals(List.of("5", failedSave.requestId(), "error", "Configuration write failed"),
                        failedResult.arguments());
            }
            assertEquals(0, confirmations.get(), "Failed persist-first app/process edits must not apply saved runtime rules");
            assertEquals(2, releases.get(), "The shell guard must be released for every failed persist-first edit");

            CompletableFuture<Void> nextCompletion = bridge.dispatch(new GnomeControlBridge.Command(5,
                    "ui:next", "3", "set_output_volume", "output\t0.4"));
            nextCompletion.get(2, TimeUnit.SECONDS);
            JsonNode successState = GnomeBridgeTestSupport.state(GnomeBridgeTestSupport.next(signals));
            JsonNode successAck = successState.path("commandAcks").get(ackIndex++);
            assertEquals("ui:next", successAck.path("requestId").asText());
            assertEquals("ok", successAck.path("status").asText());
            GnomeBridgeTestSupport.Signal successResult = GnomeBridgeTestSupport.next(signals);
            assertEquals(List.of("5", "ui:next", "ok", "Applied"), successResult.arguments());
            assertEquals(1, confirmations.get(), "The next command still receives its normal confirmation");
            assertEquals(2, releases.get(), "A successful command must not take the error release path");

            CompletableFuture<Void> failedStream = bridge.dispatch(new GnomeControlBridge.Command(5,
                    "ui:stream-failed", "4", "set_stream_volume", "10\t0.35"));
            failedStream.get(2, TimeUnit.SECONDS);
            JsonNode streamFailureState = GnomeBridgeTestSupport.state(GnomeBridgeTestSupport.next(signals));
            JsonNode streamFailureAck = streamFailureState.path("commandAcks").get(ackIndex);
            assertEquals("error", streamFailureAck.path("status").asText());
            assertEquals(2, confirmations.get(), "The guard must not suppress other command kinds' state confirmation");
            GnomeBridgeTestSupport.Signal streamFailureResult = GnomeBridgeTestSupport.next(signals);
            assertEquals("error", streamFailureResult.arguments().get(2));
            assertEquals("stream disappeared", streamFailureResult.arguments().get(3));
        }
    }
}
