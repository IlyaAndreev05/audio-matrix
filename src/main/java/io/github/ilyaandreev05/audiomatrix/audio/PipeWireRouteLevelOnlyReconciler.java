package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.BranchDiagnostic;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.BusPlan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.Plan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.RoutePlan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.StreamPlan;

/** Reapplies only changed bus and route levels while the owned routing graph is provably unchanged. */
final class PipeWireRouteLevelOnlyReconciler {
    private PipeWireRouteLevelOnlyReconciler() {}

    static boolean canApply(Plan previous, Plan next, boolean processesAlive,
                            Map<String, BranchDiagnostic> diagnostics) {
        if (previous == null || !processesAlive) return false;
        if (!PipeWireRouteStaticSupport03.sameRoutingTopology(previous, next)) return false;
        if (!sameOwnedStreams(previous, next)) return false;
        if (!diagnostics.keySet().equals(expectedOwnerIds(next))) return false;
        for (BranchDiagnostic diagnostic : diagnostics.values()) {
            if (!diagnostic.error().isEmpty() || !diagnostic.processAlive()
                    || !diagnostic.observedLinks().containsAll(diagnostic.desiredLinks())) return false;
        }
        return true;
    }

    /** Returns false when the update cannot be proven complete, so the caller runs the full reconcile. */
    static boolean apply(PipeWireRoutePart02 runtime, Plan previous, Plan next) {
        try {
            Set<String> names = ownedNodeNames(next);
            if (names.isEmpty()) return true;
            Map<String, Integer> ids = new LinkedHashMap<>();
            for (String name : names) {
                Integer cached = runtime.resolvedNodeIds.get(name);
                if (cached != null) ids.put(name, cached);
            }
            if (!ids.keySet().containsAll(names)) ids = runtime.findNodeIds(names);
            if (!ids.keySet().containsAll(names)) return false;
            for (BusPlan bus : next.buses().values()) {
                BusPlan before = previous.buses().get(bus.id());
                if (before == null) return false;
                if (Double.compare(before.volume(), bus.volume()) != 0)
                    runtime.setNodeVolume(ids.get(bus.nodeName()), bus.volume());
                if (before.muted() != bus.muted())
                    runtime.setNodeMuted(ids.get(bus.nodeName()), bus.muted());
            }
            Map<String, RoutePlan> previousRoutes = new LinkedHashMap<>();
            previous.routes().forEach(route -> previousRoutes.put(route.id(), route));
            for (RoutePlan route : PipeWireRouteStaticSupport03.routeLevelUpdateOrder(previous, next)) {
                RoutePlan before = previousRoutes.get(route.id());
                if (before == null) return false;
                double levelBefore = Math.min(1.0, PipeWireRouteStaticSupport02.effectiveRoutePlaybackVolume(previous, before));
                double levelAfter = Math.min(1.0, PipeWireRouteStaticSupport02.effectiveRoutePlaybackVolume(next, route));
                boolean mutedBefore = PipeWireRouteStaticSupport02.effectiveRoutePlaybackMuted(previous, before);
                boolean mutedAfter = PipeWireRouteStaticSupport02.effectiveRoutePlaybackMuted(next, route);
                int id = ids.get(route.playbackNodeName());
                if (Double.compare(levelBefore, levelAfter) != 0) runtime.setNodeVolume(id, levelAfter);
                if (mutedBefore != mutedAfter) runtime.setNodeMuted(id, mutedAfter);
            }
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception failure) {
            return false;
        }
    }

    private static Set<String> expectedOwnerIds(Plan plan) {
        Set<String> owners = new LinkedHashSet<>();
        plan.buses().keySet().forEach(owners::add);
        plan.routes().forEach(route -> owners.add(route.id()));
        plan.outputs().values().forEach(output -> {
            String fingerprint = output.sink().identity() == null ? "" : output.sink().identity().fingerprint();
            owners.add(fingerprint == null || fingerprint.isBlank() ? output.sink().name() : fingerprint);
        });
        plan.streams().forEach(stream -> owners.add(stream.node().name()));
        return owners;
    }

    private static Set<String> ownedNodeNames(Plan plan) {
        Set<String> names = new LinkedHashSet<>();
        plan.buses().values().forEach(bus -> names.add(bus.nodeName()));
        plan.routes().forEach(route -> names.add(route.playbackNodeName()));
        return names;
    }

    private static boolean sameOwnedStreams(Plan previous, Plan next) {
        if (previous.streams().size() != next.streams().size()) return false;
        Map<String, StreamPlan> planned = new LinkedHashMap<>();
        next.streams().forEach(stream -> planned.put(stream.node().name(), stream));
        if (planned.size() != next.streams().size()) return false;
        for (StreamPlan before : previous.streams()) {
            StreamPlan stream = planned.get(before.node().name());
            if (stream == null || before.node().id() != stream.node().id()
                    || !before.node().applicationId().equals(stream.node().applicationId())
                    || !before.node().processName().equals(stream.node().processName())
                    || before.node().channels() != stream.node().channels()
                    || !before.sourceNodeId().equals(stream.sourceNodeId())
                    || !before.instanceKey().equals(stream.instanceKey())
                    || !before.busId().equals(stream.busId())
                    || !before.filters().equals(stream.filters())
                    || !before.positions().equals(stream.positions())
                    || !before.filterProcessKey().equals(stream.filterProcessKey())
                    || !before.filterInputNodeName().equals(stream.filterInputNodeName())
                    || !before.filterOutputNodeName().equals(stream.filterOutputNodeName())) return false;
        }
        return true;
    }
}
