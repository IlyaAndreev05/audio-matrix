package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GnomeBridgeV5StateTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void epochSequenceAdvancesOnlyForObservedChangesAndConfirmedAcks() throws Exception {
        GnomeBridgeStateSequencer sequencer = new GnomeBridgeStateSequencer(json);
        String first = sequencer.observe(state(true));
        Map<String, Object> initial = decode(first);
        String epoch = (String) initial.get("stateEpoch");
        assertEquals(epoch, UUID.fromString(epoch).toString());
        assertEquals(1L, sequence(initial));
        assertEquals(List.of(), initial.get("commandAcks"));
        assertEquals("", sequencer.observe(state(true)));

        String acknowledged = sequencer.acknowledge(Map.of("requestId", "client:req-1",
                "target", Map.of("routeId", "main", "nodeId", "app-generator", "instanceKey", "app:firefox"),
                "property", "volume", "status", "ok", "value", 0.5));
        Map<String, Object> withAck = decode(acknowledged);
        assertEquals(epoch, withAck.get("stateEpoch"));
        assertEquals(2L, sequence(withAck));
        @SuppressWarnings("unchecked") List<Map<String, Object>> acknowledgments =
                (List<Map<String, Object>>) withAck.get("commandAcks");
        assertEquals(2L, ((Number) acknowledgments.getFirst().get("stateSequence")).longValue());
        assertEquals("client:req-1", acknowledgments.getFirst().get("requestId"));

        Map<String, Object> changed = decode(sequencer.observe(state(false)));
        assertEquals(epoch, changed.get("stateEpoch"));
        assertEquals(3L, sequence(changed));
        assertEquals(false, changed.get("connected"));
        assertEquals(1, ((List<?>) changed.get("commandAcks")).size());
        GnomeBridgeStateSequencer restarted = new GnomeBridgeStateSequencer(json);
        assertNotEquals(epoch, decode(restarted.observe(state(true))).get("stateEpoch"));
    }

    @Test void acknowledgmentHistoryRetainsOnlyTheLatest128Requests() throws Exception {
        GnomeBridgeStateSequencer sequencer = new GnomeBridgeStateSequencer(json);
        sequencer.observe(state(true));
        String latest = "";
        for (int index = 0; index < 129; index++) {
            latest = sequencer.acknowledge(Map.of("requestId", "request-" + index,
                    "target", Map.of("command", "test"), "property", "value", "status", "ok", "value", index));
        }
        Map<String, Object> state = decode(latest);
        @SuppressWarnings("unchecked") List<Map<String, Object>> acknowledgments =
                (List<Map<String, Object>>) state.get("commandAcks");
        assertEquals(128, acknowledgments.size());
        assertEquals("request-1", acknowledgments.getFirst().get("requestId"));
        assertEquals("request-128", acknowledgments.getLast().get("requestId"));
    }

    @Test void nodeVolumeAckPreservesTheFullGeneratedTargetAndProperty() {
        GnomeControlBridge.Command command = new GnomeControlBridge.Command(5, "client:req-2", "41",
                "setNodeVolume", "{\"target\":{\"routeId\":\"main\",\"nodeId\":\"app-generator\",\"instanceKey\":\"app:firefox\"},\"value\":0.5}");

        Map<String, Object> ack = GnomeBridgeCommandAck.create(command,
                new GnomeControlBridge.CommandResult("ok", "Applied"), json);

        assertEquals("client:req-2", ack.get("requestId"));
        assertEquals(Map.of("routeId", "main", "nodeId", "app-generator", "instanceKey", "app:firefox"),
                ack.get("target"));
        assertEquals("volume", ack.get("property"));
        assertEquals(0.5, ack.get("value"));
        assertEquals("ok", ack.get("status"));
    }

    @Test void profileProjectionKeepsStableChoicesAndOnlySelectorFields() {
        Map<String, Object> config = Map.of("profiles", List.of(
                Map.of("id", "main", "name", "Основной", "mode", "multi-output"),
                Map.of("id", "work", "name", "Работа", "mode", "off")));

        assertEquals(List.of(Map.of("id", "main", "name", "Основной"),
                Map.of("id", "work", "name", "Работа")), GnomeBridgeProfileState.profiles(config));
    }

    @Test void routeAckUsesExactPersistedMultiSwitchLinkIdentity() {
        GnomeControlBridge.Command command = new GnomeControlBridge.Command(5, "client:req-3", "41",
                "setRouteEnabled", "{\"routeId\":\"main\",\"switchNodeId\":\"multi\",\"linkId\":\"in->output-abc\",\"enabled\":true}");

        Map<String, Object> ack = GnomeBridgeCommandAck.create(command,
                new GnomeControlBridge.CommandResult("ok", "Applied"), json);

        assertEquals(Map.of("routeId", "main", "switchNodeId", "multi", "linkId", "in->output-abc"),
                ack.get("target"));
        assertEquals("enabled", ack.get("property"));
        assertEquals(true, ack.get("value"));
    }

    private static long sequence(Map<String, Object> state) {
        return ((Number) state.get("stateSequence")).longValue();
    }
    private static Map<String, Object> state(boolean connected) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("connected", connected);
        return state;
    }

    private Map<String, Object> decode(String value) throws Exception {
        return json.readValue(value, new TypeReference<>() {});
    }
}
