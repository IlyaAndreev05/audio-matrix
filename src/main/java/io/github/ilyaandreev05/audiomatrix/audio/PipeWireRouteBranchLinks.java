package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteBranchLinkDelta.BranchOwner;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.BranchDiagnostic;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.BusPlan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.Link;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.ManagedProcess;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.OutputPlan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.Plan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.RoutePlan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.StreamPlan;

/** Builds and applies links independently for each stable route, output, bus and stream owner. */
final class PipeWireRouteBranchLinks {
    private PipeWireRouteBranchLinks() {}

    static Map<String, BranchDiagnostic> reconcile(PipeWireRoutePart02 runtime, Plan plan,
                                                    Map<String, String> errors) throws Exception {
        String listing = runtime.run(List.of("pw-link", "-l"), PipeWireRouteState.COMMAND_TIMEOUT);
        runtime.observedLinks.clear();
        runtime.observedLinks.addAll(PipeWireLinkOperations.allLinks(listing));
        Map<String, List<String>> inputs = runtime.ports("-i");
        Map<String, List<String>> outputs = runtime.ports("-o");
        Map<String, BranchDiagnostic> result = new LinkedHashMap<>();
        Set<String> activeOwners = new LinkedHashSet<>();

        for (BusPlan bus : plan.buses().values()) {
            String owner = "bus:" + bus.id();
            activeOwners.add(owner);
            List<Link> desired = List.of();
            String error = errors.get(owner);
            if (error == null) try { desired = PipeWireRouteLinkBuilder.bus(bus, inputs, outputs); }
            catch (Exception failure) { error = message(failure); }
            BranchOwner identity = new BranchOwner(owner, bus.id(), "bus", true, true);
            result.put(owner, applyBranch(runtime, identity, desired, processKeys(owner, plan), error));
        }
        for (RoutePlan route : plan.routes()) {
            String owner = "route:" + route.id();
            activeOwners.add(owner);
            List<Link> desired = List.of();
            String error = errors.get(owner);
            if (error == null && errors.containsKey("bus:" + route.fromBusId())) error = "A required source bus is unavailable";
            if (error == null && route.targetBusId() != null && errors.containsKey("bus:" + route.targetBusId()))
                error = "A required destination bus is unavailable";
            if (error == null) try { desired = PipeWireRouteLinkBuilder.route(route, plan, inputs, outputs); }
            catch (Exception failure) { error = message(failure); }
            boolean available = route.sink() != null || route.targetBusId() != null;
            BranchOwner identity = new BranchOwner(owner, route.id(), "route", true, available);
            result.put(owner, applyBranch(runtime, identity, desired, processKeys(owner, plan), error));
        }
        for (OutputPlan output : plan.outputs().values()) {
            String owner = "output:" + output.sink().name();
            activeOwners.add(owner);
            List<Link> desired = List.of();
            String error = errors.get(owner);
            if (error == null) try { desired = PipeWireRouteLinkBuilder.output(output, inputs, outputs); }
            catch (Exception failure) { error = message(failure); }
            String fingerprint = output.sink().identity().fingerprint();
            BranchOwner identity = new BranchOwner(owner,
                    fingerprint == null || fingerprint.isBlank() ? output.sink().name() : fingerprint,
                    "output", true, true);
            result.put(owner, applyBranch(runtime, identity, desired, processKeys(owner, plan), error));
        }

        Set<String> activeStreams = new LinkedHashSet<>();
        for (StreamPlan stream : plan.streams()) {
            String name = stream.node().name(), owner = "stream:" + stream.node().name();
            activeOwners.add(owner);
            activeStreams.add(name);
            List<Link> desired = List.of();
            String error = errors.get(owner);
            if (error == null) try { desired = PipeWireRouteLinkBuilder.stream(stream, plan, inputs, outputs); }
            catch (Exception failure) { error = message(failure); }
            boolean previouslyLinked = !runtime.branchOwnedLinks.getOrDefault(owner, Set.of()).isEmpty();
            if (error == null && !previouslyLinked) {
                try { error = disconnectOriginal(runtime, listing, name); }
                catch (Exception failure) { error = message(failure); }
            }
            BranchOwner identity = new BranchOwner(owner, name, "stream", true, true);
            BranchDiagnostic diagnostic = applyBranch(runtime, identity, desired, processKeys(owner, plan), error);
            result.put(owner, diagnostic);
            if (!diagnostic.error().isEmpty() && !previouslyLinked) runtime.restoreOriginal(name);
        }

        cleanupRemovedOwners(runtime, activeOwners, activeStreams, result);
        runtime.refreshObservedLinks();
        Map<String, BranchDiagnostic> byOwnerId = new LinkedHashMap<>();
        for (BranchDiagnostic diagnostic : result.values())
            byOwnerId.putIfAbsent(diagnostic.ownerId(), refreshDiagnostic(runtime, diagnostic));
        return Map.copyOf(byOwnerId);
    }

