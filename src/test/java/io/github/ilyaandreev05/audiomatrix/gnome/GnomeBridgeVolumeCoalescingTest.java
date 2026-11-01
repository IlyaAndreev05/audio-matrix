package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.ilyaandreev05.audiomatrix.gnome.GnomeBridgeTestSupport.*;
import static io.github.ilyaandreev05.audiomatrix.gnome.GnomeBridgeVolumeTestSupport.*;

class GnomeBridgeVolumeCoalescingTest {
    @Test void oneHundredLevelsExecuteOnlyFirstAndLastAndAcknowledgeOnlyConfirmedFinalState() throws Exception {
        for (String kind : List.of("setNodeVolume", "set_app_volume", "set_stream_volume")) {
            CompletableFuture<Void> firstApplied = new CompletableFuture<>();
            CompletableFuture<Void> firstObserved = new CompletableFuture<>();
            CompletableFuture<Void> lastApplied = new CompletableFuture<>();
            CompletableFuture<Void> lastObserved = new CompletableFuture<>();
            AtomicReference<GnomeControlBridge> reference = new AtomicReference<>();
            try (Fixture fixture = new Fixture(item -> switch (item.requestId()) {
                case "client:1" -> firstApplied;
                case "client:100" -> lastApplied;
                default -> done();
            }, item -> {
                CompletableFuture<Void> observed = "client:1".equals(item.requestId()) ? firstObserved
                        : "client:100".equals(item.requestId()) ? lastObserved : done();
                return observed.thenRun(() -> reference.get().emitObservedState(Map.of(
                        "revision", item.requestId(), "volume", Integer.parseInt(item.requestId().substring(7)) / 100.0)));
            })) {
                reference.set(fixture.bridge);
                List<GnomeControlBridge.Command> commands = new ArrayList<>();
                List<CompletableFuture<Void>> completions = new ArrayList<>();
                for (int level = 1; level <= 100; level++) {
                    GnomeControlBridge.Command item = setter(kind, "client:" + level, level / 100.0);
                    commands.add(item);
                    completions.add(fixture.bridge.dispatch(item));
                }
                assertEquals(List.of(commands.getFirst()), fixture.handled, kind);
                assertTrue(fixture.confirmed.isEmpty(), "superseded levels must never publish authoritative state");
                assertFalse(completions.getFirst().isDone(), "the active level must not be replaced");
                assertFalse(completions.getLast().isDone(), "the final pending level still awaits execution");
                long previousSequence = 0;
                for (int index = 1; index < 99; index++) {
                    await(completions.get(index));
                    JsonNode acknowledgment = fixture.ack(commands.get(index), "error", SUPERSEDED);
                    assertVolumeTarget(acknowledgment, kind);
                    assertTrue(acknowledgment.path("stateSequence").asLong() > previousSequence);
                    previousSequence = acknowledgment.path("stateSequence").asLong();
                }
                assertTrue(fixture.signals.isEmpty());

                firstApplied.complete(null);
                assertFalse(completions.getFirst().isDone(), "confirmation is part of the active audio slot");
                assertFalse(completions.getLast().isDone());
                assertEquals(List.of(commands.getFirst()), fixture.handled, "the final setter cannot overtake first-state confirmation");
                assertEquals(List.of(commands.getFirst()), fixture.confirmed);
                assertTrue(fixture.signals.isEmpty());
                firstObserved.complete(null);
                await(completions.getFirst());
                assertEquals(List.of(commands.getFirst(), commands.getLast()), fixture.handled, kind);
                assertEquals(List.of(commands.getFirst()), fixture.confirmed);
                JsonNode firstState = state(next(fixture.signals));
                assertEquals(0.01, firstState.path("volume").asDouble(), 1e-9);
                JsonNode firstAck = fixture.ack(commands.getFirst(), "ok", "Applied");
                assertVolumeTarget(firstAck, kind);
                assertEquals(0.01, firstAck.path("value").asDouble(), 1e-9);
                assertTrue(firstAck.path("stateSequence").asLong() > firstState.path("stateSequence").asLong());

                lastApplied.complete(null);
                assertEquals(List.of(commands.getFirst(), commands.getLast()), fixture.confirmed);
                assertFalse(completions.getLast().isDone(), "runtime/persistence success cannot retire the final intent");
                assertTrue(fixture.signals.isEmpty(), "no final success ACK or Result before confirmed state");
                lastObserved.complete(null);
                await(completions.getLast());
                JsonNode finalState = state(next(fixture.signals));
                assertEquals("client:100", finalState.path("revision").asText());
                assertEquals(1.0, finalState.path("volume").asDouble(), 1e-9);
                assertTrue(finalState.path("stateSequence").asLong() > firstAck.path("stateSequence").asLong());
                JsonNode finalAck = fixture.ack(commands.getLast(), "ok", "Applied");
                assertVolumeTarget(finalAck, kind);
                assertEquals(1.0, finalAck.path("value").asDouble(), 1e-9);
                assertTrue(finalAck.path("stateSequence").asLong() > finalState.path("stateSequence").asLong());
                assertEquals("client:100", fixture.lastAcknowledgedState.path("revision").asText());
                assertEquals(1.0, fixture.lastAcknowledgedState.path("volume").asDouble(), 1e-9);
                assertEquals(100, fixture.lastAcknowledgedState.path("commandAcks").size());
                for (CompletableFuture<Void> completion : completions) await(completion);
                assertTrue(fixture.signals.isEmpty());
            }
        }
    }

