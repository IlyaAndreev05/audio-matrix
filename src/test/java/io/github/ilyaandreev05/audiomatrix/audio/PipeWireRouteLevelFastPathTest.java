package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.BranchDiagnostic;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.Plan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.StreamPlan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireRouteLevelFastPathTest extends PipeWireRouteLevelFastPathFixture {
    @Test
    void masterVolumeChangeWritesOnlyTheTerminalRouteLevel() throws Exception {
        Plan previous = withOutput(plan(0.5, false, List.of(), List.of(route("route", 1.0))));
        Plan next = withOutput(plan(0.25, false, List.of(), List.of(route("route", 1.0))));
        try (Harness runtime = harness(previous)) {
            runtime.reconcilePipeWireGraph(next, "next", 0);

            assertTrue(runtime.commands.isEmpty(), "A level-only apply must not run pw-link or pw-dump");
            assertEquals(List.of("1:0.25"), runtime.volumes);
            assertTrue(runtime.mutes.isEmpty());
            assertEquals(next, runtime.appliedPlan);
        }
    }

    @Test
    void masterLevelUpdatesRouteNodesWithoutRebuild() throws Exception {
        try (Harness runtime = harness(plan(0.5, false, List.of(), List.of(route("route", 0.5), route("other", 1.0))))) {
            runtime.applyMasterLevel(0.25, false).get(5, TimeUnit.SECONDS);

            assertTrue(runtime.commands.isEmpty(), "A direct master update must not run pw-link or pw-dump");
            assertEquals(List.of("1:0.125", "1:0.25"), runtime.volumes);
            assertEquals(List.of("1:false", "1:false"), runtime.mutes);

            runtime.applyMasterLevel(0.5, true).get(5, TimeUnit.SECONDS);

            assertTrue(runtime.commands.isEmpty());
            assertEquals(List.of("1:0.125", "1:0.25", "1:0.25", "1:0.5"), runtime.volumes);
            assertEquals(List.of("1:false", "1:false", "1:true", "1:true"), runtime.mutes);
        }
    }

    @Test
    void masterLevelLeavesAnInternalRouteUnscaled() throws Exception {
        try (Harness runtime = harness(plan(0.5, false, List.of(), List.of(internalRoute("chain", 0.5))))) {
            runtime.applyMasterLevel(0.25, true).get(5, TimeUnit.SECONDS);

            assertEquals(List.of("1:0.5"), runtime.volumes, "An internal bus route never carries the physical master");
            assertEquals(List.of("1:false"), runtime.mutes);
        }
    }

    @Test
    void configuredRouteVolumeStillMultipliesMasterOnce() throws Exception {
        Plan previous = plan(0.5, false, List.of(), List.of(route("route", 0.5)));
        try (Harness runtime = harness(previous)) {
            runtime.reconcilePipeWireGraph(plan(0.25, false, List.of(), List.of(route("route", 0.5))), "next", 0);
            assertEquals(List.of("1:0.125"), runtime.volumes);
        }
        try (Harness runtime = harness(previous)) {
            runtime.reconcilePipeWireGraph(plan(0.2, false, List.of(), List.of(route("route", 0.5))), "next", 0);
            assertEquals(List.of("1:0.1"), runtime.volumes, "Master must be applied exactly once");
        }
    }

    @Test
    void masterMuteKeepsTheComputedLevelAndOnlyTogglesMute() throws Exception {
        Plan previous = plan(0.5, false, List.of(), List.of(route("route", 1.0)));
        Plan next = plan(0.5, true, List.of(), List.of(route("route", 1.0)));
        try (Harness runtime = harness(previous)) {
            runtime.reconcilePipeWireGraph(next, "next", 0);
            assertTrue(runtime.volumes.isEmpty(), "The full reconciler also leaves the level untouched when only mute changes");
            assertEquals(List.of("1:true"), runtime.mutes);
        }
    }

    @Test
    void loweringRoutesAreWrittenBeforeRaisingOnes() throws Exception {
        Plan previous = plan(0.5, false, List.of(), List.of(route("loud", 1.0), route("quiet", 0.1)));
        Plan next = plan(0.5, false, List.of(), List.of(route("loud", 0.1), route("quiet", 1.0)));
        try (Harness runtime = harness(previous)) {
            runtime.reconcilePipeWireGraph(next, "next", 0);
            assertEquals(List.of("1:0.05", "1:0.5"), runtime.volumes,
                    "The lowering route must reach its quieter level before the other route is raised");
        }
    }

    @Test
    void busLevelChangeDoesNotTouchTheRouteLevel() throws Exception {
        Plan previous = plan(0.5, false, List.of(), List.of(route("route", 1.0)));
        Plan next = plan(0.5, false, List.of(), List.of(route("route", 1.0)));
        try (Harness runtime = harness(previous)) {
            runtime.reconcilePipeWireGraph(withBusVolume(next, 0.4), "next", 0);
            assertEquals(List.of("1:0.4"), runtime.volumes);
        }
    }

    @Test
    void streamAppearanceNeverUsesTheLevelFastPath() throws Exception {
        Plan next = plan(0.25, false, List.of(stream("app-stream")), List.of(route("route", 1.0)));
        try (Harness runtime = harness(plan(0.5, false, List.of(), List.of(route("route", 1.0))))) {
            runtime.reconcilePipeWireGraph(next, "next", 0);
            assertFalse(runtime.commands.isEmpty(), "A new owned stream must run the full topology reconcile");
        }
    }

    @Test
    void streamDisappearanceNeverUsesTheLevelFastPath() throws Exception {
        Plan previous = plan(0.5, false, List.of(stream("app-stream")), List.of(route("route", 1.0)));
        try (Harness runtime = harness(previous)) {
            runtime.reconcilePipeWireGraph(plan(0.25, false, List.of(), List.of(route("route", 1.0))), "next", 0);
            assertFalse(runtime.commands.isEmpty());
        }
    }

    @Test
    void reconnectedSourceNeverUsesTheLevelFastPath() throws Exception {
        StreamPlan before = new StreamPlan(stream("app-stream").node(), "source-old", "instance", "music",
                List.of(), List.of("FL", "FR"), "application:app-stream", "in", "out");
        StreamPlan after = new StreamPlan(stream("app-stream").node(), "source-new", "instance", "music",
                List.of(), List.of("FL", "FR"), "application:app-stream", "in", "out");
        Plan previous = plan(0.5, false, List.of(before), List.of(route("route", 1.0)));
        try (Harness runtime = harness(previous)) {
            runtime.reconcilePipeWireGraph(plan(0.25, false, List.of(after), List.of(route("route", 1.0))), "next", 0);
            assertFalse(runtime.commands.isEmpty());
        }
    }

    @Test
    void topologyChangeNeverUsesTheLevelFastPath() throws Exception {
        Plan previous = plan(0.5, false, List.of(), List.of(route("route", 1.0)));
        Plan next = plan(0.25, false, List.of(), List.of(route("route", 1.0), route("second", 1.0)));
        try (Harness runtime = harness(previous)) {
            runtime.reconcilePipeWireGraph(next, "next", 0);
            assertFalse(runtime.commands.isEmpty());
        }
    }

    @Test
    void unhealthyBranchNeverUsesTheLevelFastPath() throws Exception {
        Plan previous = plan(0.5, false, List.of(), List.of(route("route", 1.0)));
        try (Harness runtime = harness(previous)) {
            Map<String, BranchDiagnostic> diagnostics = new LinkedHashMap<>(runtime.branchDiagnostics);
            diagnostics.put("route", new BranchDiagnostic("route", "route", true, true, true,
                    List.of(LINK), List.of(), List.of("route-process:route"), ""));
            runtime.branchDiagnostics = Map.copyOf(diagnostics);
            runtime.reconcilePipeWireGraph(plan(0.25, false, List.of(), List.of(route("route", 1.0))), "next", 0);
            assertFalse(runtime.commands.isEmpty(), "A branch with unobserved desired links must be rebuilt");
        }
    }

    @Test
    void missingOwnedNodeFallsBackToTheFullReconcile() throws Exception {
        Plan previous = plan(0.5, false, List.of(), List.of(route("route", 1.0)));
        try (Harness runtime = harness(previous)) {
            runtime.omitOwnedNode = true;
            runtime.reconcilePipeWireGraph(plan(0.25, false, List.of(), List.of(route("route", 1.0))), "next", 0);
            assertFalse(runtime.commands.isEmpty(), "A vanished owned node must be rebuilt by the full path");
        }
    }
}
