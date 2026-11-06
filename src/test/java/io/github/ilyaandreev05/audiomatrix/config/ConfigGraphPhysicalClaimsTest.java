package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigGraphPhysicalClaimsTest {
    private static final List<String> STEREO = List.of("FL", "FR");
    private static OutputNode matrix(String id, String device, List<String> positions) {
        return new OutputNode(id, id, device, STEREO, new ChannelMatrix(STEREO, positions,
                List.of(List.of(1.0, 0.0), List.of(0.0, 0.0))));
    }
    private static AudioGraph graph(String id, GraphNode... nodes) {
        return new AudioGraph(id, id, List.of(nodes), List.of());
    }
    private static List<String> validate(AudioGraph common, AudioGraph route, String active) {
        var errors = new ArrayList<String>();
        ConfigGraphPhysicalRules.validate(Map.of(GraphCommonVibro.COMMON_ROUTE_ID, common,
                route.id(), route), active, errors);
        return errors;
    }

    @Test void commonComparesEveryEndpointButInactiveRouteCanPrebind() {
        var common = graph(GraphCommonVibro.COMMON_ROUTE_ID,
                matrix("other-common", "device:card", List.of("RL", "RR")));
        var overlapping = graph("route", matrix("out", "card", List.of("RR", "RC")));
        assertFalse(validate(common, overlapping, "route").isEmpty());
        assertTrue(validate(common, overlapping, "another-route").isEmpty());
        var disjoint = graph("route", new DeviceSlotNode("slot", "Slot", "card", STEREO));
        assertTrue(validate(common, disjoint, "route").isEmpty());
    }

    @Test void inactiveGraphStillChecksEveryInternalPairAndUnknownClaims() {
        var common = graph(GraphCommonVibro.COMMON_ROUTE_ID);
        var route = graph("route", matrix("one", "card", List.of("FL", "FR")),
                matrix("two", "card", List.of("RL", "RR")),
                matrix("three", "device:card", List.of("RR", "RC")));
        assertFalse(validate(common, route, "another-route").isEmpty());
        var unknown = graph("route", matrix("rear", "card", List.of("RL", "RR")),
                new DeviceSlotNode("mono", "Mono", "card", List.of("MONO")));
        assertFalse(validate(common, unknown, "another-route").isEmpty());
    }
}
