package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GraphGainCommandTest {
    @Test void commonVibroGainKeepsRememberedLevelThroughMute() {
        Map<String, Object> config = io.github.ilyaandreev05.audiomatrix.config.DefaultConfig.create();
        String target = GraphCommonVibro.COMMON_ROUTE_ID + "\t" + GraphCommonVibro.VIBRO_GAIN_ID;
        GraphGainCommand.set(config, target + "\t0.35", false);
        GainNode level = gain(config);
        assertEquals(0.35, level.gain(), 0.0001);
        assertFalse(level.muted());
        GraphGainCommand.set(config, target, true);
        GainNode muted = gain(config);
        assertEquals(0.35, muted.gain(), 0.0001);
        assertTrue(muted.muted());
        GraphGainCommand.set(config, target, true);
        GainNode restored = gain(config);
        assertEquals(0.35, restored.gain(), 0.0001);
        assertFalse(restored.muted());
    }

    @Test void rejectsMalformedTargetsWithoutChangingTheSharedGraph() {
        Map<String, Object> config = io.github.ilyaandreev05.audiomatrix.config.DefaultConfig.create();
        Map<String, Object> before = new LinkedHashMap<>(config);
        assertThrows(IllegalArgumentException.class, () -> GraphGainCommand.set(config, "only-route", false));
        assertThrows(IllegalArgumentException.class,
                () -> GraphGainCommand.set(config, GraphCommonVibro.COMMON_ROUTE_ID + "\tmissing\t0.5", false));
        assertEquals(before.keySet(), config.keySet());
        assertEquals(0.6, gain(config).gain(), 0.0001);
    }

    private static GainNode gain(Map<String, Object> config) {
        return GraphCommonVibro.graph(config).nodes().stream()
                .filter(node -> GraphCommonVibro.VIBRO_GAIN_ID.equals(node.id()) && node instanceof GainNode)
                .map(GainNode.class::cast).findFirst().orElseThrow();
    }
}
