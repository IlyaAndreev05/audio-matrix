package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.ApplyReport;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.BranchDiagnostic;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.ManagedProcess;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.Plan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.RoutePlan;

/** Reconciles stable owned route resources without rebuilding unchanged branches. */
abstract class PipeWireRoutePart02 extends PipeWireRoutePart01 {
    private static final long ORPHAN_EXIT_WAIT_MS = 1500;
    private volatile boolean orphansReaped;

    @Override
    protected ApplyReport reconcilePipeWireGraph(Plan plan, String signature, int transitionMs) throws Exception {
        desiredLinks.clear();
        Plan previous = appliedPlan;
        if (active && PipeWireRouteLevelOnlyReconciler.canApply(previous, plan, processesAlive(), branchDiagnostics)
                && PipeWireRouteLevelOnlyReconciler.apply(this, previous, plan)) {
            appliedPlan = plan;
            appliedSignature = signature;
            activeTransitionMs = transitionMs;
            skippedStreamCount = 0;
            return reportAppliedPlan(plan);
        }
        reapOrphans();
        Map<String, String> processErrors = PipeWireRouteProcessReconciler.prepare(this, plan);
        Map<String, BranchDiagnostic> branches = PipeWireRouteBranchLinks.reconcile(this, plan, processErrors);
        branchDiagnostics = Map.copyOf(branches);
        skippedStreamCount = (int) branches.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("stream:") && !entry.getValue().error().isEmpty())
                .count();
        active = !busProcesses.isEmpty() || !routeProcesses.isEmpty() || !filterChainProcesses.isEmpty()
                || !ownedLinks.isEmpty();
        appliedPlan = plan;
        appliedSignature = signature;
        activeTransitionMs = transitionMs;
        return reportAppliedPlan(plan);
    }

    /**
     * Writes the shared master level straight into the playback nodes of the routes that are already
     * running, so the master slider never waits for a plan rebuild. The plan keeps the master: a later
     * full apply recomputes every level from the configuration by itself.
     */
    public CompletableFuture<Void> applyMasterLevel(double masterVolume, boolean masterMuted) {
        if (closed) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            Plan plan = appliedPlan;
            if (plan == null || !processesAlive()) return;
            double master = Math.max(0, Math.min(1, masterVolume));
            for (RoutePlan route : plan.routes()) {
                try {
                    double base = MixLevelMath.effectiveRouteVolume(route.volume(), route.gainDb());
                    double level = route.sink() == null ? base : Math.min(1.0, base * master);
                    boolean muted = route.muted() || (route.sink() != null && masterMuted);
                    int nodeId = findNodeId(route.playbackNodeName());
                    setNodeVolume(nodeId, level);
                    setNodeMuted(nodeId, muted);
                } catch (Exception ignored) {
                    // A node that vanished must not cancel the remaining routes.
                }
            }
        }, worker);
    }

    /**
     * Kills the helper processes an earlier manager lifetime leaked, once per manager lifetime.
     *
     * <p>Only commands this manager owns are considered: filter chains and channel matrices run from
     * temporary {@code /tmp/audio-matrix-} configurations, while the standalone {@code audiomatrix_system}
     * and {@code audiomatrix_events} loopbacks never carry that path. A process whose parent runtime is
     * still alive stays untouched, so a second manager in the same session (tests, diagnostics) cannot
     * kill the graph of the running application.</p>
     */
    private void reapOrphans() {
        if (orphansReaped) return;
        orphansReaped = true;
        try {
            Set<Long> protectedPids = protectedProcessIds();
            List<ProcessHandle> candidates = ProcessHandle.allProcesses()
                    .filter(handle -> !protectedPids.contains(handle.pid()))
                    .filter(PipeWireRoutePart02::isReapableOwnedProcess)
                    .toList();
            for (ProcessHandle handle : candidates) handle.destroy();
            awaitOrphanExit(candidates);
        } catch (Exception ignored) {
            // Reaping is best effort; it must never fail or block a route apply.
        }
    }

    private Set<Long> protectedProcessIds() {
        Set<Long> pids = new LinkedHashSet<>();
        pids.add(ProcessHandle.current().pid());
        addProcessIds(busProcesses, pids);
        addProcessIds(routeProcesses, pids);
        addProcessIds(filterChainProcesses, pids);
        return pids;
    }

    private static void addProcessIds(Map<String, ManagedProcess> managed, Set<Long> pids) {
        for (ManagedProcess process : managed.values()) {
            if (process != null && process.process() != null) pids.add(process.process().pid());
        }
    }

    private static boolean isReapableOwnedProcess(ProcessHandle handle) {
        try {
            if (!isOrphanedOwnedProcess(handle.info().commandLine().orElse(""))) return false;
            return handle.parent().filter(ProcessHandle::isAlive)
                    .map(parent -> parent.info().commandLine().orElse(""))
                    .map(PipeWireRoutePart02::isLiveOwnerRuntime)
                    .map(owner -> !owner)
                    .orElse(true);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isLiveOwnerRuntime(String commandLine) {
        String normalized = commandLine.toLowerCase(Locale.ROOT);
        return normalized.contains("audiomatrix") || normalized.contains("audio-matrix");
    }

    private static void awaitOrphanExit(List<ProcessHandle> handles) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ORPHAN_EXIT_WAIT_MS);
        for (ProcessHandle handle : handles) {
            while (handle.isAlive() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (handle.isAlive()) handle.destroyForcibly();
        }
    }

    /** True for the helper command line an Audio Matrix runtime owns through its temporary configuration. */
    static boolean isOrphanedOwnedProcess(String commandLine) {
        if (commandLine == null || !commandLine.contains("/tmp/audio-matrix-")) return false;
        return commandLine.contains("pw-loopback") || commandLine.contains("pipewire -c");
    }
}
