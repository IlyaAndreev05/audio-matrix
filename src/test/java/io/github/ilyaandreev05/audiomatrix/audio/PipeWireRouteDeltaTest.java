package io.github.ilyaandreev05.audiomatrix.audio;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.BusPlan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.Link;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.ManagedProcess;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.Plan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.RoutePlan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.StreamPlan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireRouteDeltaTest {
    private static final String BUS_ID = "music";

    @Test
    void streamAndOutputChurnPreservesUnchangedRouteResources() throws Exception {
        try (Harness runtime = new Harness()) {
            Plan initial = plan(List.of(route("out-a", sink(51, "sink-a"), 1.0)), List.of());
            runtime.reconcile(initial);
            Process routeProcess = runtime.routeProcesses.get("out-a").process();
            Set<Link> routeLinks = Set.copyOf(runtime.linkOwners.get("route:out-a"));

            AudioNode stream = stream(201, "firefox-stream");
            Plan withDynamicBindings = plan(
                    List.of(route("out-a", sink(151, "sink-a"), 1.0), route("out-b", sink(52, "sink-b"), 1.0)),
                    List.of(streamPlan(stream)));
            runtime.reconcile(withDynamicBindings);

            assertSame(routeProcess, runtime.routeProcesses.get("out-a").process());
            assertEquals(routeLinks, runtime.linkOwners.get("route:out-a"));
            assertTrue(runtime.liveLinks.containsAll(routeLinks));
            assertEquals(1, runtime.routeStarts.get("out-a"));
            assertNotNull(runtime.linkOwners.get("route:out-b"));
            assertNotNull(runtime.linkOwners.get("stream:music:firefox-stream"));

            Plan afterRemoval = plan(List.of(route("out-a", sink(251, "sink-a"), 1.0)), List.of());
            runtime.reconcile(afterRemoval);

            assertSame(routeProcess, runtime.routeProcesses.get("out-a").process());
            assertEquals(routeLinks, runtime.linkOwners.get("route:out-a"));
            assertTrue(runtime.liveLinks.containsAll(routeLinks));
            assertEquals(1, runtime.routeStarts.get("out-a"));
            assertFalse(runtime.linkOwners.containsKey("route:out-b"));
            assertFalse(runtime.linkOwners.containsKey("stream:music:firefox-stream"));
            assertFalse(runtime.routeProcesses.containsKey("out-b"));
        }
    }

    @Test
    void volumeOnlyDeltaUpdatesLevelsWithoutRecreatingProcessesOrLinks() throws Exception {
        try (Harness runtime = new Harness()) {
            Plan initial = plan(List.of(route("out-a", sink(51, "sink-a"), 1.0)), List.of());
            runtime.reconcile(initial);
            Process routeProcess = runtime.routeProcesses.get("out-a").process();
            Set<Link> routeLinks = Set.copyOf(runtime.linkOwners.get("route:out-a"));
            int levelUpdatesBefore = runtime.levelUpdates;

            runtime.reconcile(plan(List.of(route("out-a", sink(51, "sink-a"), 0.35)), List.of()));

            assertSame(routeProcess, runtime.routeProcesses.get("out-a").process());
            assertEquals(1, runtime.routeStarts.get("out-a"));
            assertEquals(routeLinks, runtime.linkOwners.get("route:out-a"));
            assertEquals(routeLinks, runtime.liveLinks);
            assertTrue(runtime.levelUpdates > levelUpdatesBefore);
        }
    }

    @Test
    void branchFailureDoesNotRemoveOtherOutputOrItsLinks() throws Exception {
        try (Harness runtime = new Harness()) {
            Plan initial = plan(List.of(route("out-a", sink(51, "sink-a"), 1.0)), List.of());
            runtime.reconcile(initial);
            Process routeProcess = runtime.routeProcesses.get("out-a").process();
            Set<Link> routeLinks = Set.copyOf(runtime.linkOwners.get("route:out-a"));
            runtime.failedRoute = "out-b";

            PipeWireRouteManager.ApplyReport report = runtime.reconcile(plan(
                    List.of(route("out-a", sink(51, "sink-a"), 1.0), route("out-b", sink(52, "sink-b"), 1.0)),
                    List.of()));

            assertSame(routeProcess, runtime.routeProcesses.get("out-a").process());
            assertEquals(routeLinks, runtime.linkOwners.get("route:out-a"));
            assertTrue(runtime.liveLinks.containsAll(routeLinks));
            assertFalse(runtime.routeProcesses.containsKey("out-b"));
            assertTrue(runtime.branchErrors.get("route:out-b").contains("synthetic branch failure"));
            assertTrue(report.warnings().stream().anyMatch(warning -> warning.contains("out-b")));
            assertTrue(runtime.active());
        }
    }

    private static Plan plan(List<RoutePlan> routes, List<StreamPlan> streams) {
        BusPlan bus = new BusPlan(BUS_ID, "audiomatrix_test_bus", "stereo", List.of("FL", "FR"), 1.0, false);
        return new Plan(Map.of(BUS_ID, bus), routes, streams, Map.of(), List.of(), false, 1.0, false);
    }

    private static RoutePlan route(String id, AudioNode sink, double volume) {
        return new RoutePlan(id, BUS_ID, null, sink, "stereo", List.of("FL", "FR"), null,
                volume, 0.0, 0.0, false, 0, List.of(), "audiomatrix_capture_" + id,
                "audiomatrix_playback_" + id, "audiomatrix_filter_in_" + id,
                "audiomatrix_filter_out_" + id, "audiomatrix_matrix_in_" + id,
                "audiomatrix_matrix_out_" + id);
    }

    private static StreamPlan streamPlan(AudioNode stream) {
        return new StreamPlan(stream, BUS_ID, List.of(), List.of("FL", "FR"),
                "application:" + stream.name(), "", "", BUS_ID);
    }

    private static AudioNode sink(int id, String name) {
        return new AudioNode(id, name, name, "Audio/Sink", "running", "", "", "", 2,
                List.of(48000), 1.0, false, null);
    }

    private static AudioNode stream(int id, String name) {
        return new AudioNode(id, name, name, "Stream/Output/Audio", "running", "Firefox", "firefox",
                "org.mozilla.firefox", 2, List.of(48000), 1.0, false, null);
    }

    private static final class Harness extends PipeWireRoutePart07 implements AutoCloseable {
        final Set<Link> liveLinks = new LinkedHashSet<>();
        final Map<String, Integer> routeStarts = new LinkedHashMap<>();
        Plan currentPlan;
        String failedRoute = "";
        int levelUpdates;

        PipeWireRouteManager.ApplyReport reconcile(Plan plan) throws Exception {
            currentPlan = plan;
            return reconcilePipeWireGraph(plan, "delta-test", 120);
        }

        @Override protected void startBus(BusPlan bus) {
            ManagedProcess existing = busProcesses.get(bus.id());
            if (existing != null && existing.process().isAlive()) return;
            busProcesses.put(bus.id(), managedProcess());
        }

        @Override protected void startRoute(RoutePlan route) throws Exception {
            if (route.id().equals(failedRoute)) throw new IOException("synthetic branch failure");
            ManagedProcess existing = routeProcesses.get(route.id());
            if (existing != null && existing.process().isAlive()) return;
            routeStarts.merge(route.id(), 1, Integer::sum);
            routeProcesses.put(route.id(), managedProcess());
        }

        @Override protected void startFilterChain(RoutePlan route) {}
        @Override protected void startChannelMatrix(RoutePlan route) {}
        @Override protected void startFilterChain(StreamPlan stream) {}
        @Override protected void startFilterChain(PipeWireRouteManager.OutputPlan output) {}
        @Override protected void startOutputLimiter(PipeWireRouteManager.OutputPlan output) {}

        @Override protected void updateLiveLevels(Plan previous, Plan plan) { levelUpdates++; }

        @Override protected Map<String, List<String>> ports(String direction) {
            Map<String, List<String>> ports = new LinkedHashMap<>();
            if (currentPlan == null) return ports;
            for (BusPlan bus : currentPlan.buses().values()) {
                ports.put(bus.nodeName(), channelPorts(bus.nodeName(), direction));
            }
            for (RoutePlan route : currentPlan.routes()) {
                ports.put(route.captureNodeName(), channelPorts(route.captureNodeName(), direction));
                ports.put(route.playbackNodeName(), channelPorts(route.playbackNodeName(), direction));
                if (route.sink() != null) ports.put(route.sink().name(), channelPorts(route.sink().name(), direction));
            }
            for (StreamPlan stream : currentPlan.streams())
                ports.put(stream.node().name(), channelPorts(stream.node().name(), direction));
            return ports;
        }

        @Override protected String run(List<String> command, Duration timeout) {
            if (command.equals(List.of("pw-link", "-l"))) {
                StringBuilder listing = new StringBuilder();
                for (Link link : liveLinks) listing.append(link.output()).append("\n  |-> ").append(link.input()).append('\n');
                return listing.toString();
            }
            if (command.size() >= 4 && command.get(0).equals("pw-link") && command.get(1).equals("-d")) {
                liveLinks.remove(new Link(command.get(2), command.get(3)));
            } else if (command.size() == 3 && command.get(0).equals("pw-link")) {
                liveLinks.add(new Link(command.get(1), command.get(2)));
            }
            return "";
        }

        @Override public void close() { worker.shutdownNow(); }

        private static List<String> channelPorts(String nodeName, String direction) {
            String prefix = direction.equals("-i") ? "in_" : "out_";
            return List.of(nodeName + ":" + prefix + "FL", nodeName + ":" + prefix + "FR");
        }

        private static ManagedProcess managedProcess() {
            return new ManagedProcess(new FakeProcess(), null, List.of(), null);
        }
    }

    private static final class FakeProcess extends Process {
        private volatile boolean alive = true;

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { alive = false; return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { alive = false; return true; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { alive = false; }
        @Override public Process destroyForcibly() { alive = false; return this; }
        @Override public boolean isAlive() { return alive; }
    }
}
