package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Serializes observed bridge state and acknowledgments into one ordered epoch. */
public final class GnomeBridgeStateSequencer {
    private static final int MAX_ACKNOWLEDGMENTS = 128;
    private final ObjectMapper json;
    private final String epoch = UUID.randomUUID().toString();
    private final ArrayDeque<Map<String, Object>> acknowledgments = new ArrayDeque<>();
    private Map<String, Object> observedState = Map.of();
    private String observedFingerprint = "";
    private String latest = "";
    private long sequence;

    public GnomeBridgeStateSequencer(ObjectMapper json) {
        this.json = json;
    }

    /** Returns an empty string when the observable state is identical to the prior State. */
    public synchronized String observe(Map<String, Object> state) {
        try {
            String fingerprint = json.writeValueAsString(state);
            if (fingerprint.equals(observedFingerprint)) return "";
            observedFingerprint = fingerprint;
            observedState = new LinkedHashMap<>(state);
            sequence++;
            latest = encode();
            return latest;
        } catch (Exception invalid) {
            throw new IllegalStateException("Could not serialize Audio Matrix bridge state", invalid);
        }
    }

    /** Adds a confirmed or failed command ACK and emits it in the next ordered State. */
    public synchronized String acknowledge(Map<String, Object> acknowledgment) {
        sequence++;
        Map<String, Object> item = new LinkedHashMap<>(acknowledgment);
        item.put("stateSequence", sequence);
        acknowledgments.addLast(item);
        while (acknowledgments.size() > MAX_ACKNOWLEDGMENTS) acknowledgments.removeFirst();
        if (observedState.isEmpty()) return "";
        latest = encode();
        return latest;
    }

    public synchronized String latest() {
        return latest;
    }

    private String encode() {
        try {
            Map<String, Object> state = new LinkedHashMap<>(observedState);
            state.put("stateEpoch", epoch);
            state.put("stateSequence", sequence);
            state.put("commandAcks", new ArrayList<>(acknowledgments));
            return json.writeValueAsString(state);
        } catch (Exception invalid) {
            throw new IllegalStateException("Could not serialize Audio Matrix bridge state", invalid);
        }
    }
}
