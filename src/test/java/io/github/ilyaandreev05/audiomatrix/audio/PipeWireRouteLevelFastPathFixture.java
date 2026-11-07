package io.github.ilyaandreev05.audiomatrix.audio;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.BranchDiagnostic;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.BusPlan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.Link;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.OutputPlan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.Plan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.RoutePlan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.StreamPlan;

/** Plans, a healthy diagnostic map and a stub runtime shared by the route level fast-path tests. */
abstract class PipeWireRouteLevelFastPathFixture {
    protected static final AudioNode SINK = new AudioNode(7, "test-output", "Test output", "Audio/Sink", "running",
            "", "", "", 2, List.of(48000), 1.0, false, null);
    protected static final Link LINK = new Link("audiomatrix_route_playback_route:out", "test-output:in");

    static Harness harness(Plan applied) {
        Harness runtime = new Harness();
        runtime.appliedPlan = applied;
        runtime.active = true;
        runtime.branchDiagnostics = healthy(applied);
        return runtime;
    }

    static Plan plan(double masterVolume, boolean masterMuted, List<StreamPlan> streams, List<RoutePlan> routes) {
        BusPlan bus = new BusPlan("music", "audiomatrix_bus_music", "music", "stereo",
                List.of("FL", "FR"), 1.0, false);
        return new Plan(Map.of("music", bus), routes, streams, Map.of(), List.of(), false, masterVolume, masterMuted);
    }

    static Plan withOutput(Plan plan) {
        OutputPlan output = new OutputPlan(SINK, List.of("FL", "FR"), List.of(), List.of(), "in", "out", "lin", "lout");
        return new Plan(plan.buses(), plan.routes(), plan.streams(), Map.of(SINK.name(), output),
                plan.warnings(), false, plan.masterVolume(), plan.masterMuted());
    }

    static Plan withBusVolume(Plan plan, double volume) {
        BusPlan bus = plan.buses().get("music");
        BusPlan changed = new BusPlan(bus.id(), bus.nodeName(), bus.role(), bus.channelLayout(), bus.positions(), volume, bus.muted());
        return new Plan(Map.of("music", changed), plan.routes(), plan.streams(), plan.outputs(),
                plan.warnings(), plan.profileOff(), plan.masterVolume(), plan.masterMuted());
    }

    static RoutePlan route(String id, double volume) {
        return new RoutePlan(id, "music", null, SINK, "stereo", List.of("FL", "FR"), null, volume, 0, 0, false, 0,
                List.of(), "audiomatrix_route_capture_" + id, "audiomatrix_route_playback_" + id,
                "audiomatrix_route_dsp_input_" + id, "audiomatrix_route_dsp_output_" + id,
                "audiomatrix_route_matrix_input_" + id, "audiomatrix_route_matrix_output_" + id);
    }

    static RoutePlan internalRoute(String id, double volume) {
        return new RoutePlan(id, "music", "effects", null, "stereo", List.of("FL", "FR"), null, volume, 0, 0, false, 0,
                List.of(), "audiomatrix_route_capture_" + id, "audiomatrix_route_playback_" + id,
                "audiomatrix_route_dsp_input_" + id, "audiomatrix_route_dsp_output_" + id,
                "audiomatrix_route_matrix_input_" + id, "audiomatrix_route_matrix_output_" + id);
    }

    static StreamPlan stream(String name) {
        return new StreamPlan(new AudioNode(31, name, name, "Stream/Output/Audio", "running", "Test", "test", "", 2,
                List.of(48000), 1.0, false, null), "", "", "music", List.of(), List.of("FL", "FR"),
                "application:" + name, "in", "out");
    }

    static Map<String, BranchDiagnostic> healthy(Plan plan) {
        Set<String> owners = new LinkedHashSet<>();
        plan.buses().keySet().forEach(owners::add);
        plan.routes().forEach(route -> owners.add(route.id()));
        plan.outputs().values().forEach(output -> owners.add(owner(output.sink())));
        plan.streams().forEach(stream -> owners.add(stream.node().name()));
        Map<String, BranchDiagnostic> diagnostics = new LinkedHashMap<>();
        for (String owner : owners)
            diagnostics.put(owner, new BranchDiagnostic(owner, "branch", true, true, true,
                    List.of(LINK), List.of(LINK), List.of(), ""));
        return diagnostics;
    }

    static String owner(AudioNode sink) {
        String fingerprint = sink.identity() == null ? "" : sink.identity().fingerprint();
        return fingerprint == null || fingerprint.isBlank() ? sink.name() : fingerprint;
    }

    /** Records every level write instead of touching PipeWire. */
    static final class Harness extends PipeWireRoutePart07 {
        final List<List<String>> commands = new ArrayList<>();
        final List<String> volumes = new ArrayList<>();
        final List<String> mutes = new ArrayList<>();
        boolean omitOwnedNode;

        @Override protected String run(List<String> command, Duration timeout) {
            commands.add(List.copyOf(command));
            if (command.equals(List.of("pw-link", "-l")) || command.equals(List.of("pw-link", "-i"))
                    || command.equals(List.of("pw-link", "-o"))) return "";
            if (command.equals(List.of("pw-dump", "-N"))) return "[]";
            throw new IllegalStateException("Unexpected command: " + command);
        }

        @Override protected Map<String, Integer> findNodeIds(Set<String> names) {
            Map<String, Integer> ids = new LinkedHashMap<>();
            for (String name : names) {
                if (omitOwnedNode) return Map.of();
                ids.put(name, 1);
            }
            return ids;
        }

        @Override protected int findNodeId(String nodeName) { return 1; }
        @Override protected void setNodeVolume(int nodeId, double volume) { volumes.add(nodeId + ":" + volume); }
        @Override protected void setNodeMuted(int nodeId, boolean muted) { mutes.add(nodeId + ":" + muted); }
        @Override protected void startBus(BusPlan bus) { }
        @Override protected void startRoute(RoutePlan route) { }
        @Override protected void startFilterChain(RoutePlan route) { }
        @Override protected void startChannelMatrix(RoutePlan route) { }
        @Override protected void startFilterChain(StreamPlan stream) { }
        @Override protected void startFilterChain(PipeWireRouteTypes.OutputPlan output) { }
        @Override protected void startOutputLimiter(PipeWireRouteTypes.OutputPlan output) { }
        @Override protected void stopProcesses(Map<String, PipeWireRouteTypes.ManagedProcess> processes) { }
        @Override public void close() { worker.shutdownNow(); }
    }
}
