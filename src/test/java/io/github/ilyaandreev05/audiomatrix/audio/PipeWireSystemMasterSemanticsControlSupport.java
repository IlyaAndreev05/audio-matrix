package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.EventSoundStudioSource;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphPipeWireCompiler;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import io.github.ilyaandreev05.audiomatrix.ui.SystemMasterLiveSmokeSupport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.ilyaandreev05.audiomatrix.audio.PipeWireSystemMasterSemanticsLiveSmokeTest.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Control-plane and live PipeWire assertions for the private system-master PCM smoke. */
final class PipeWireSystemMasterSemanticsControlSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PipeWireSystemMasterSemanticsControlSupport() {}

    static Map<String, Object> routeConfig() {
        Map<String, Object> config = DefaultConfig.create();
        config.put("devices", new ArrayList<>(List.of(DefaultConfig.map(
                "id", "system-master-output", "name", "Private master semantics output",
                "selector", DefaultConfig.map("kind", "name_equals", "value", OUTPUT_SINK),
                "role", "speaker", "volume", 1.0, "mute", false))));
        Map<String, Object> mixer = object(config.get("mixer"));
        mixer.put("global_volume", 1.0);
        mixer.put("global_mute", false);
        return config;
    }

    private static AudioGraph masterGraph() {
        List<String> stereo = List.of("FL", "FR");
        SourceNode system = new SourceNode(SYSTEM_BUS, "System monitor", "system-input", stereo);
        SourceNode events = new SourceNode(EVENT_BUS, "Event monitor", EventSoundStudioSource.SELECTOR, stereo);
        MixerNode mix = new MixerNode("system-master-mix", "Private source mix", stereo,
                Map.of("system", 1.0, "events", 1.0));
        OutputNode output = new OutputNode("system-master-terminal", "Private master output", "system-master-output", stereo);
        return new AudioGraph("system-master-semantics", "Private master semantics",
                List.of(system, events, mix, output), List.of(
                new GraphConnection("system-mix", SYSTEM_BUS, "out", mix.id(), "system", true, 1.0),
                new GraphConnection("events-mix", EVENT_BUS, "out", mix.id(), "events", true, 1.0),
                new GraphConnection("mix-output", mix.id(), "out", output.id(), "in", true, 1.0)));
    }

    static void applyRuntimeGraph(PipeWireRouteManager manager, ConfigStore store,
                                  PipeWireBackend.Snapshot snapshot) throws Exception {
        // Compile the minimal test graph just as the application does. In particular,
        // system_input is a runtime bus role and must NOT be saved as a legacy config bus.
        Map<String, Object> runtime = GraphPipeWireCompiler.compile(masterGraph(), store.snapshot());
        PipeWireRouteManager.Plan plan = manager.plan(runtime, snapshot);
        assertEquals("system_input", plan.buses().get(SYSTEM_BUS).role());
        long terminalRoutes = plan.routes().stream().filter(route -> route.sink() != null).count();
        assertTrue(terminalRoutes > 0, "The private graph must have a terminal master route");
        assertTrue(plan.routes().stream().filter(route -> route.sink() != null)
                .allMatch(route -> OUTPUT_SINK.equals(route.sink().name())),
                "The master proof must route only to its private virtual output");
        manager.apply(runtime, snapshot).get(30, TimeUnit.SECONDS);
    }

    static void applyMasterFromSystemSink(int systemSinkId, double volume, boolean muted,
                                          PipeWireBackend backend, PipeWireRouteManager manager,
                                          ConfigStore store) throws Exception {
        backend.setVolume(systemSinkId, volume).get(8, TimeUnit.SECONDS);
        backend.setMute(systemSinkId, muted).get(8, TimeUnit.SECONDS);
        PipeWireBackend.Snapshot current = backend.refresh().get(10, TimeUnit.SECONDS);
        AudioNode systemSink = sink(current, systemSinkId);
        try {
            assertEquals(volume, systemSink.volume(), 0.02, "The private OSD sink must expose the requested linear level");
            assertEquals(muted, systemSink.muted(), "The private OSD sink must expose the requested mute state");
            assertStoredSystemSinkProps(systemSinkId, volume, muted);
        } catch (AssertionError failure) {
            String diagnostics = PrivatePipeWireSmokeDiagnostics.masterPostWriteFailure(
                    systemSinkId, volume, muted, current, systemSink);
            throw new AssertionError(failure.getMessage() + "\n" + diagnostics, failure);
        }

        SystemMasterLiveSmokeSupport.mirrorObservedSink(store, systemSink).get(8, TimeUnit.SECONDS);
        applyRuntimeGraph(manager, store, current);
        assertSinkConfigMirror(systemSinkId, current, store);
        assertRawMonitorUnity(systemSinkId);
        assertEquals(systemSinkId, current.defaultSinkId(), "Only the private system sink may be the OSD default");
    }


    static void setSystemSinkLevelWhileMuted(int systemSinkId, double volume,
                                             PipeWireBackend backend, PipeWireRouteManager manager,
                                             ConfigStore store) throws Exception {
        backend.setVolume(systemSinkId, volume).get(8, TimeUnit.SECONDS);
        PipeWireBackend.Snapshot current = backend.refresh().get(10, TimeUnit.SECONDS);
        AudioNode systemSink = sink(current, systemSinkId);
        assertEquals(volume, systemSink.volume(), 0.02);
        assertTrue(systemSink.muted(), "A volume change while muted must not unmute the OSD sink");
        assertStoredSystemSinkProps(systemSinkId, volume, true);
        SystemMasterLiveSmokeSupport.mirrorObservedSink(store, systemSink).get(8, TimeUnit.SECONDS);
        applyRuntimeGraph(manager, store, current);
        assertSinkConfigMirror(systemSinkId, current, store);
        assertRawMonitorUnity(systemSinkId);
    }

    static void setSystemSinkLevelOnly(int systemSinkId, double volume, PipeWireBackend backend,
                                       PipeWireRouteManager manager, ConfigStore store) throws Exception {
        backend.setVolume(systemSinkId, volume).get(8, TimeUnit.SECONDS);
        PipeWireBackend.Snapshot current = backend.refresh().get(10, TimeUnit.SECONDS);
        assertEquals(volume, sink(current, systemSinkId).volume(), 0.02);
        assertEquals(1.0, globalVolume(store), 0.01);
        applyRuntimeGraph(manager, store, current);
    }

    private static void assertSinkConfigMirror(int systemSinkId, PipeWireBackend.Snapshot current, ConfigStore store) {
        AudioNode systemSink = sink(current, systemSinkId);
        Map<String, Object> mixer = object(store.snapshot().get("mixer"));
        assertEquals(systemSink.volume(), ((Number) mixer.get("global_volume")).doubleValue(), 0.02,
                "The OSD sink level and terminal master config must mirror one another");
        assertEquals(systemSink.muted(), mixer.get("global_mute"),
                "The OSD sink mute and terminal master config must mirror one another");
    }

    private static JsonNode systemSinkDump(int systemSinkId) throws Exception {
        JsonNode match = null;
        int namedSinks = 0;
        for (JsonNode candidate : JSON.readTree(PipeWireChannelMatrixRouteLiveSmokeSupport.run("pw-dump", "-N"))) {
            if (SYSTEM_SINK.equals(candidate.path("info").path("props").path("node.name").asText())) namedSinks++;
            if (candidate.path("id").asInt(-1) == systemSinkId) match = candidate;
        }
        assertEquals(1, namedSinks, "The source drop-in must provide exactly one stable system sink");
        if (match == null) throw new IOException("The private system sink disappeared from pw-dump");
        return match;
    }

    private static JsonNode liveProps(JsonNode node) {
        JsonNode params = node.path("info").path("params").path("Props");
        assertTrue(params.isArray() && !params.isEmpty(), "The private sink must publish live Props");
        return params.get(0);
    }

    private static void assertStoredSystemSinkProps(int systemSinkId, double volume, boolean muted) throws Exception {
        JsonNode props = liveProps(systemSinkDump(systemSinkId));
        JsonNode channels = props.path("channelVolumes");
        assertTrue(channels.isArray() && channels.size() == 2,
                "The private OSD sink must publish both stored linear channel targets");
        assertEquals(volume, channels.get(0).asDouble(), 0.02,
                "The first channel volume is the stored linear target");
        assertEquals(volume, channels.get(1).asDouble(), 0.02,
                "The second channel volume is the stored linear target");
        assertEquals(muted, props.path("mute").asBoolean(), "Mixer Props must preserve mute independently of volume");
    }

    static void assertRawMonitorUnity(int systemSinkId) throws Exception {
        JsonNode node = systemSinkDump(systemSinkId);
        JsonNode properties = node.path("info").path("props");
        assertEquals(SYSTEM_SINK, properties.path("node.name").asText());
        assertEquals("Общая громкость", properties.path("node.description").asText());
        JsonNode channelVolumes = properties.path("monitor.channel-volumes");
        assertTrue(channelVolumes.isBoolean() && !channelVolumes.booleanValue(),
                "The source drop-in must expose raw PCM, not a renamed or compensated sink");
        JsonNode props = liveProps(node);
        assertTrue(props.path("monitorMute").isBoolean() && !props.path("monitorMute").booleanValue(),
                "The system monitor must remain unmuted");
        JsonNode volumes = props.path("monitorVolumes");
        assertTrue(volumes.isArray() && volumes.size() == 2, "The system monitor must publish both channel levels");
        assertEquals(1.0, volumes.get(0).asDouble(), 1e-6, "The left monitor channel must stay at unity");
        assertEquals(1.0, volumes.get(1).asDouble(), 1e-6, "The right monitor channel must stay at unity");
    }

    static void assertRuntimeMonitorParameter(int systemSinkId) throws Exception {
        JsonNode params = liveProps(systemSinkDump(systemSinkId)).path("params");
        boolean confirmed = false;
        if (params.isArray()) {
            for (int index = 0; index + 1 < params.size(); index += 2)
                if ("monitor.channel-volumes".equals(params.get(index).asText()))
                    confirmed = params.get(index + 1).isBoolean() && !params.get(index + 1).booleanValue();
        } else if (params.isObject()) {
            JsonNode value = params.path("monitor.channel-volumes");
            confirmed = value.isBoolean() && !value.booleanValue();
        }
        assertTrue(confirmed, "The exact live pw-cli monitor.channel-volumes=false syntax was not confirmed in Props; "
                + "do not migrate the host sink with an unverified command. Props params=" + params);
    }

    static void assertPrivateGraphHasNoAlsaHardware() throws Exception {
        JsonNode graph = JSON.readTree(PipeWireChannelMatrixRouteLiveSmokeSupport.run("pw-dump", "-N"));
        for (JsonNode object : graph) {
            JsonNode properties = object.path("info").path("props");
            String deviceApi = properties.path("device.api").asText("");
            assertFalse("alsa".equalsIgnoreCase(deviceApi) || properties.has("api.alsa.path"),
                    "The private master smoke must not discover or use an ALSA hardware node: " + properties);
        }
    }

    private static AudioNode sink(PipeWireBackend.Snapshot snapshot, int id) {
        return snapshot.sinks().stream().filter(node -> node.id() == id).findFirst()
                .orElseThrow(() -> new IllegalStateException("Private system sink disappeared: " + id));
    }

    private static double globalVolume(ConfigStore store) {
        return ((Number) object(store.snapshot().get("mixer")).get("global_volume")).doubleValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }
}
