package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Shared recording fixtures for the D-Bus command-ordering tests. */
final class GnomeBridgeTestSupport {
    static final ObjectMapper JSON = new ObjectMapper();
    record Signal(String name, String signature, List<String> arguments) {}

    private GnomeBridgeTestSupport() {
    }

    static GnomeControlBridge.Command open(String request) {
        return new GnomeControlBridge.Command(5, request, "1", "open", "");
    }

    static GnomeControlBridge.Command volume(String request, double value) {
        return new GnomeControlBridge.Command(5, request, "1", "setNodeVolume",
                "{\"target\":{\"routeId\":\"main\",\"nodeId\":\"gain\",\"instanceKey\":\"\"},\"value\":" + value + "}");
    }

    static Signal next(LinkedBlockingQueue<Signal> signals) throws InterruptedException {
        Signal signal = signals.poll(2, TimeUnit.SECONDS);
        assertNotNull(signal, "the ordered bridge signal was not emitted");
        return signal;
    }

    static JsonNode state(Signal signal) throws Exception {
        assertEquals("State", signal.name());
        assertEquals("s", signal.signature());
        return JSON.readTree(signal.arguments().getFirst());
    }
}
