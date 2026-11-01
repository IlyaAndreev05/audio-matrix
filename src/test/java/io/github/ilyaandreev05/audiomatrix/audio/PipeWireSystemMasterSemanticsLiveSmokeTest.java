package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.graph.EventSoundStudioSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves private system and ESS monitor sources receive the configured master exactly once. */
class PipeWireSystemMasterSemanticsLiveSmokeTest extends PipeWireChannelMatrixRouteLiveSmokeSupport {
    static final String SYSTEM_SINK = "audiomatrix_system";
    static final String EVENT_SINK = "audiomatrix_events";
    static final String OUTPUT_SINK = "am_system_master_semantics_output";
    static final String OUTPUT_MONITOR = "am_system_master_semantics_monitor";
    static final String OUTPUT_CAPTURE = "am_system_master_semantics_capture";
    static final String SYSTEM_BUS = "system-master-system-input";
    static final String EVENT_BUS = "system-master-event-input";
    static final String SYSTEM_TONE = "am_system_master_semantics_system_tone";
    static final String EVENT_TONE = "am_system_master_semantics_event_tone";
    static final String SYSTEM_APP_ID = "org.example.AudioMatrixSystemMasterSemantics";
    static final double TONE_FREQUENCY = 440.0;
    static final double MEASURE_WINDOW_SECONDS = 0.5;
    static final double VOLUME_TOLERANCE = 0.05;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void systemAndEventSourcesShareOneMasterGainExactlyOnce() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE")),
                "Run scripts/pipewire-route-smoke.py in its isolated PipeWire session");
        for (String command : List.of("pipewire", "pw-cli", "pw-dump", "pw-link", "pw-cat", "pw-loopback", "wpctl"))
            Assumptions.assumeTrue(commandExists(command), "Requires " + command);

        Path privateRuntime = Path.of(System.getenv("XDG_RUNTIME_DIR")).toRealPath();
        assertTrue(privateRuntime.toString().startsWith("/tmp/audio-matrix-private-route-"),
                "This smoke must only connect to the isolated route-smoke runtime");
        assertEquals(privateRuntime, Path.of(System.getenv("PIPEWIRE_RUNTIME_DIR")).toRealPath());
        assertEquals("pipewire-0", System.getenv("PIPEWIRE_REMOTE"));

        List<Process> clients = new ArrayList<>();
        PipeWireBackend backend = new PipeWireBackend();
        PipeWireRouteManager manager = new PipeWireRouteManager();
        ConfigStore store = new ConfigStore(tempDir.resolve("config.json"));
        PipeWireSystemMasterSemanticsPcmSupport pcm = new PipeWireSystemMasterSemanticsPcmSupport(tempDir);
        try {
            Path outputConfig = tempDir.resolve("system-master-output.conf");
            Files.writeString(outputConfig, virtualSinkConfig(OUTPUT_SINK, OUTPUT_MONITOR));
            Path outputLog = tempDir.resolve("system-master-output.log");
            Process outputClient = new ProcessBuilder("pipewire", "-c", outputConfig.toString())
                    .redirectErrorStream(true).redirectOutput(outputLog.toFile()).start();
            clients.add(outputClient);

            Path eventLog = tempDir.resolve("system-master-events.log");
            Process eventClient = new ProcessBuilder("pw-loopback", "-n", EVENT_SINK, "-l", "10", "-c", "2",
                    "-m", "[ FL FR ]", "-i", EVENT_CAPTURE_PROPS, "-o", EVENT_DISCARD_PROPS)
                    .redirectErrorStream(true).redirectOutput(eventLog.toFile()).start();
            clients.add(eventClient);
            awaitNodeState(EVENT_SINK, true);

            backend.start(snapshot -> { });
            AudioNode systemSink = waitForSink(backend, outputClient, SYSTEM_SINK, outputLog);
            waitForSink(backend, outputClient, OUTPUT_SINK, outputLog);
            backend.setDefaultOutput(systemSink.id()).get(8, TimeUnit.SECONDS);

            PipeWireSystemMasterSemanticsControlSupport.assertPrivateGraphHasNoAlsaHardware();
            // Check the source drop-in before the runtime command can mask a startup regression.
            PipeWireSystemMasterSemanticsControlSupport.assertRawMonitorUnity(systemSink.id());

            store.save(PipeWireSystemMasterSemanticsControlSupport.routeConfig());
            PipeWireBackend.Snapshot current = backend.refresh().get(10, TimeUnit.SECONDS);
            PipeWireSystemMasterSemanticsControlSupport.applyRuntimeGraph(manager, store, current);

            PipeWireSystemMasterSemanticsPcmSupport.Capture output = pcm.startCapture(OUTPUT_CAPTURE, OUTPUT_MONITOR);
            clients.add(output.process());
            Map<String, Double> system = pcm.verifyMasterPath("ordinary system monitor", SYSTEM_SINK, SYSTEM_APP_ID,
                    SYSTEM_TONE, systemSink.id(), output, backend, manager, store);
            Map<String, Double> event = pcm.verifyMasterPath("ESS event monitor", EVENT_SINK,
                    EventSoundStudioSource.APPLICATION_ID, EVENT_TONE, systemSink.id(), output, backend, manager, store);
            assertEquals(1.0, event.get("full_rms") / system.get("full_rms"), VOLUME_TOLERANCE,
                    "The identical system and event signals must share the same unity terminal gain");

            // Validate the exact planned live-migration syntax only in this private graph.
            // A successful exit is insufficient: require the live parameter back in pw-dump.
            run("pw-cli", "set-param", Integer.toString(systemSink.id()), "Props",
                    "{ params = [ \"monitor.channel-volumes\" false ] monitorMute = false monitorVolumes = [ 1.0 1.0 ] }");
            PipeWireSystemMasterSemanticsControlSupport.assertRuntimeMonitorParameter(systemSink.id());
            PipeWireSystemMasterSemanticsControlSupport.assertRawMonitorUnity(systemSink.id());
            Map<String, Double> updated = pcm.verifyMasterPath("system monitor after runtime Props update",
                    SYSTEM_SINK, SYSTEM_APP_ID, SYSTEM_TONE, systemSink.id(), output, backend, manager, store);
            PipeWireSystemMasterSemanticsControlSupport.assertPrivateGraphHasNoAlsaHardware();
            JSON.writeValue(privateRuntime.resolve("system-master-result.json").toFile(), Map.of(
                    "system", system, "event", event, "after_runtime_update", updated,
                    "runtime_monitor_parameter_confirmed", true, "no_alsa_hardware", true));
        } finally {
            for (Process client : clients.reversed()) stop(client);
            try { manager.closeAsync().get(10, TimeUnit.SECONDS); }
            finally { backend.close(); store.close(); }
        }
    }

    private static final String EVENT_CAPTURE_PROPS = "{\"node.name\":\"audiomatrix_events\",\"node.description\":\"Audio Matrix event input\",\"media.class\":\"Audio/Sink\",\"audio.position\":[\"FL\",\"FR\"],\"node.virtual\":true,\"node.autoconnect\":false}";
    private static final String EVENT_DISCARD_PROPS = "{\"node.name\":\"audiomatrix_events_discard\",\"node.description\":\"Audio Matrix event sink isolation\",\"node.autoconnect\":false,\"node.passive\":true,\"stream.dont-remix\":true,\"audio.position\":[\"FL\",\"FR\"]}";
}
