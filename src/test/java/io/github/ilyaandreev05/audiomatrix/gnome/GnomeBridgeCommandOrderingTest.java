package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GnomeBridgeCommandOrderingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private record Signal(String name, String signature, List<String> arguments) {}

    @Test void acknowledgmentWaitsForRuntimePersistenceAndPublishedAuthoritativeState() throws Exception {
        LinkedBlockingQueue<Signal> signals = new LinkedBlockingQueue<>();
        CompletableFuture<Void> appliedAndSaved = new CompletableFuture<>();
        CompletableFuture<Void> observed = new CompletableFuture<>();
        AtomicReference<GnomeControlBridge> reference = new AtomicReference<>();
        try (GnomeControlBridge bridge = new GnomeControlBridge(command -> appliedAndSaved, List.of(),
                command -> observed.thenRun(() -> reference.get().emitObservedState(
                        Map.of("revision", "2", "volume", 0.25, "muted", false))),
                (name, signature, values) -> signals.add(new Signal(name, signature, values)))) {
            reference.set(bridge);
            bridge.emitObservedState(Map.of("revision", "1", "volume", 0.8, "muted", true));
            assertEquals("State", next(signals).name());

            CompletableFuture<Void> completed = bridge.dispatch(volume("client:1", 0.25));
            assertFalse(completed.isDone());
            assertTrue(signals.isEmpty());
            appliedAndSaved.complete(null);
            assertFalse(completed.isDone(), "saving the command alone cannot retire the pending slider");
            assertTrue(signals.isEmpty());

            observed.complete(null);
            completed.get(2, TimeUnit.SECONDS);
            JsonNode fresh = state(next(signals));
            JsonNode acknowledged = state(next(signals));
            assertEquals(0.25, fresh.path("volume").asDouble(), 1e-9);
            assertEquals("2", acknowledged.path("revision").asText());
            assertEquals(0.25, acknowledged.path("volume").asDouble(), 1e-9);
            assertFalse(acknowledged.path("muted").asBoolean());
            JsonNode ack = acknowledged.path("commandAcks").get(0);
            assertEquals("client:1", ack.path("requestId").asText());
            assertEquals("ok", ack.path("status").asText());
            assertEquals("gain", ack.path("target").path("nodeId").asText());
            assertEquals("volume", ack.path("property").asText());
            assertTrue(acknowledged.path("stateSequence").asLong() > fresh.path("stateSequence").asLong());
            Signal result = next(signals);
            assertEquals("Result", result.name());
            assertEquals("usss", result.signature());
            assertEquals(List.of("5", "client:1", "ok", "Applied"), result.arguments());
        }
    }

    @Test void nextCommandCannotOvertakeThePreviousStateAndAcknowledgment() throws Exception {
        LinkedBlockingQueue<Signal> signals = new LinkedBlockingQueue<>();
        CompletableFuture<Void> firstObservation = new CompletableFuture<>();
        AtomicInteger handled = new AtomicInteger();
        AtomicReference<GnomeControlBridge> reference = new AtomicReference<>();
        try (GnomeControlBridge bridge = new GnomeControlBridge(command -> {
                    handled.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }, List.of(), command -> {
                    CompletableFuture<Void> ready = "client:1".equals(command.requestId())
                            ? firstObservation : CompletableFuture.completedFuture(null);
                    return ready.thenRun(() -> reference.get().emitObservedState(Map.of(
                            "revision", command.requestId(), "volume", "client:1".equals(command.requestId()) ? 0.2 : 0.7)));
                }, (name, signature, values) -> signals.add(new Signal(name, signature, values)))) {
            reference.set(bridge);
            bridge.emitObservedState(Map.of("revision", "initial", "volume", 1.0));
            next(signals);
            CompletableFuture<Void> first = bridge.dispatch(volume("client:1", 0.2));
            CompletableFuture<Void> second = bridge.dispatch(volume("client:2", 0.7));
            assertEquals(1, handled.get());
            assertFalse(first.isDone());
            assertFalse(second.isDone());

            firstObservation.complete(null);
            second.get(2, TimeUnit.SECONDS);
            assertEquals(2, handled.get());
            assertEquals("client:1", state(next(signals)).path("revision").asText());
            JsonNode firstAck = state(next(signals));
            assertEquals("client:1", firstAck.path("commandAcks").get(0).path("requestId").asText());
            assertEquals("client:1", next(signals).arguments().get(1));
            assertEquals("client:2", state(next(signals)).path("revision").asText());
            JsonNode secondAck = state(next(signals));
            assertEquals("client:2", secondAck.path("commandAcks").get(1).path("requestId").asText());
            assertEquals("client:2", next(signals).arguments().get(1));
        }
    }

    @Test void failedCommandPublishesRolledBackStateAndDoesNotPoisonTheQueue() throws Exception {
        LinkedBlockingQueue<Signal> signals = new LinkedBlockingQueue<>();
        AtomicReference<GnomeControlBridge> reference = new AtomicReference<>();
        try (GnomeControlBridge bridge = new GnomeControlBridge(command -> "client:failed".equals(command.requestId())
                        ? CompletableFuture.failedFuture(new IllegalStateException("Save conflict"))
                        : CompletableFuture.completedFuture(null), List.of(), command -> {
                    reference.get().emitObservedState(Map.of("revision", command.requestId(), "volume", 0.8));
                    return CompletableFuture.completedFuture(null);
                }, (name, signature, values) -> signals.add(new Signal(name, signature, values)))) {
            reference.set(bridge);
            bridge.emitObservedState(Map.of("revision", "initial", "volume", 0.2));
            next(signals);
            bridge.dispatch(volume("client:failed", 0.1)).get(2, TimeUnit.SECONDS);
            next(signals);
            JsonNode failed = state(next(signals));
            assertEquals(0.8, failed.path("volume").asDouble(), 1e-9);
            JsonNode ack = failed.path("commandAcks").get(0);
            assertEquals("error", ack.path("status").asText());
            assertTrue(ack.path("value").isNull());
            assertEquals("Save conflict", ack.path("error").asText());
            assertEquals("error", next(signals).arguments().get(2));

            bridge.dispatch(volume("client:next", 0.8)).get(2, TimeUnit.SECONDS);
            next(signals);
            assertEquals("ok", state(next(signals)).path("commandAcks").get(1).path("status").asText());
            assertEquals("ok", next(signals).arguments().get(2));
        }
    }

    @Test void statePublicationFailureCannotProduceSuccessfulAcknowledgment() throws Exception {
        LinkedBlockingQueue<Signal> signals = new LinkedBlockingQueue<>();
        try (GnomeControlBridge bridge = new GnomeControlBridge(command -> CompletableFuture.completedFuture(null),
                List.of(), command -> CompletableFuture.failedFuture(new IllegalStateException("Snapshot unavailable")),
                (name, signature, values) -> signals.add(new Signal(name, signature, values)))) {
            bridge.emitObservedState(Map.of("revision", "1", "volume", 0.8));
            next(signals);
            bridge.dispatch(volume("client:1", 0.2)).get(2, TimeUnit.SECONDS);
            JsonNode ack = state(next(signals)).path("commandAcks").get(0);
            assertEquals("error", ack.path("status").asText());
            assertTrue(ack.path("error").asText().contains("Snapshot unavailable"));
            assertEquals("error", next(signals).arguments().get(2));
        }
    }

    @Test void browserMuteAcknowledgmentRetainsTheExactTabTarget() throws Exception {
        Map<String, Object> target = Map.of("browser", "firefox", "instanceId", "instance-1",
                "profileId", "profile-2", "sessionToken", "tab-session");
        GnomeControlBridge.Command command = new GnomeControlBridge.Command(5, "client:tab", "7",
                "setBrowserTabMuted", JSON.writeValueAsString(Map.of("target", target, "muted", true)));
        Map<String, Object> ack = GnomeBridgeCommandAck.create(command,
                new GnomeControlBridge.CommandResult("ok", "Applied"), JSON);
        assertEquals(target, ack.get("target"));
        assertEquals("muted", ack.get("property"));
        assertEquals(true, ack.get("value"));
    }

    private static GnomeControlBridge.Command volume(String request, double value) {
        return new GnomeControlBridge.Command(5, request, "1", "setNodeVolume",
                "{\"target\":{\"routeId\":\"main\",\"nodeId\":\"gain\",\"instanceKey\":\"\"},\"value\":" + value + "}");
    }

    private static Signal next(LinkedBlockingQueue<Signal> signals) throws InterruptedException {
        Signal signal = signals.poll(2, TimeUnit.SECONDS);
        assertNotNull(signal, "the ordered bridge signal was not emitted");
        return signal;
    }

    private static JsonNode state(Signal signal) throws Exception {
        assertEquals("State", signal.name());
        assertEquals("s", signal.signature());
        return JSON.readTree(signal.arguments().getFirst());
    }
}
