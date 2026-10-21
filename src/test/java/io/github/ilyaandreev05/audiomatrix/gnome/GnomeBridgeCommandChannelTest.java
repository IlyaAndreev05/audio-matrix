package io.github.ilyaandreev05.audiomatrix.gnome;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
            emitCommand("client:req-1", "rev-1", "set_output_volume", "{\"nodeId\":\"sink-1\",\"value\":0.5}");
            GnomeControlBridge.Command command = received.get(20, TimeUnit.SECONDS);
            assertEquals(GnomeControlBridge.API_VERSION, command.apiVersion());
            assertEquals("client:req-1", command.requestId());
            assertEquals("rev-1", command.revision());
            assertEquals("set_output_volume", command.name());
            assertEquals("{\"nodeId\":\"sink-1\",\"value\":0.5}", command.value());
        } finally {
            bridge.close();
        }
    }

    @Test void monitorArgumentLinesAreParsedFromTheSignalDump() {
        List<String> arguments = new java.util.ArrayList<>();
        assertTrue(GnomeControlBridge.collectMonitorArgument("   uint32 5", arguments));
        assertTrue(GnomeControlBridge.collectMonitorArgument("   string \"client:req-2\"", arguments));
        assertTrue(GnomeControlBridge.collectMonitorArgument("   string \"\"", arguments));
        assertTrue(GnomeControlBridge.collectMonitorArgument("   string \"set_output_volume\"", arguments));
        assertTrue(GnomeControlBridge.collectMonitorArgument("   string \"{\\\"value\\\":0.25}\"", arguments));
        GnomeControlBridge.Command command = GnomeControlBridge.parseCommandArguments(arguments);
        assertNotNull(command);
        assertEquals("client:req-2", command.requestId());
        assertEquals("set_output_volume", command.name());
        assertEquals("{\"value\":0.25}", command.value());
    }

    private static boolean sessionBusAvailable() {
        if (System.getenv("DBUS_SESSION_BUS_ADDRESS") == null) return false;
        try {
            Process process = new ProcessBuilder("busctl", "--user", "list")
                    .redirectErrorStream(true).redirectOutput(new java.io.File("/dev/null")).start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static void emitCommand(String requestId, String revision, String name, String payload) throws Exception {
        Process process = new ProcessBuilder("busctl", "--user", "emit", GnomeControlBridge.OBJECT_PATH,
                GnomeControlBridge.INTERFACE, "Command", "ussss", Integer.toString(GnomeControlBridge.API_VERSION),
                requestId, revision, name, payload)
                .redirectErrorStream(true).redirectOutput(new java.io.File("/dev/null")).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "The Command signal could not be emitted");
        assertEquals(0, process.exitValue());
    }
}
