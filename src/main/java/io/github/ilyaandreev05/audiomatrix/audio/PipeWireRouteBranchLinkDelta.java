package io.github.ilyaandreev05.audiomatrix.audio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.BranchDiagnostic;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.Link;

/** Applies one owner's link delta and rolls back only that owner if its links fail. */
final class PipeWireRouteBranchLinkDelta {
    /** Stable identity of one independently reconciled resource branch. */
    record BranchOwner(String key, String ownerId, String kind, boolean selected, boolean available) {}

    private PipeWireRouteBranchLinkDelta() {}

    static BranchDiagnostic reconcile(PipeWireRoutePart02 runtime, BranchOwner owner, List<Link> requested,
                                      List<String> desiredProcesses, List<String> observedProcesses,
                                      String branchError) {
        Set<Link> previous = new LinkedHashSet<>(runtime.branchOwnedLinks.getOrDefault(owner.key(), Set.of()));
        Set<Link> desired = new LinkedHashSet<>(requested);
        if (branchError != null)
            return failed(runtime, owner, previous, desiredProcesses, observedProcesses, branchError);
        try {
            Set<Link> removed = new LinkedHashSet<>(previous);
            removed.removeAll(desired);
            runtime.disconnectAll(new ArrayList<>(removed));
            runtime.refreshObservedLinks();
            if (runtime.observedLinks.stream().anyMatch(removed::contains))
                throw new IOException("Previous branch links could not be removed");
            if (!desired.isEmpty()) runtime.connectAll(new ArrayList<>(desired));
            runtime.refreshObservedLinks();
            Set<Link> missing = new LinkedHashSet<>(desired);
            missing.removeAll(runtime.observedLinks);
            if (!missing.isEmpty()) throw new IOException("PipeWire did not confirm branch links: " + missing);
            runtime.branchOwnedLinks.put(owner.key(), desired);
            runtime.desiredLinks.addAll(desired);
            return diagnostic(owner, desired, runtime.observedLinks, desiredProcesses, observedProcesses, "");
        } catch (Exception error) {
            Set<Link> attempted = new LinkedHashSet<>(desired);
            attempted.removeAll(previous);
            runtime.disconnectAll(new ArrayList<>(attempted));
            try {
                runtime.refreshObservedLinks();
                Set<Link> missingPrevious = new LinkedHashSet<>(previous);
                missingPrevious.removeAll(runtime.observedLinks);
                if (!missingPrevious.isEmpty()) runtime.connectAll(new ArrayList<>(missingPrevious));
                runtime.refreshObservedLinks();
            } catch (Exception rollbackError) {
                error.addSuppressed(rollbackError);
            }
            return failed(runtime, owner, previous, desiredProcesses, observedProcesses, message(error));
        }
    }

    private static BranchDiagnostic failed(PipeWireRoutePart02 runtime, BranchOwner owner, Set<Link> previous,
                                           List<String> desiredProcesses, List<String> observedProcesses,
                                           String error) {
        runtime.branchOwnedLinks.put(owner.key(), previous);
        runtime.desiredLinks.addAll(previous);
        runtime.transitionWarnings.add(owner.key() + " reconciliation failed: " + error);
        return diagnostic(owner, previous, runtime.observedLinks, desiredProcesses, observedProcesses, error);
    }

    private static BranchDiagnostic diagnostic(BranchOwner owner, Set<Link> desired, Set<Link> observed,
                                               List<String> desiredProcesses, List<String> observedProcesses,
                                               String error) {
        List<Link> present = desired.stream().filter(observed::contains).toList();
        boolean processAlive = desiredProcesses.isEmpty() || observedProcesses.size() == desiredProcesses.size();
        return new BranchDiagnostic(owner.ownerId(), owner.kind(), owner.selected(), owner.available(), processAlive,
                new ArrayList<>(desired), present, desiredProcesses, error);
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
