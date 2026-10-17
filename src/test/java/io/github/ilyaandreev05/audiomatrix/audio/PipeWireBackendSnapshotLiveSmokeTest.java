package io.github.ilyaandreev05.audiomatrix.audio;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphPipeWireCompiler;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import io.github.ilyaandreev05.audiomatrix.graph.SwitchNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireBackendSnapshotLiveSmokeTest extends PipeWireBackendReconnectLiveSmokeSupport {
@Test void backendPublishesFreshSnapshotAfterPrivatePipeWireRestarts() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("AUDIO_MATRIX_PIPEWIRE_RECONNECT_SMOKE")),
                "Run scripts/pipewire-reconnect-smoke.py for the isolated daemon restart smoke");
        String runtime = System.getenv("PIPEWIRE_RUNTIME_DIR");
        Assumptions.assumeTrue(runtime != null && !runtime.isBlank(), "An isolated PIPEWIRE_RUNTIME_DIR is required");
        Path runtimePath = Path.of(runtime).toRealPath();
        assertTrue(runtimePath.startsWith(Path.of("/tmp").toRealPath()), "The smoke must use a temporary PipeWire runtime");
        String xdgRuntime = System.getenv("XDG_RUNTIME_DIR");
        Assumptions.assumeTrue(xdgRuntime != null && !xdgRuntime.isBlank(), "An isolated XDG_RUNTIME_DIR is required");
        assertTrue(runtimePath.equals(Path.of(xdgRuntime).toRealPath()),
                "XDG_RUNTIME_DIR must point to the isolated PipeWire runtime");
        assertTrue("pipewire-0".equals(System.getenv("PIPEWIRE_REMOTE")), "The smoke must target its private pipewire-0 socket");

        Process firstServer = startPipeWire();
        Process secondServer = null;
        PipeWireBackend backend = new PipeWireBackend();
        CountDownLatch initialSnapshot = new CountDownLatch(1);
        CountDownLatch recoveredSnapshot = new CountDownLatch(1);
        AtomicReference<PipeWireBackend.Snapshot> recovered = new AtomicReference<>();
        backend.start(snapshot -> {
            if (snapshot.connected() && backend.connectionGeneration() == 0) initialSnapshot.countDown();
            if (snapshot.connected() && backend.connectionGeneration() > 0) {
                recovered.set(snapshot);
                recoveredSnapshot.countDown();
            }
        });
        try {
            assertTrue(waitForCore(firstServer, Duration.ofSeconds(8)), "The private PipeWire server did not start");
            assertTrue(initialSnapshot.await(8, TimeUnit.SECONDS), "Audio Matrix did not publish the initial private-server snapshot");

            firstServer.destroy();
            assertTrue(firstServer.waitFor(5, TimeUnit.SECONDS), "The private PipeWire server did not stop");
            assertTrue(waitForMonitorDisconnect(backend, Duration.ofSeconds(8)), "Audio Matrix monitor did not detect the private server stopping");

            secondServer = startPipeWire();
            assertTrue(waitForCore(secondServer, Duration.ofSeconds(8)), "The restarted private PipeWire server did not start");
            assertTrue(recoveredSnapshot.await(12, TimeUnit.SECONDS), "Audio Matrix did not publish a fresh connected snapshot after reconnect");
            assertTrue(backend.connectionGeneration() > 0, "The backend did not advance its connection generation");
            assertTrue(recovered.get() != null && recovered.get().connected(), "The recovered snapshot must be connected");
        } finally {
            backend.close();
            stop(firstServer);
            if (secondServer != null) stop(secondServer);
        }
    }
}
