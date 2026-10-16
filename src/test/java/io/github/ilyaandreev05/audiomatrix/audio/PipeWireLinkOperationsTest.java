package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PipeWireLinkOperationsTest {
    @Test
    void selectsPortsOnlyFromTheRequestedNode() {
        String listing = "sink:monitor_FL\nstream-7:out_FL\nstream-7:out_FR\nstream-70:out_FL\n";
        assertEquals(List.of("stream-7:out_FL", "stream-7:out_FR"),
                PipeWireLinkOperations.portsForNode(listing, "stream-7"));
    }

    @Test
    void parsesBothPipeWireLinkListingOrientations() {
        String listing = """
                stream:out_FL
                  |-> old-sink:playback_FL
                old-sink:playback_FL
                  |<- stream:out_FL
                new-sink:playback_FR
                  |<- stream:out_FR
                other:playback_FL
                  |<- other-source:out_FL
                """;
        assertEquals(List.of(
                new PipeWireRouteManager.Link("stream:out_FL", "old-sink:playback_FL"),
                new PipeWireRouteManager.Link("stream:out_FR", "new-sink:playback_FR")),
                PipeWireLinkOperations.linksFromNode(listing, "stream"));
    }
}
