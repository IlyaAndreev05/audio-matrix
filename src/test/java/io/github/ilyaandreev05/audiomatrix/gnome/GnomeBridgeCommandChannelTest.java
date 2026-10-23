package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * The shell extension sends commands as a broadcast {@code Command} signal on the
 * session bus. The bridge must receive that broadcast: a monitor that only
 * watches messages addressed to a single destination never sees it, and every
 * slider, toggle and profile selection in Quick Settings stays inert.
 */
class GnomeBridgeCommandChannelTest {

    @Test void broadcastCommandSignalReachesTheCommandHandler() throws Exception {
        assumeTrue(sessionBusAvailable(), "A session bus is required for the D-Bus command channel");

        CompletableFuture<GnomeControlBridge.Command> received = new CompletableFuture<>();
        GnomeControlBridge bridge = new GnomeControlBridge(command -> {
            received.complete(command);
            return CompletableFuture.completedFuture(null);
        }, List.of());
        bridge.start();
        try {
            bridge.commandListenerReady().join();
            emitCommand("client:req-1", "rev-1", "set_output_volume", "{\"nodeId\":\"sink-1\",\"value\":0.5}");
            GnomeControlBridge.Command command = received.join();
            assertEquals(GnomeControlBridge.API_VERSION, command.apiVersion());
            assertEquals("client:req-1", command.requestId());
            assertEquals("rev-1", command.revision());
            assertEquals("set_output_volume", command.name());
            assertEquals("{\"nodeId\":\"sink-1\",\"value\":0.5}", command.value());
        } finally {
            bridge.close();
        }
    }

    @Test void publishesOnlyTheLatestStateAfterTheCommandListenerIsReady() {
        assumeTrue(sessionBusAvailable(), "A session bus is required for the D-Bus command channel");

        CompletableFuture<String> emittedState = new CompletableFuture<>();
        AtomicReference<GnomeControlBridge> bridgeReference = new AtomicReference<>();
        AtomicBoolean emittedWhenReady = new AtomicBoolean();
        GnomeControlBridge bridge = new GnomeControlBridge(ignored -> CompletableFuture.completedFuture(null),
                List.of(), ignored -> CompletableFuture.completedFuture(null), (signal, signature, arguments) -> {
                    if ("State".equals(signal)) {
                        emittedWhenReady.set(bridgeReference.get().commandListenerReady().isDone());
                        emittedState.complete(arguments.getFirst());
                    }
                }, true);
        bridgeReference.set(bridge);
        try {
            bridge.emitObservedState(Map.of("marker", "older"));
            bridge.emitObservedState(Map.of("marker", "latest"));
            assertFalse(emittedState.isDone());
            bridge.start();
            bridge.commandListenerReady().join();
            String payload = emittedState.join();
            assertTrue(emittedWhenReady.get());
            assertTrue(payload.contains("\"marker\":\"latest\""));
            assertFalse(payload.contains("older"));
        } finally {
            bridge.close();
        }
    }

    private static boolean sessionBusAvailable() {
        if (System.getenv("DBUS_SESSION_BUS_ADDRESS") == null) return false;
        try {
            Process process = new ProcessBuilder("busctl", "--user", "list")
                    .redirectErrorStream(true).redirectOutput(new java.io.File("/dev/null")).start();
            process.waitFor();
            return process.exitValue() == 0;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static void emitCommand(String requestId, String revision, String name, String payload) throws Exception {
        Process process = new ProcessBuilder("busctl", "--user", "emit", GnomeControlBridge.OBJECT_PATH,
                GnomeControlBridge.INTERFACE, "Command", "ussss", Integer.toString(GnomeControlBridge.API_VERSION),
                requestId, revision, name, payload)
                .redirectErrorStream(true).redirectOutput(new java.io.File("/dev/null")).start();
        process.waitFor();
        assertEquals(0, process.exitValue());
    }
}
