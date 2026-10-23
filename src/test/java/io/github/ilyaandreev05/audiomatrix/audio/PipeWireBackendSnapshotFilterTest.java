package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    void treatsASinkMonitorAsSignalFeedbackAndNotAnApplicationStream() {
        AudioNode sink=new AudioNode(12,"alsa_output.pci-0000_09_00.1.hdmi-stereo","BenQ","Audio/Sink","running",
                "","","",2,java.util.List.of(48000),1.0,false,null);
        AudioNode monitor=new AudioNode(13,"alsa_output.pci-0000_09_00.1.hdmi-stereo_monitor","Monitor of BenQ",
                "Stream/Output/Audio","running","","","",2,java.util.List.of(48000),1.0,false,null);
        AudioNode playback=new AudioNode(14,"Firefox","Firefox","Stream/Output/Audio","running",
                "Firefox","firefox","org.mozilla.firefox",2,java.util.List.of(48000),1.0,false,null);

        assertTrue(PipeWireSnapshotReader.isSinkMonitor(monitor,java.util.List.of(sink)),
                "a monitor of a known sink must not be routed as an application");
        assertFalse(PipeWireSnapshotReader.isSinkMonitor(playback,java.util.List.of(sink)),
                "a real playback stream stays an application");
        assertFalse(PipeWireSnapshotReader.isSinkMonitor(monitor,java.util.List.of()),
                "without a matching sink the node is not treated as a monitor");
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
    void readsNamelessPlaybackUsingClientIdentityAndPreservesNodeOverrides() throws IOException {
        JsonNode root = new ObjectMapper().readTree("""
                [
                  {"id":348,"type":"PipeWire:Interface:Client","info":{"props":{
                    "application.name":"Client player","application.process.binary":"client-player",
                    "application.id":"org.example.client"
                  }}},
                  {"id":349,"type":"PipeWire:Interface:Node","info":{"state":"running","props":{
                    "client.id":348,"media.class":"Stream/Output/Audio"
                  }}},
                  {"id":350,"type":"PipeWire:Interface:Node","info":{"state":"running","props":{
                    "client.id":348,"node.name":"","media.class":"Stream/Output/Audio",
                    "application.name":"Node player","application.process.binary":"node-player",
                    "application.id":"org.example.node"
                  }}}
                ]
                """);
        List<AudioNode> sinks = new ArrayList<>(), sources = new ArrayList<>(), streams = new ArrayList<>();

        PipeWireSnapshotReader.readNodes(root, PipeWireSnapshotReader.clientProperties(root), Map.of(),
                sinks, sources, streams);

        assertTrue(sinks.isEmpty());
        assertTrue(sources.isEmpty());
        assertEquals(List.of(349, 350), streams.stream().map(AudioNode::id).toList());
        AudioNode inherited = streams.getFirst();
        assertEquals("", inherited.name(), "a live node id must not become a synthetic routing name");
        assertEquals("Client player", inherited.applicationName());
        assertEquals("client-player", inherited.processName());
        assertEquals("org.example.client", inherited.applicationId());
        AudioNode overridden = streams.get(1);
        assertEquals("", overridden.name());
        assertEquals("Node player", overridden.applicationName());
        assertEquals("node-player", overridden.processName());
        assertEquals("org.example.node", overridden.applicationId());
    }

    @Test
    void admitsNamelessNodesOnlyForIdentifiedLivePlayback() throws IOException {
        JsonNode root = new ObjectMapper().readTree("""
                [
                  {"id":300,"type":"PipeWire:Interface:Client","info":{"props":{
                    "application.name":"Display name only"
                  }}},
                  {"id":301,"type":"PipeWire:Interface:Client","info":{"props":{
                    "application.process.binary":"client-player"
                  }}},
                  {"id":100,"type":"PipeWire:Interface:Node","info":{"props":{
                    "media.class":"Stream/Output/Audio","application.id":"org.example.player"
                  }}},
                  {"id":101,"type":"PipeWire:Interface:Node","info":{"props":{
                    "node.name":"","media.class":"Stream/Output/Audio","client.id":301
                  }}},
                  {"id":102,"type":"PipeWire:Interface:Node","info":{"props":{
                    "node.name":"","media.class":"Stream/Output/Audio"
                  }}},
                  {"id":103,"type":"PipeWire:Interface:Node","info":{"props":{
                    "node.name":"","media.class":"Stream/Output/Audio","client.id":300
                  }}},
                  {"id":104,"type":"PipeWire:Interface:Node","info":{"props":{
                    "node.name":" ","media.class":"Stream/Output/Audio",
                    "application.id":" ","application.process.binary":" "
                  }}},
                  {"id":-1,"type":"PipeWire:Interface:Node","info":{"props":{
                    "node.name":"","media.class":"Stream/Output/Audio","application.id":"org.example.player"
                  }}},
                  {"type":"PipeWire:Interface:Node","info":{"props":{
                    "node.name":"","media.class":"Stream/Output/Audio","application.id":"org.example.player"
                  }}},
                  {"id":106,"type":"PipeWire:Interface:Node","info":{"props":{
                    "node.name":"","media.class":"Audio/Sink","application.id":"org.example.player"
                  }}},
                  {"id":107,"type":"PipeWire:Interface:Node","info":{"props":{
                    "node.name":"","media.class":"Audio/Source","application.process.binary":"player"
                  }}},
                  {"id":108,"type":"PipeWire:Interface:Node","info":{"props":{
                    "node.name":"","media.class":"Stream/Input/Audio","application.id":"org.example.player"
                  }}},
                  {"id":109,"type":"PipeWire:Interface:Node","info":{"props":{
                    "node.name":"","media.class":"Video/Source","application.id":"org.example.player"
                  }}}
                ]
                """);
        List<AudioNode> sinks = new ArrayList<>(), sources = new ArrayList<>(), streams = new ArrayList<>();

        PipeWireSnapshotReader.readNodes(root, PipeWireSnapshotReader.clientProperties(root), Map.of(),
                sinks, sources, streams);

        assertTrue(sinks.isEmpty(), "identity must not admit a nameless physical sink");
        assertTrue(sources.isEmpty(), "identity must not admit a nameless physical source");
        assertEquals(List.of(100, 101), streams.stream().map(AudioNode::id).toList());
        assertEquals("org.example.player", streams.getFirst().applicationId());
        assertEquals("client-player", streams.get(1).processName());
        assertTrue(streams.stream().allMatch(node -> node.name().isEmpty()));
    }

    @Test
    void preservesNamedInternalFiltersAndNamelessOwnershipMetadata() throws IOException {
        JsonNode root = new ObjectMapper().readTree("""
                [
                  {"id":201,"type":"PipeWire:Interface:Node","info":{"props":{
                    "node.name":"audiomatrix_route","media.class":"Stream/Output/Audio"
                  }}},
                  {"id":202,"type":"PipeWire:Interface:Node","info":{"props":{
                    "node.name":"audiomatrix_events_discard","media.class":"Stream/Output/Audio"
                  }}},
                  {"id":203,"type":"PipeWire:Interface:Node","info":{"props":{
                    "node.name":"audiomatrix_events","media.class":"Audio/Sink"
                  }}},
                  {"id":204,"type":"PipeWire:Interface:Node","info":{"props":{
                    "node.name":"audiomatrix_system","media.class":"Audio/Sink"
                  }}},
                  {"id":205,"type":"PipeWire:Interface:Node","info":{"props":{
                    "node.name":"","media.class":"Stream/Output/Audio",
                    "application.id":"io.github.ilyaandreev05.audiomatrix"
                  }}}
                ]
                """);
        List<AudioNode> sinks = new ArrayList<>(), sources = new ArrayList<>(), streams = new ArrayList<>();

        PipeWireSnapshotReader.readNodes(root, Map.of(), Map.of(), sinks, sources, streams);

        assertEquals(List.of(204), sinks.stream().map(AudioNode::id).toList());
        assertEquals(List.of(203), sources.stream().map(AudioNode::id).toList());
        assertTrue(EventInputMonitor.isNode(sources.getFirst()));
        assertEquals(List.of(205), streams.stream().map(AudioNode::id).toList());
        assertEquals("", streams.getFirst().name());
        assertTrue(AudioMatrixOwnedStream.matches(streams.getFirst()),
                "existing downstream exclusion must still recognize an owned nameless stream");
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
