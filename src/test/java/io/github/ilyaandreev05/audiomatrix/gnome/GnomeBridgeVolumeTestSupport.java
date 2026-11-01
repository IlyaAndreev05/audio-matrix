package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.ilyaandreev05.audiomatrix.gnome.GnomeBridgeTestSupport.*;

/** Bus-independent fixtures for bounded bridge volume-command regression tests. */
final class GnomeBridgeVolumeTestSupport {
    static final String SUPERSEDED = "superseded by newer volume for this target";
    static final String FULL = "Audio Matrix command queue is full";
    static final String TARGET = "{\"routeId\":\"main\",\"nodeId\":\"gain\",\"instanceKey\":\"\"}";

    private GnomeBridgeVolumeTestSupport() {}

    static CompletableFuture<Void> done() {
        return CompletableFuture.completedFuture(null);
    }

    static void await(CompletableFuture<Void> completion) throws Exception {
        completion.get(2, TimeUnit.SECONDS);
        assertFalse(completion.isCompletedExceptionally(), "bridge rejections resolve through ACK and Result");
    }

    static GnomeControlBridge.Command command(String request, String name, String value) {
        return new GnomeControlBridge.Command(GnomeControlBridge.API_VERSION, request, "1", name, value);
    }

    static GnomeControlBridge.Command node(String request, String target, String value) {
        return command(request, "setNodeVolume", "{\"target\":" + target + ",\"value\":" + value + "}");
    }

    static GnomeControlBridge.Command setter(String name, String request, double value) {
        return switch (name) {
            case "setNodeVolume" -> volume(request, value);
            case "set_app_volume" -> command(request, name, "org.example.Player\t" + value);
            case "set_stream_volume" -> command(request, name, "42\t" + value);
            default -> throw new IllegalArgumentException(name);
        };
    }

    static void assertVolumeTarget(JsonNode acknowledgment, String name) throws Exception {
        assertEquals("volume", acknowledgment.path("property").asText());
        JsonNode target = acknowledgment.path("target");
        switch (name) {
            case "setNodeVolume" -> assertEquals(JSON.readTree(TARGET), target);
            case "set_app_volume" -> {
                assertEquals(1, target.size());
                assertEquals("org.example.Player", target.path("applicationId").asText());
            }
            case "set_stream_volume" -> {
                assertEquals(1, target.size());
                assertEquals(42, target.path("streamId").asInt());
            }
            default -> fail("Unexpected volume command " + name);
        }
    }

    /** A barrier must preserve its older neighbor and itself, but later adjacent setters still merge. */
    static void assertBarrier(Function<String, GnomeControlBridge.Command> setter,
            List<GnomeControlBridge.Command> barriers) throws Exception {
        CompletableFuture<Void> blocked = new CompletableFuture<>();
        GnomeControlBridge.Command active = setter.apply("client:active");
        GnomeControlBridge.Command before = setter.apply("client:before");
        GnomeControlBridge.Command after = setter.apply("client:after");
        GnomeControlBridge.Command latest = setter.apply("client:latest");
        try (Fixture fixture = new Fixture(ignored -> blocked, ignored -> done())) {
            CompletableFuture<Void> first = fixture.bridge.dispatch(active);
            List<GnomeControlBridge.Command> expected = new ArrayList<>(List.of(active, before));
            expected.addAll(barriers);
            expected.add(latest);
            List<CompletableFuture<Void>> retained = new ArrayList<>();
            retained.add(first);
            retained.add(fixture.bridge.dispatch(before));
            for (GnomeControlBridge.Command barrier : barriers)
                retained.add(fixture.bridge.dispatch(barrier));
            CompletableFuture<Void> displaced = fixture.bridge.dispatch(after);
            retained.add(fixture.bridge.dispatch(latest));
            await(displaced);
            fixture.ack(after, "error", SUPERSEDED);
            for (CompletableFuture<Void> completion : retained)
                assertFalse(completion.isDone(), "a FIFO barrier cannot be silently superseded");
            assertEquals(List.of(active), fixture.handled);
            assertTrue(fixture.confirmed.isEmpty());
            blocked.complete(null);
            for (CompletableFuture<Void> completion : retained) await(completion);
            assertEquals(expected, fixture.confirmed, "confirmation also preserves barrier order");
            List<GnomeControlBridge.Command> handled = new ArrayList<>(expected);
            handled.removeIf(item -> item.apiVersion() != GnomeControlBridge.API_VERSION
                    || "unsupported_volume_command".equals(item.name()));
            assertEquals(handled, fixture.handled, "invalid/unsupported commands never bypass the FIFO");
            for (GnomeControlBridge.Command item : expected) {
                String error = item.apiVersion() != GnomeControlBridge.API_VERSION
                        ? "Unsupported Audio Matrix D-Bus API version " + item.apiVersion()
                        : "unsupported_volume_command".equals(item.name()) ? "Unsupported command: " + item.name() : null;
                if (!item.requestId().isBlank()) fixture.ack(item, error == null ? "ok" : "error", error == null ? "Applied" : error);
            }
            assertTrue(fixture.signals.isEmpty());
        }
    }

    static final class Fixture implements AutoCloseable {
        final LinkedBlockingQueue<Signal> signals = new LinkedBlockingQueue<>();
        final List<GnomeControlBridge.Command> handled = new CopyOnWriteArrayList<>();
        final List<GnomeControlBridge.Command> confirmed = new CopyOnWriteArrayList<>();
        final GnomeControlBridge bridge;
        JsonNode lastAcknowledgedState;

        Fixture(Function<GnomeControlBridge.Command, CompletableFuture<Void>> action,
                Function<GnomeControlBridge.Command, CompletableFuture<Void>> confirmation) throws Exception {
            bridge = new GnomeControlBridge(item -> {
                handled.add(item);
                return action.apply(item);
            }, List.of(), item -> {
                confirmed.add(item);
                return confirmation.apply(item);
            }, (name, signature, arguments) -> signals.add(new Signal(name, signature, List.copyOf(arguments))));
            bridge.emitObservedState(Map.of("revision", "1", "volume", 1.0));
            state(next(signals));
        }

        JsonNode ack(GnomeControlBridge.Command command, String status, String message) throws Exception {
            JsonNode envelope = state(next(signals));
            lastAcknowledgedState = envelope;
            JsonNode acknowledgments = envelope.path("commandAcks");
            assertFalse(acknowledgments.isEmpty());
            JsonNode acknowledgment = acknowledgments.get(acknowledgments.size() - 1);
            assertEquals(command.requestId(), acknowledgment.path("requestId").asText());
            assertEquals(status, acknowledgment.path("status").asText());
            assertEquals(envelope.path("stateSequence"), acknowledgment.path("stateSequence"));
            if ("error".equals(status)) {
                assertEquals(message, acknowledgment.path("error").asText());
                assertTrue(acknowledgment.path("value").isNull(), "an error cannot confirm the discarded level");
            } else assertFalse(acknowledgment.has("error"));
            Signal result = next(signals);
            assertEquals("Result", result.name());
            assertEquals("usss", result.signature());
            assertEquals(List.of(Integer.toString(GnomeControlBridge.API_VERSION), command.requestId(), status, message),
                    result.arguments());
            return acknowledgment;
        }

        @Override public void close() {
            bridge.close();
        }
    }
}
