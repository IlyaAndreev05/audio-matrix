package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.ilyaandreev05.audiomatrix.gnome.GnomeBridgeTestSupport.*;
import static io.github.ilyaandreev05.audiomatrix.gnome.GnomeBridgeVolumeTestSupport.*;

class GnomeBridgeVolumeValidationTest {
    @Test void exactNodeSchemaAllowsJsonWhitespaceKeyOrderAndNumericBoundaryValues() throws Exception {
        GnomeControlBridge.Command reordered = command("client:reordered", "setNodeVolume",
                " { \"value\" : 2.5e-1, \"target\" : { \"instanceKey\" : \"\", \"nodeId\" : \"gain\", \"routeId\" : \"main\" } } \n");
        GnomeControlBridge.Command newest = volume("client:latest", 1.0);
        assertEquals(GnomeVolumeCommandKey.from(volume("client:zero", 0.0)), GnomeVolumeCommandKey.from(reordered));
        assertEquals(GnomeVolumeCommandKey.from(reordered), GnomeVolumeCommandKey.from(newest));
        CompletableFuture<Void> blocked = new CompletableFuture<>();
        try (Fixture fixture = new Fixture(ignored -> blocked, ignored -> done())) {
            GnomeControlBridge.Command active = volume("client:active", 0.0);
            CompletableFuture<Void> first = fixture.bridge.dispatch(active);
            CompletableFuture<Void> displaced = fixture.bridge.dispatch(reordered);
            CompletableFuture<Void> last = fixture.bridge.dispatch(newest);
            await(displaced);
            assertVolumeTarget(fixture.ack(reordered, "error", SUPERSEDED), "setNodeVolume");
            assertFalse(first.isDone());
            assertFalse(last.isDone());
            blocked.complete(null);
            await(first);
            await(last);
            assertEquals(List.of(active, newest), fixture.handled);
            assertEquals(List.of(active, newest), fixture.confirmed);
            assertEquals(0.0, fixture.ack(active, "ok", "Applied").path("value").asDouble(), 1e-9);
            assertEquals(1.0, fixture.ack(newest, "ok", "Applied").path("value").asDouble(), 1e-9);
            assertTrue(fixture.signals.isEmpty());
        }
    }

    @Test void nodeJsonMissingUnknownDuplicateAndTrailingFieldsRemainFifoBarriers() throws Exception {
        String valid = volume("client:valid", 0.5).value();
        List<String> malformed = List.of(
                "", " ", "null", "[]", "true", "{}",
                "{\"target\":" + TARGET + "}",
                "{\"value\":0.5}",
                "{\"target\":" + TARGET + ",\"value\":0.5,\"extra\":true}",
                "{\"target\":" + TARGET + ",\"value\":0.5,\"value\":0.7}",
                "{\"target\":" + TARGET + ",\"target\":" + TARGET + ",\"value\":0.5}",
                node("client:unused", "{\"nodeId\":\"gain\",\"instanceKey\":\"\"}", "0.5").value(),
                node("client:unused", "{\"routeId\":\"main\",\"instanceKey\":\"\"}", "0.5").value(),
                node("client:unused", "{\"routeId\":\"main\",\"nodeId\":\"gain\"}", "0.5").value(),
                node("client:unused", "{\"routeId\":\"main\",\"nodeId\":\"gain\",\"instanceKey\":\"\",\"extra\":true}", "0.5").value(),
                node("client:unused", "{\"routeId\":\"main\",\"routeId\":\"main\",\"nodeId\":\"gain\",\"instanceKey\":\"\"}", "0.5").value(),
                node("client:unused", "{\"routeId\":\"main\",\"nodeId\":\"gain\",\"nodeId\":\"gain\",\"instanceKey\":\"\"}", "0.5").value(),
                node("client:unused", "{\"routeId\":\"main\",\"nodeId\":\"gain\",\"instanceKey\":\"\",\"instanceKey\":\"\"}", "0.5").value(),
                valid + " {}", valid + " true", valid + " trailing", valid.substring(0, valid.length() - 1),
                "{\"target\":" + TARGET + ",\"value\":0.5,}");
        for (String raw : malformed) assertMalformedNodeBarrier(raw);
    }

