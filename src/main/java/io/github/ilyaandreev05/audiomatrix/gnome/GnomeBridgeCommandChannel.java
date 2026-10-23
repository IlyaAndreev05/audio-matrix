package io.github.ilyaandreev05.audiomatrix.gnome;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Owns the broadcast monitor and confirms its D-Bus match before publishing readiness. */
final class GnomeBridgeCommandChannel implements AutoCloseable {
    private static final String COMMAND_RULE = "type='signal',interface='" + GnomeControlBridge.INTERFACE
            + "',member='Command'";
    private static final String READY_RULE = "type='signal',sender='org.freedesktop.DBus',"
            + "interface='org.freedesktop.DBus',member='NameLost'";
    private final Consumer<GnomeControlBridge.Command> commands;
    private final Runnable readyCallback;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();
    private final CompletableFuture<Void> firstReady = new CompletableFuture<>();
    private volatile Process monitor;
    private volatile Process probe;

    GnomeBridgeCommandChannel(Consumer<GnomeControlBridge.Command> commands, Runnable readyCallback) {
        this.commands = commands;
        this.readyCallback = readyCallback;
    }

    void start() {
        Thread.ofVirtual().name("audio-matrix-dbus-monitor").start(this::monitorLoop);
    }

    CompletableFuture<Void> readyFuture() {
        return firstReady.copy();
    }

    boolean isReady() {
        return ready.get();
    }

    private void monitorLoop() {
        while (!closed.get()) {
            Process current = null;
            boolean failed = false;
            try {
                current = new ProcessBuilder("dbus-monitor", "--session", COMMAND_RULE, READY_RULE)
                        .redirectErrorStream(true).start();
                monitor = current;
                Process watched = current;
                Thread.ofVirtual().name("audio-matrix-dbus-ready-probe")
                        .start(() -> probeUntilReady(watched));
                readMessages(current);
            } catch (Exception error) {
                failed = true;
            } finally {
                ready.set(false);
                Process currentProbe = probe;
                if (currentProbe != null) currentProbe.destroyForcibly();
                if (monitor == current) monitor = null;
            }
            if (!closed.get()) sleepBeforeRetry(failed ? 2500 : 1200);
        }
    }

    private void readMessages(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            List<String> arguments = null;
            while (!closed.get() && (line = reader.readLine()) != null) {
                if (isReadyHeader(line)) {
                    markReady();
                    arguments = null;
                    continue;
                }
                if (line.contains("member=Command") || line.contains(GnomeControlBridge.INTERFACE + ".Command")) {
                    GnomeControlBridge.Command command = GnomeControlBridge.parseCommandLine(line);
                    if (command != null) {
                        commands.accept(command);
                        arguments = null;
                    } else {
                        arguments = new ArrayList<>();
                    }
                    continue;
                }
                if (arguments == null) continue;
                if (line.contains("member=")) {
                    arguments = null;
                    continue;
                }
                GnomeControlBridge.Command command = GnomeControlBridge.parseCommandLine(line);
                if (command != null) {
                    commands.accept(command);
                    arguments = null;
                    continue;
                }
                if (!GnomeControlBridge.collectMonitorArgument(line, arguments)) continue;
                command = GnomeControlBridge.parseCommandArguments(arguments);
                if (command != null) {
                    commands.accept(command);
                    arguments = null;
                }
            }
        }
    }

    private void markReady() {
        if (ready.compareAndSet(false, true)) {
            firstReady.complete(null);
            readyCallback.run();
        }
    }

    private static boolean isReadyHeader(String line) {
        return line.startsWith("signal ") && line.contains("sender=org.freedesktop.DBus")
                && line.contains("interface=org.freedesktop.DBus") && line.contains("member=NameLost");
    }

    /*
     * dbus-monitor has no startup acknowledgement. A short-lived busctl client
     * loses its temporary name on exit; retry until that daemon marker reaches
     * this reader, which proves both monitor match rules have been installed.
     */
    private void probeUntilReady(Process watched) {
        while (!closed.get() && watched.isAlive() && !ready.get()) {
            Process attempt = null;
            try {
                String name = "org.ilya.AudioMatrix.Ready.r" + UUID.randomUUID().toString().replace("-", "");
                attempt = new ProcessBuilder("busctl", "--user", "call", "org.freedesktop.DBus",
                        "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName", "su", name, "0")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                probe = attempt;
                if (attempt.waitFor() != 0) {
                    watched.destroyForcibly();
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException unavailable) {
                watched.destroyForcibly();
                return;
            } finally {
                if (probe == attempt) probe = null;
            }
        }
    }

    private void sleepBeforeRetry(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            close();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Process currentMonitor = monitor;
        if (currentMonitor != null) currentMonitor.destroyForcibly();
        Process currentProbe = probe;
        if (currentProbe != null) currentProbe.destroyForcibly();
    }
}
