package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

class PipeWireSinkMetadataTest {
    @Test void missingCountUsesPositionsAndPortsRatherThanDefaultStereo() throws Exception {
        var json = new ObjectMapper();
        var props = json.readTree("{\"audio.position\":[\"FL\",\"FR\",\"SL\",\"SR\"]}");
        var empty = json.readTree("[]");
        assertEquals(4, PipeWireSinkMetadata.channelCount(props, empty, 7));
        var ports = json.readTree("""
                [
                  {"type":"PipeWire:Interface:Port","info":{"props":{"node.id":7,"port.id":0,"port.direction":"in","audio.channel":"FL"}}},
                  {"type":"PipeWire:Interface:Port","info":{"props":{"node.id":7,"port.id":1,"port.direction":"in","audio.channel":"FR"}}},
                  {"type":"PipeWire:Interface:Port","info":{"props":{"node.id":7,"port.id":2,"port.direction":"in","audio.channel":"SL"}}},
                  {"type":"PipeWire:Interface:Port","info":{"props":{"node.id":7,"port.id":3,"port.direction":"in","audio.channel":"SR"}}}
                ]
                """);
        assertEquals(4, PipeWireSinkMetadata.channelCount(json.readTree("{}"), ports, 7));
        var properties = new LinkedHashMap<String, String>();
        PipeWireSinkMetadata.addPortPositions(properties, ports, 7, 4);
        assertEquals("FL,FR,SL,SR", properties.get("audio.position"));
    }

    @Test void duplicatePortPositionsAreNotTrusted() throws Exception {
        var json = new ObjectMapper();
        var ports = json.readTree("""
                [
                  {"type":"PipeWire:Interface:Port","info":{"props":{"node.id":7,"port.id":0,"port.direction":"in","audio.channel":"FL"}}},
                  {"type":"PipeWire:Interface:Port","info":{"props":{"node.id":7,"port.id":1,"port.direction":"in","audio.channel":"FL"}}}
                ]
                """);
        var properties = new LinkedHashMap<String, String>();
        PipeWireSinkMetadata.addPortPositions(properties, ports, 7, 2);
        assertFalse(properties.containsKey("audio.position"));
    }
}
