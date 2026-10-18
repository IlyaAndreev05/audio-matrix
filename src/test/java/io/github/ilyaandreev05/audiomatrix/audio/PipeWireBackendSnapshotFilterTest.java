package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireBackendSnapshotFilterTest {
    @Test
    void includesPhysicalAudioNodesAndTheSystemMixerSink() {
        assertTrue(PipeWireBackend.isSnapshotAudioNode("Audio/Sink", "alsa_output.pci-0000_00_1f.3.analog-stereo"));
        assertTrue(PipeWireBackend.isSnapshotAudioNode("Audio/Source", "alsa_input.pci-0000_00_1f.3.analog-stereo"));
        assertTrue(PipeWireBackend.isSnapshotAudioNode("Stream/Output/Audio", "Firefox"));
        assertTrue(PipeWireBackend.isSnapshotAudioNode("Audio/Sink", PipeWireBackend.SYSTEM_MIXER_SINK_NAME));
    }

    @Test
    void tracksTheIsolatedEventSinkAsAnInputButExcludesItsDiscardNode() {
        assertTrue(PipeWireBackend.isSnapshotAudioNode("Audio/Sink", "audiomatrix_events"),
                "the event sink must be tracked so a route can retry when its monitor appears");
        assertFalse(PipeWireBackend.isSnapshotAudioNode("Stream/Output/Audio", "audiomatrix_events_discard"));
        assertFalse(PipeWireBackend.isSnapshotAudioNode("Video/Source", "camera"));
        assertFalse(PipeWireBackend.isSnapshotAudioNode("Audio/Sink", ""));
        assertFalse(PipeWireBackend.isSnapshotAudioNode(null, "speaker"));
    }

    @Test
    void exposesTheIsolatedEventSinkAsANonPhysicalSource(){
        AudioNode sink=new AudioNode(203,EventInputMonitor.NODE_NAME,"Audio Matrix event input","Audio/Sink","running",
                "","","",2,java.util.List.of(48000),1.0,false,null);
        AudioNode source=EventInputMonitor.sourceView(sink);
        assertEquals("Audio/Source",source.mediaClass());
        assertEquals(EventInputMonitor.NODE_NAME,source.name());
        assertTrue(EventInputMonitor.isNode(source));
    }

    @Test
    void readsTheEventSinkMonitorAsASourceRatherThanAnOutput() throws IOException {
        JsonNode root=new ObjectMapper().readTree("""
                [{"id":203,"type":"PipeWire:Interface:Node","info":{"state":"running","props":{
                  "node.name":"audiomatrix_events","node.description":"Audio Matrix event input",
                  "media.class":"Audio/Sink","audio.channels":2
                }}}]
                """);
        java.util.List<AudioNode> sinks=new java.util.ArrayList<>(),sources=new java.util.ArrayList<>(),streams=new java.util.ArrayList<>();

        PipeWireSnapshotReader.readNodes(root,java.util.Map.of(),java.util.Map.of(),sinks,sources,streams);

        assertTrue(sinks.isEmpty());
        assertEquals(1,sources.size());
        assertEquals("Audio/Source",sources.getFirst().mediaClass());
        assertTrue(EventInputMonitor.isNode(sources.getFirst()));
    }

    @Test
    void inheritsMissingApplicationIdentityFromPipeWireClient() throws IOException {
        JsonNode root = new ObjectMapper().readTree("""
                [
                  {"id":348,"type":"PipeWire:Interface:Client","info":{"props":{
                    "application.name":"Event Sound Studio",
                    "application.process.binary":"EventSoundStudio",
                    "application.id":"io.github.ilyaandreev05.eventsoundstudio"
                  }}},
                  {"id":349,"type":"PipeWire:Interface:Node","info":{"props":{
                    "client.id":348,"node.name":"pw-cat","node.description":"Event effects"
                  }}}
                ]
                """);

        var identity = PipeWireBackend.applicationIdentity(root.get(1).path("info").path("props"),
                PipeWireBackend.clientProperties(root));

        assertEquals("Event Sound Studio", identity.applicationName());
        assertEquals("EventSoundStudio", identity.processName());
        assertEquals("io.github.ilyaandreev05.eventsoundstudio", identity.applicationId());
    }

    @Test
    void nodeIdentityOverridesPipeWireClientFallback() throws IOException {
        JsonNode root = new ObjectMapper().readTree("""
                [
                  {"id":348,"type":"PipeWire:Interface:Client","info":{"props":{
                    "application.name":"Client name","application.process.binary":"client-process",
                    "application.id":"client.id"
                  }}},
                  {"id":349,"type":"PipeWire:Interface:Node","info":{"props":{
                    "client.id":348,"application.name":"Node name","application.id":"node.id"
                  }}}
                ]
                """);

        var identity = PipeWireBackend.applicationIdentity(root.get(1).path("info").path("props"),
                PipeWireBackend.clientProperties(root));

        assertEquals("Node name", identity.applicationName());
        assertEquals("client-process", identity.processName());
        assertEquals("node.id", identity.applicationId());
    }
}