    private static String disconnectOriginal(PipeWireRoutePart02 runtime, String listing, String nodeName) throws Exception {
        runtime.originalStreamLinks.computeIfAbsent(nodeName,
                ignored -> PipeWireLinkOperations.linksFromNode(listing, nodeName));
        List<Link> original = runtime.originalStreamLinks.getOrDefault(nodeName, List.of());
        runtime.disconnectAll(original);
        runtime.refreshObservedLinks();
        return original.stream().anyMatch(runtime.observedLinks::contains)
                ? "Original stream links could not be disconnected" : null;
    }

    private static BranchDiagnostic applyBranch(PipeWireRoutePart02 runtime, BranchOwner identity, List<Link> desired,
                                                 List<String> processKeys, String error) {
        List<String> observed = processKeys.stream()
                .filter(key -> processAlive(runtime, identity.key(), key)).toList();
        return PipeWireRouteBranchLinkDelta.reconcile(runtime, identity, desired, processKeys, observed, error);
    }

    private static List<String> processKeys(String owner, Plan plan) {
        if (owner.startsWith("bus:")) return List.of(owner);
        if (owner.startsWith("route:")) {
            String id = owner.substring("route:".length());
            RoutePlan route = plan.routes().stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
            if (route == null) return List.of();
            List<String> keys = new ArrayList<>(List.of("route-process:" + id));
            if (route.channelMatrix() != null) keys.add("route-matrix:" + id);
            if (!route.filters().isEmpty()) keys.add("route:" + id);
            return List.copyOf(keys);
        }
        if (owner.startsWith("output:")) {
            String name = owner.substring("output:".length());
            OutputPlan output = plan.outputs().get(name);
            if (output == null) return List.of();
            List<String> keys = new ArrayList<>();
            if (!output.filters().isEmpty()) keys.add("output:" + name);
            if (!output.limiterFilters().isEmpty()) keys.add("output-limiter:" + name);
            return List.copyOf(keys);
        }
        String name = owner.substring("stream:".length());
        return plan.streams().stream().filter(stream -> stream.node().name().equals(name) && !stream.filters().isEmpty())
                .map(StreamPlan::filterProcessKey).toList();
    }

    private static boolean processAlive(PipeWireRoutePart02 runtime, String owner, String key) {
        if (owner.startsWith("bus:")) {
            ManagedProcess process = runtime.busProcesses.get(owner.substring("bus:".length()));
            return process != null && process.process().isAlive();
        }
        if (owner.startsWith("route:") && key.startsWith("route-process:")) {
            ManagedProcess process = runtime.routeProcesses.get(key.substring("route-process:".length()));
            return process != null && process.process().isAlive();
        }
        ManagedProcess process = runtime.filterChainProcesses.get(key);
        return process != null && process.process().isAlive();
    }

