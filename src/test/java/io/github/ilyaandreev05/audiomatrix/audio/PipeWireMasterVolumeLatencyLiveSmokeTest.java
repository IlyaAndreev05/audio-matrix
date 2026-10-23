package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Measures the real master-volume reaction path on an isolated graph and proves the PCM scaling. */
class PipeWireMasterVolumeLatencyLiveSmokeTest extends PipeWireChannelMatrixRouteLiveSmokeSupport {
    private static final String SYSTEM_SINK = "audiomatrix_system";
    private static final String OUTPUT_SINK = "am_master_latency_output";
    private static final String OUTPUT_MONITOR = "am_master_latency_output_monitor";
    private static final String ROUTE_ID = "master-latency";
    private static final String PLAYBACK_NODE = "audiomatrix_route_" + PipeWireRouteStaticSupport01.shortHash(ROUTE_ID) + "_playback";
    private static final String BUS_NODE = "audiomatrix_bus_" + PipeWireRouteStaticSupport01.shortHash("music");
    private static final String PLAYBACK_STREAM = "am_master_latency_tone";
    private static final double TONE_FREQUENCY = 330.0;
    private static final double[] UI_VOLUMES = {0.5, 0.65, 0.42, 0.6, 0.48, 0.68, 0.44, 0.62, 0.52, 0.58};
    private static final int MEASURED_SAMPLES = 20;

