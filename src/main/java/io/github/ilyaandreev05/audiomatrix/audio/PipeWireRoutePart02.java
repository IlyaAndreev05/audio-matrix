package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.Map;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.ApplyReport;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.BranchDiagnostic;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.Plan;

/** Reconciles stable owned route resources without rebuilding unchanged branches. */
abstract class PipeWireRoutePart02 extends PipeWireRoutePart01 {
    @Override
    protected ApplyReport reconcilePipeWireGraph(Plan plan, String signature, int transitionMs) throws Exception {
        desiredLinks.clear();
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
}