    private static void cleanupRemovedOwners(PipeWireRoutePart02 runtime, Set<String> activeOwners,
                                             Set<String> activeStreams, Map<String, BranchDiagnostic> diagnostics) {
        for (String owner : Set.copyOf(runtime.branchOwnedLinks.keySet())) {
            if (activeOwners.contains(owner)) continue;
            Set<Link> previous = runtime.branchOwnedLinks.getOrDefault(owner, Set.of());
            try {
                runtime.disconnectAll(new ArrayList<>(previous));
                runtime.refreshObservedLinks();
                Set<Link> remaining = new LinkedHashSet<>(previous);
                remaining.retainAll(runtime.observedLinks);
                if (remaining.isEmpty()) {
                    runtime.branchOwnedLinks.remove(owner);
                    diagnostics.remove(owner);
                } else {
                    runtime.branchOwnedLinks.put(owner, remaining);
                    BranchOwner identity = ownerIdentity(owner);
                    diagnostics.put(owner, new BranchDiagnostic(identity.ownerId(), identity.kind(), false,
                            false, false, new ArrayList<>(previous), new ArrayList<>(remaining), List.of(),
                            "Removed branch links remain observed"));
                }
                if (owner.startsWith("stream:") && remaining.isEmpty()) {
                    String nodeName = owner.substring("stream:".length());
                    runtime.restoreOriginal(nodeName);
                    runtime.originalStreamLinks.remove(nodeName);
                }
            } catch (Exception failure) {
                List<Link> observed = previous.stream().filter(runtime.observedLinks::contains).toList();
                BranchOwner identity = ownerIdentity(owner);
                diagnostics.put(owner, new BranchDiagnostic(identity.ownerId(), identity.kind(), false, false,
                        false, new ArrayList<>(previous), observed, List.of(), message(failure)));
                runtime.transitionWarnings.add(owner + " cleanup failed: " + message(failure));
            }
        }
        for (String nodeName : Set.copyOf(runtime.originalStreamLinks.keySet())) {
            if (activeStreams.contains(nodeName)
                    || !runtime.branchOwnedLinks.getOrDefault("stream:" + nodeName, Set.of()).isEmpty()) continue;
            runtime.restoreOriginal(nodeName);
            runtime.originalStreamLinks.remove(nodeName);
        }
    }

    private static BranchOwner ownerIdentity(String internalKey) {
        int separator = internalKey.indexOf(':');
        String kind = separator < 0 ? "branch" : internalKey.substring(0, separator);
        String ownerId = separator < 0 ? internalKey : internalKey.substring(separator + 1);
        return new BranchOwner(internalKey, ownerId, kind, false, false);
    }

    private static BranchDiagnostic refreshDiagnostic(PipeWireRoutePart02 runtime, BranchDiagnostic diagnostic) {
        List<Link> observed = diagnostic.desiredLinks().stream().filter(runtime.observedLinks::contains).toList();
        boolean processAlive = diagnostic.desiredProcesses().isEmpty()
                || diagnostic.desiredProcesses().stream().allMatch(key -> ownedProcessAlive(runtime, key));
        return new BranchDiagnostic(diagnostic.ownerId(), diagnostic.kind(), diagnostic.selected(),
                diagnostic.available(), processAlive, diagnostic.desiredLinks(), observed,
                diagnostic.desiredProcesses(), diagnostic.error());
    }

    private static boolean ownedProcessAlive(PipeWireRoutePart02 runtime, String key) {
        if (key.startsWith("route-process:")) {
            ManagedProcess process = runtime.routeProcesses.get(key.substring("route-process:".length()));
            return process != null && process.process().isAlive();
        }
        if (key.startsWith("bus:")) {
            ManagedProcess process = runtime.busProcesses.get(key.substring("bus:".length()));
            return process != null && process.process().isAlive();
        }
        ManagedProcess process = runtime.filterChainProcesses.get(key);
        return process != null && process.process().isAlive();
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