    @Test
    void masterVolumeReachesRoutedPlaybackNodeAndStillScalesPcm() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_MASTER_LATENCY_SMOKE")),
                "Run scripts/pipewire-master-latency-smoke.py");
        for (String command : List.of("pipewire", "pw-link", "pw-dump", "wpctl", "pw-cat"))
            Assumptions.assumeTrue(commandExists(command), "Requires " + command);
        Path resultPath = Path.of(System.getenv("AUDIO_MATRIX_MASTER_LATENCY_RESULT"));
        List<Process> clients = new ArrayList<>();
        List<RouteSmokePcmSupport.ToneWriter> tones = new ArrayList<>();
        PipeWireBackend backend = new PipeWireBackend();
        PipeWireRouteManager manager = new PipeWireRouteManager();
        ConfigStore store = new ConfigStore(tempDir.resolve("config.json"));
        try {
            MasterVolumeLatencyGraph.startSink(tempDir, OUTPUT_SINK,
                    virtualSinkConfig(OUTPUT_SINK, OUTPUT_MONITOR), clients);
            int systemSinkId = MasterVolumeLatencyGraph.awaitNodeId(SYSTEM_SINK);
            run("wpctl", "set-default", Integer.toString(systemSinkId));

            store.save(routeConfig());
            backend.start(snapshot -> { });
            PipeWireBackend.Snapshot current = awaitConnected(backend, systemSinkId);
            manager.apply(store.snapshot(), current).get(30, TimeUnit.SECONDS);
            int playbackNodeId = MasterVolumeLatencyGraph.awaitNodeId(PLAYBACK_NODE);

            List<Long> latencies = new ArrayList<>();
            Map<String, List<Long>> timings = new LinkedHashMap<>();
            try (MasterVolumeLatencyMonitor monitor = MasterVolumeLatencyMonitor.start(playbackNodeId)) {
                for (int index = 0; index < 3; index++)
                    exercise(manager, store, backend, monitor, systemSinkId, UI_VOLUMES[index], null, null);
                for (int index = 0; index < MEASURED_SAMPLES; index++)
                    exercise(manager, store, backend, monitor, systemSinkId,
                            UI_VOLUMES[index % UI_VOLUMES.length], latencies, timings);
                assertMutePath(manager, store, backend, monitor);
                Map<String, Object> result = measurePcmScaling(manager, store, backend, clients, tones);
                result.put("samples", latencies.size());
                result.put("latency_ms_p50", percentileMillis(latencies, 0.50));
                result.put("latency_ms_p95", percentileMillis(latencies, 0.95));
                result.put("latency_ms_max", percentileMillis(latencies, 1.0));
                timings.forEach((name, values) -> {
                    result.put(name + "_p50", percentileMillis(values, 0.50));
                    result.put(name + "_p95", percentileMillis(values, 0.95));
                });
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(resultPath.toFile(), result);
                assertTrue((double) result.get("latency_ms_p95") < 150.0,
                        "Master volume reached the routed playback node too slowly: " + result);
            }
            assertTopologyChangesBypassFastPath(manager, store, backend, systemSinkId);
        } finally {
            for (RouteSmokePcmSupport.ToneWriter tone : tones) tone.stop();
            for (Process client : clients) stop(client);
            try { manager.closeAsync().get(10, TimeUnit.SECONDS); }
            finally { backend.close(); store.close(); }
        }
    }

    private void exercise(PipeWireRouteManager manager, ConfigStore store, PipeWireBackend backend,
                          MasterVolumeLatencyMonitor monitor, int systemSinkId, double uiVolume,
                          List<Long> latencies, Map<String, List<Long>> timings) throws Exception {
        double previous = sinkLevel(backend, systemSinkId);
        run("wpctl", "set-volume", Integer.toString(systemSinkId), Double.toString(uiVolume));
        long commandCompleted = System.nanoTime();
        double linear = awaitSinkLevelChange(backend, systemSinkId, previous);
        long detected = System.nanoTime();
        double master = Math.round(linear * 100.0) / 100.0;
        store.updateAsync(config -> map(config.get("mixer")).put("global_volume", master)).get(5, TimeUnit.SECONDS);
        long configured = System.nanoTime();
        manager.apply(store.snapshot(), backend.current()).get(30, TimeUnit.SECONDS);
        long applied = System.nanoTime();
        MasterVolumeLatencyMonitor.Sample sample = monitor.awaitVolume(master, 0.005, 3000);
        assertNotNull(sample, "The routed playback node never reached " + master + "; actual=" + sinkLevel(backend, systemSinkId));
        if (latencies != null) latencies.add(TimeUnit.NANOSECONDS.toMicros(sample.atNanos() - commandCompleted));
        if (timings != null) {
            timings.computeIfAbsent("detect_ms", ignored -> new ArrayList<>()).add(micros(detected, commandCompleted));
            timings.computeIfAbsent("config_ms", ignored -> new ArrayList<>()).add(micros(configured, detected));
            timings.computeIfAbsent("apply_ms", ignored -> new ArrayList<>()).add(micros(applied, configured));
            timings.computeIfAbsent("observe_ms", ignored -> new ArrayList<>()).add(micros(sample.atNanos(), applied));
        }
    }

    private static long micros(long endNanos, long startNanos) {
        return TimeUnit.NANOSECONDS.toMicros(endNanos - startNanos);
    }

    private void assertMutePath(PipeWireRouteManager manager, ConfigStore store, PipeWireBackend backend,
                                MasterVolumeLatencyMonitor monitor) throws Exception {
        monitor.drain();
        store.updateAsync(config -> map(config.get("mixer")).put("global_mute", true)).get(5, TimeUnit.SECONDS);
        manager.apply(store.snapshot(), backend.current()).get(30, TimeUnit.SECONDS);
        assertNotNull(monitor.awaitMuted(true, 2000), "Master mute must reach the routed playback node");
        store.updateAsync(config -> map(config.get("mixer")).put("global_mute", false)).get(5, TimeUnit.SECONDS);
        manager.apply(store.snapshot(), backend.current()).get(30, TimeUnit.SECONDS);
        assertNotNull(monitor.awaitMuted(false, 2000), "Master unmute must reach the routed playback node");
    }

    private Map<String, Object> measurePcmScaling(PipeWireRouteManager manager, ConfigStore store,
                                                  PipeWireBackend backend, List<Process> clients,
                                                  List<RouteSmokePcmSupport.ToneWriter> tones) throws Exception {
        Path capture = tempDir.resolve("master-volume-pcm.f32");
        Process recorder = RouteSmokePcmSupport.startRecorder("am_master_latency_capture", OUTPUT_MONITOR, capture);
        clients.add(recorder);
        Process tone = RouteSmokePcmSupport.startToneProcess(PLAYBACK_STREAM, BUS_NODE,
                "org.example.MasterVolumeLatency");
        RouteSmokePcmSupport.ToneWriter writer = RouteSmokePcmSupport.ToneWriter.start(tone, TONE_FREQUENCY);
        tones.add(writer);
        Thread.sleep(1000);
        setMaster(manager, store, backend, 0.35);
        Thread.sleep(1200);
        double loud = RouteSmokePcmSupport.amplitude(capture, TONE_FREQUENCY, 0.5);
        setMaster(manager, store, backend, 0.175);
        Thread.sleep(1200);
        double quiet = RouteSmokePcmSupport.amplitude(capture, TONE_FREQUENCY, 0.5);
        double ratio = quiet / loud;
        writer.stop();
        stop(recorder);
        assertTrue(loud > 0.01, "The private output monitor must carry the routed tone, amplitude=" + loud);
        assertTrue(Math.abs(ratio - 0.5) < 0.03, "Master volume must scale the routed PCM by exactly 0.5, measured " + ratio);
        Map<String, Object> measured = new LinkedHashMap<>();
        measured.put("pcm_loud_amplitude", loud);
        measured.put("pcm_quiet_amplitude", quiet);
        measured.put("pcm_master_ratio", ratio);
        return measured;
    }

    private void setMaster(PipeWireRouteManager manager, ConfigStore store, PipeWireBackend backend, double value) throws Exception {
        store.updateAsync(config -> map(config.get("mixer")).put("global_volume", value)).get(5, TimeUnit.SECONDS);
        manager.apply(store.snapshot(), backend.current()).get(30, TimeUnit.SECONDS);
    }

    private void assertTopologyChangesBypassFastPath(PipeWireRouteManager manager, ConfigStore store,
                                                     PipeWireBackend backend, int systemSinkId) throws Exception {
        store.updateAsync(config -> routes(config).getFirst().put("enabled", false)).get(5, TimeUnit.SECONDS);
        manager.apply(store.snapshot(), backend.current()).get(30, TimeUnit.SECONDS);
        assertFalse(manager.active(), "Disabling the only route must rebuild and stop the owned graph");
        assertFalse(MasterVolumeLatencyGraph.nodeExists(PLAYBACK_NODE), "The disabled route playback node must be removed");

        store.updateAsync(config -> routes(config).getFirst().put("enabled", true)).get(5, TimeUnit.SECONDS);
        PipeWireBackend.Snapshot current = backend.refresh().get(5, TimeUnit.SECONDS);
        manager.apply(store.snapshot(), current).get(30, TimeUnit.SECONDS);
        assertTrue(manager.active(), "Re-enabling the route must rebuild the owned graph");
        assertTrue(MasterVolumeLatencyGraph.nodeExists(PLAYBACK_NODE), "The re-enabled route playback node must return");

        assertFalse(manager.branchDiagnostics().containsKey(PLAYBACK_STREAM),
                "A stream that disappeared must lose its owned branch through the full reconcile");
    }

    private static PipeWireBackend.Snapshot awaitConnected(PipeWireBackend backend, int systemSinkId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            PipeWireBackend.Snapshot snapshot = backend.current();
            if (snapshot.connected() && snapshot.defaultSinkId() == systemSinkId) return snapshot;
            Thread.sleep(20);
        }
        throw new IllegalStateException("The backend did not observe the private default sink");
    }

    private static double sinkLevel(PipeWireBackend backend, int systemSinkId) {
        return backend.current().sinks().stream().filter(node -> node.id() == systemSinkId)
                .map(AudioNode::volume).filter(Double::isFinite).findFirst().orElse(0.0);
    }

    private static double awaitSinkLevelChange(PipeWireBackend backend, int systemSinkId, double previous) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (System.nanoTime() < deadline) {
            double level = sinkLevel(backend, systemSinkId);
            if (Double.isFinite(level) && Math.abs(level - previous) > 1e-4) return level;
            Thread.sleep(2);
        }
        throw new IllegalStateException("The backend did not observe the default-sink volume change");
    }

    private static double percentileMillis(List<Long> micros, double percentile) {
        List<Long> sorted = new ArrayList<>(micros);
        sorted.sort(Long::compareTo);
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index) / 1000.0;
    }

    private static Map<String, Object> routeConfig() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> profiles = (List<Map<String, Object>>) config.get("profiles");
        profiles.getFirst().put("active_buses", List.of("music"));
        profiles.getFirst().put("active_routes", List.of(ROUTE_ID));
        config.put("devices", new ArrayList<>(List.of(DefaultConfig.map("id", "latency-output", "selector",
                DefaultConfig.map("kind", "name_equals", "value", OUTPUT_SINK), "role", "speaker", "volume", 1.0, "mute", false))));
        config.put("routes", new ArrayList<>(List.of(DefaultConfig.map("id", ROUTE_ID, "from", "music", "to", "latency-output",
                "enabled", true, "volume", 1.0, "gain_db", 0.0, "channel_map", "stereo"))));
        config.put("graph_default_bus", "music");
        Map<String, Object> mixer = map(config.get("mixer"));
        mixer.put("global_volume", 0.125);
        mixer.put("global_mute", false);
        return config;
    }

    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }

    private static List<Map<String, Object>> routes(Map<String, Object> config) {
        return (List<Map<String, Object>>) config.get("routes");
    }
}
