package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression checks for channel links used when front and rear outputs share one card. */
class PipeWireSplitChannelIsolationTest {
    @Test void stereoInputDoesNotConnectToRearChannels() {
        List<PipeWireRouteManager.Link> links = PipeWireRouteManager.channelLinks(
                List.of("source:output_FL", "source:output_FR"),
                List.of("output:input_FL", "output:input_FR", "output:input_RL", "output:input_RR"),
                "stereo", false);
        assertEquals(List.of(new PipeWireRouteManager.Link("source:output_FL", "output:input_FL"),
                new PipeWireRouteManager.Link("source:output_FR", "output:input_FR")), links);
    }

    @Test void rearPositionLinksDoNotConnectToFrontChannels() {
        List<PipeWireRouteManager.Link> links = PipeWireRouteManager.positionLinks(
                List.of("rear:output_RL", "rear:output_RR"),
                List.of("output:input_FL", "output:input_FR", "output:input_RL", "output:input_RR"));
        assertEquals(List.of(new PipeWireRouteManager.Link("rear:output_RL", "output:input_RL"),
                new PipeWireRouteManager.Link("rear:output_RR", "output:input_RR")), links);
    }
}
