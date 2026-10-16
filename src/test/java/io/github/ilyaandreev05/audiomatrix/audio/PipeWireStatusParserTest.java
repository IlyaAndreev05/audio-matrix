package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireStatusParserTest {
    @Test void readsDefaultSinkAndActualLevelsFromWirePlumberTreeOutput() {
        String status = """
                Audio
                 ├─ Sinks:
                 │  *   38. Family 17h Analog [vol: 0.23]
                 │      46. TU106 HDMI [vol: 0.02]
                 ├─ Sources:
                 │      47. Microphone [vol: 0.45]
                """;

        assertEquals(38, PipeWireBackend.defaultSinkId(status));
        assertEquals(0.23, PipeWireBackend.levelStates(status).get(38).volume(), 1e-9);
        assertEquals(0.02, PipeWireBackend.levelStates(status).get(46).volume(), 1e-9);
        assertEquals(0.45, PipeWireBackend.levelStates(status).get(47).volume(), 1e-9);
    }

    @Test void recognizesMutedMarker() {
        var levels = PipeWireBackend.levelStates(" ├─ Sinks:\n │ *  38. Analog [vol: 0.23 MUTED]\n");
        assertTrue(levels.get(38).muted());
    }
}