    @Test void nodeJsonRejectsWrongTargetTypesAndNonNumericNonFiniteOrOutOfRangeLevels() throws Exception {
        for (String target : List.of("null", "[]", "\"gain\"", "false",
                "{\"routeId\":null,\"nodeId\":\"gain\",\"instanceKey\":\"\"}",
                "{\"routeId\":1,\"nodeId\":\"gain\",\"instanceKey\":\"\"}",
                "{\"routeId\":\" \",\"nodeId\":\"gain\",\"instanceKey\":\"\"}",
                "{\"routeId\":\"main\",\"nodeId\":false,\"instanceKey\":\"\"}",
                "{\"routeId\":\"main\",\"nodeId\":\"\",\"instanceKey\":\"\"}",
                "{\"routeId\":\"main\",\"nodeId\":\"gain\",\"instanceKey\":null}",
                "{\"routeId\":\"main\",\"nodeId\":\"gain\",\"instanceKey\":1}")) {
            assertMalformedNodeBarrier(node("client:unused", target, "0.5").value());
        }
        for (String level : List.of("\"0.5\"", "true", "null", "[]", "{}", "-0.01", "1.01", "1e400", "NaN", "Infinity"))
            assertMalformedNodeBarrier(node("client:unused", TARGET, level).value());
    }

    @Test void malformedLegacyLevelsAndStreamIdsAreNotEligibleForSupersession() throws Exception {
        for (String kind : List.of("set_app_volume", "set_stream_volume")) {
            String target = "set_app_volume".equals(kind) ? "org.example.Player" : "42";
            for (String raw : List.of("", "\t0.5", target, target + "\t", target + "\t0.5\textra",
                    target + "\tNaN", target + "\tInfinity", target + "\t-Infinity", target + "\tno-number")) {
                GnomeControlBridge.Command first = command("client:invalid-one", kind, raw);
                GnomeControlBridge.Command second = command("client:invalid-two", kind, raw);
                assertNull(GnomeVolumeCommandKey.from(first), raw);
                assertBarrier(request -> setter(kind, request, 0.5), List.of(first, second));
            }
        }
        for (String raw : List.of("not-an-id\t0.5", "42.0\t0.5", "2147483648\t0.5")) {
            GnomeControlBridge.Command first = command("client:invalid-one", "set_stream_volume", raw);
            assertNull(GnomeVolumeCommandKey.from(first), raw);
            assertBarrier(request -> setter("set_stream_volume", request, 0.5),
                    List.of(first, command("client:invalid-two", "set_stream_volume", raw)));
        }
    }

    @Test void unprefixedEmptyAndIncompleteRequestIdsNeverCoalesceEvenWhenRepeated() throws Exception {
        for (String kind : List.of("setNodeVolume", "set_app_volume", "set_stream_volume")) {
            for (String request : List.of("", "unprefixed", ":request", " :request", "client:", "client: ")) {
                GnomeControlBridge.Command first = setter(kind, request, 0.2);
                GnomeControlBridge.Command second = setter(kind, request, 0.8);
                assertNull(GnomeVolumeCommandKey.from(first), "unidentified request: " + request);
                assertNull(GnomeVolumeCommandKey.from(second));
                assertBarrier(id -> setter(kind, id, 0.5), List.of(first, second));
            }
        }
    }

    private static void assertMalformedNodeBarrier(String raw) throws Exception {
        GnomeControlBridge.Command first = command("client:invalid-one", "setNodeVolume", raw);
        GnomeControlBridge.Command second = command("client:invalid-two", "setNodeVolume", raw);
        assertNull(GnomeVolumeCommandKey.from(first), raw);
        assertNull(GnomeVolumeCommandKey.from(second), raw);
        assertBarrier(request -> volume(request, 0.5), List.of(first, second));
    }
}