    @Test void muteStructuralUnsupportedAndOtherCommandKindsAreFifoBarriers() throws Exception {
        List<GnomeControlBridge.Command> barriers = List.of(
                command("client:mute", "setNodeMuted", "{\"target\":" + TARGET + ",\"muted\":true}"),
                command("client:app-mute", "toggle_app_mute", "org.example.Player"),
                command("client:stream-mute", "toggle_stream_mute", "42"),
                command("client:route", "select_route", "another-route"),
                command("client:structure", "setRouteEnabled", "{\"routeId\":\"main\",\"switchNodeId\":\"switch\",\"linkId\":\"link\",\"enabled\":false}"),
                command("client:unsupported", "unsupported_volume_command", "0.5"),
                new GnomeControlBridge.Command(4, "client:unsupported-version", "1", "setNodeVolume", volume("client:unused", 0.5).value()),
                setter("set_app_volume", "client:other-kind", 0.5),
                setter("set_stream_volume", "client:other-kind", 0.5),
                command("client:output", "set_output_volume", "sink\t0.5"),
                command("client:source", "set_source_volume", "main\tsource\tgain\t0.5"),
                command("client:graph-gain", "set_graph_gain", "main\tgain\t0.5"));
        for (GnomeControlBridge.Command barrier : barriers)
            assertBarrier(request -> volume(request, 0.5), List.of(barrier));
    }

    @Test void everyNodeTargetComponentAndClientIdentityIsASeparateFifoKey() throws Exception {
        List<GnomeControlBridge.Command> barriers = List.of(
                volume("another-client:target", 0.5),
                node("client:route", "{\"routeId\":\"other\",\"nodeId\":\"gain\",\"instanceKey\":\"\"}", "0.5"),
                node("client:node", "{\"routeId\":\"main\",\"nodeId\":\"other\",\"instanceKey\":\"\"}", "0.5"),
                node("client:instance", "{\"routeId\":\"main\",\"nodeId\":\"gain\",\"instanceKey\":\"app:org.example.Player\"}", "0.5"));
        for (GnomeControlBridge.Command barrier : barriers)
            assertBarrier(request -> volume(request, 0.5), List.of(barrier));
    }

    @Test void legacyVolumeSettersDoNotMergeAcrossClientsTargetsOrKinds() throws Exception {
        for (String kind : List.of("set_app_volume", "set_stream_volume")) {
            String otherTarget = "set_app_volume".equals(kind) ? "org.example.Other\t0.5" : "43\t0.5";
            for (GnomeControlBridge.Command barrier : List.of(
                    setter(kind, "other-client:barrier", 0.5),
                    command("client:other-target", kind, otherTarget),
                    setter("set_app_volume".equals(kind) ? "set_stream_volume" : "set_app_volume", "client:other-kind", 0.5),
                    volume("client:node-kind", 0.5))) {
                assertBarrier(request -> setter(kind, request, 0.5), List.of(barrier));
            }
        }
    }

    @Test void commandKindRemainsPartOfTheKeyEvenWhenLegacyTargetTextIsIdentical() throws Exception {
        assertBarrier(request -> command(request, "set_app_volume", "42\t0.5"),
                List.of(command("client:other-kind", "set_stream_volume", "42\t0.5")));
        assertBarrier(request -> command(request, "set_stream_volume", "42\t0.5"),
                List.of(command("client:other-kind", "set_app_volume", "42\t0.5")));
    }
}
