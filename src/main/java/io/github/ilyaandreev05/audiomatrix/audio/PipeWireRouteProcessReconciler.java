package io.github.ilyaandreev05.audiomatrix.audio;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.BusPlan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.ManagedProcess;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.OutputPlan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.Plan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.RoutePlan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.StreamPlan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Keeps only changed owned process instances and reapplies their independent levels. */
final class PipeWireRouteProcessReconciler {
    private PipeWireRouteProcessReconciler() {}

    static Map<String, String> prepare(PipeWireRoutePart02 runtime, Plan plan) throws Exception {
        Map<String, String> errors = new LinkedHashMap<>();
        Plan previous = runtime.appliedPlan;
        Set<String> expectedBuses = new LinkedHashSet<>(plan.buses().keySet());
        Set<String> expectedRoutes = new LinkedHashSet<>();
        Set<String> expectedFilters = new LinkedHashSet<>();
        Set<String> expectedSpecifications = new LinkedHashSet<>();
        Map<String, List<String>> knownInputs = runtime.ports("-i");
        Map<String, List<String>> knownOutputs = runtime.ports("-o");
        for (RoutePlan route : plan.routes()) {
            expectedRoutes.add(route.id());
            expectedSpecifications.add("route-process:" + route.id());
            if (route.channelMatrix() != null) {
                expectedFilters.add("route-matrix:" + route.id());
                expectedSpecifications.add("route-matrix:" + route.id());
            }
            if (!route.filters().isEmpty()) {
                expectedFilters.add("route:" + route.id());
                expectedSpecifications.add("route:" + route.id());
            }
        }
        for (OutputPlan output : plan.outputs().values()) {
            if (!output.filters().isEmpty()) {
                expectedFilters.add("output:" + output.sink().name());
                expectedSpecifications.add("output:" + output.sink().name());
            }
            if (!output.limiterFilters().isEmpty()) {
                expectedFilters.add("output-limiter:" + output.sink().name());
                expectedSpecifications.add("output-limiter:" + output.sink().name());
            }
        }
        for (StreamPlan stream : plan.streams()) if (!stream.filters().isEmpty()) {
            expectedFilters.add(stream.filterProcessKey());
            expectedSpecifications.add(stream.filterProcessKey());
        }
        for (BusPlan bus : plan.buses().values()) {
            String key = "bus:" + bus.id();
            expectedSpecifications.add(key);
            try {
                boolean changed = prepareProcess(runtime, runtime.busProcesses, bus.id(), key, bus.nodeName(),
                        bus.channelLayout() + ":" + bus.positions(), knownInputs, knownOutputs);
                runtime.startBus(bus);
                runtime.processSpecifications.put(key, bus.channelLayout() + ":" + bus.positions());
                BusPlan before = previous == null ? null : previous.buses().get(bus.id());
                if (changed || before == null || before.volume() != bus.volume() || before.muted() != bus.muted()) {
                    int id = runtime.findNodeId(bus.nodeName());
                    WirePlumberMixerControl.setVolume(id, bus.volume());
                    WirePlumberMixerControl.setMuted(id, bus.muted());
                }
            } catch (Exception error) {
                errors.put("bus:" + bus.id(), message(error));
            }
        }

        List<RoutePlan> orderedRoutes = previous == null ? plan.routes()
                : PipeWireRouteStaticSupport03.routeLevelUpdateOrder(previous, plan);
        for (RoutePlan route : orderedRoutes) {
            String owner = "route:" + route.id();
            expectedRoutes.add(route.id());
            try {
                if (errors.containsKey("bus:" + route.fromBusId())
                        || (route.targetBusId() != null && errors.containsKey("bus:" + route.targetBusId())))
                    throw new IllegalStateException("A required source or destination bus is unavailable");
                String routeKey = "route-process:" + route.id();
                boolean changed = prepareProcess(runtime, runtime.routeProcesses, route.id(), routeKey,
                        route.captureNodeName(), route.positions() + ":" + route.delayMs(), knownInputs, knownOutputs);
                runtime.startRoute(route);
                runtime.processSpecifications.put(routeKey, route.positions() + ":" + route.delayMs());
                if (route.channelMatrix() != null) {
                    String matrixKey = "route-matrix:" + route.id();
                    expectedFilters.add(matrixKey);
                    expectedSpecifications.add(matrixKey);
                    changed |= prepareProcess(runtime, runtime.filterChainProcesses, matrixKey, matrixKey,
                            route.matrixInputNodeName(), route.channelMatrix().toString(), knownInputs, knownOutputs);
                    runtime.startChannelMatrix(route);
                    runtime.processSpecifications.put(matrixKey, route.channelMatrix().toString());
                }
                if (!route.filters().isEmpty()) {
                    String filterKey = "route:" + route.id();
                    expectedFilters.add(filterKey);
                    expectedSpecifications.add(filterKey);
                    String filterSpec = route.positions() + ":" + route.filters().hashCode();
                    changed |= prepareProcess(runtime, runtime.filterChainProcesses, filterKey, filterKey,
                            route.filterInputNodeName(), filterSpec, knownInputs, knownOutputs);
                    runtime.startFilterChain(route);
                    runtime.processSpecifications.put(filterKey, filterSpec);
                }
                RoutePlan before = previous == null ? null : previous.routes().stream()
                        .filter(item -> item.id().equals(route.id())).findFirst().orElse(null);
                double level = Math.min(1.0, PipeWireRouteStaticSupport02.effectiveRoutePlaybackVolume(plan, route));
                boolean muted = PipeWireRouteStaticSupport02.effectiveRoutePlaybackMuted(plan, route);
                if (changed || before == null
                        || level != Math.min(1.0, PipeWireRouteStaticSupport02.effectiveRoutePlaybackVolume(previous, before))
                        || muted != PipeWireRouteStaticSupport02.effectiveRoutePlaybackMuted(previous, before)) {
                    int id = runtime.findNodeId(route.playbackNodeName());
                    WirePlumberMixerControl.setVolume(id, level);
                    WirePlumberMixerControl.setMuted(id, muted);
                }
            } catch (Exception error) {
                errors.put(owner, message(error));
            }
        }

        for (OutputPlan output : plan.outputs().values()) {
            String owner = "output:" + output.sink().name();
            try {
                if (!output.filters().isEmpty()) {
                    String key = "output:" + output.sink().name();
                    expectedFilters.add(key);
                    expectedSpecifications.add(key);
                    String spec = output.sink().identity().fingerprint() + ":" + output.positions() + ":" + output.filters().hashCode();
                    prepareProcess(runtime, runtime.filterChainProcesses, key, key, output.filterInputNodeName(),
                            spec, knownInputs, knownOutputs);
                    runtime.startFilterChain(output);
                    runtime.processSpecifications.put(key, spec);
                }
                if (!output.limiterFilters().isEmpty()) {
                    String key = "output-limiter:" + output.sink().name();
                    expectedFilters.add(key);
                    expectedSpecifications.add(key);
                    String spec = output.sink().identity().fingerprint() + ":" + output.positions() + ":" + output.limiterFilters().hashCode();
                    prepareProcess(runtime, runtime.filterChainProcesses, key, key, output.limiterInputNodeName(),
                            spec, knownInputs, knownOutputs);
                    runtime.startOutputLimiter(output);
                    runtime.processSpecifications.put(key, spec);
                }
            } catch (Exception error) {
                errors.put(owner, message(error));
            }
        }

        for (StreamPlan stream : plan.streams()) {
            if (stream.filters().isEmpty()) continue;
            String key = stream.filterProcessKey();
            expectedFilters.add(key);
            expectedSpecifications.add(key);
            try {
                String spec = stream.positions() + ":" + stream.filters().hashCode();
                prepareProcess(runtime, runtime.filterChainProcesses, key, key, stream.filterInputNodeName(),
                        spec, knownInputs, knownOutputs);
                runtime.startFilterChain(stream);
                runtime.processSpecifications.put(key, spec);
            } catch (Exception error) {
                errors.put("stream:" + stream.node().name(), message(error));
            }
        }

        prune(runtime, runtime.busProcesses, expectedBuses, "bus:");
        prune(runtime, runtime.routeProcesses, expectedRoutes, "route-process:");
        prune(runtime, runtime.filterChainProcesses, expectedFilters, "");
        runtime.processSpecifications.keySet().removeIf(key -> !expectedSpecifications.contains(key));
        return errors;
    }

    private static boolean prepareProcess(PipeWireRoutePart02 runtime, Map<String, ManagedProcess> processes,
                                          String processId, String specificationKey, String nodeName,
                                          String specification, Map<String, List<String>> knownInputs,
                                          Map<String, List<String>> knownOutputs) {
        ManagedProcess existing = processes.get(processId);
        boolean nodePresent = knownInputs.containsKey(nodeName) || knownOutputs.containsKey(nodeName);
        boolean changed = existing == null || !existing.process().isAlive() || !nodePresent
                || !specification.equals(runtime.processSpecifications.get(specificationKey));
        if (!changed) return false;
        if (existing != null) {
            processes.remove(processId);
            Map<String, ManagedProcess> removed = new LinkedHashMap<>();
            removed.put(processId, existing);
            runtime.stopProcesses(removed);
        }
        runtime.processSpecifications.remove(specificationKey);
        return true;
    }

    private static void prune(PipeWireRoutePart02 runtime, Map<String, ManagedProcess> processes,
                              Set<String> expected, String specificationPrefix) {
        for (String key : Set.copyOf(processes.keySet())) {
            if (expected.contains(key)) continue;
            ManagedProcess removed = processes.remove(key);
            if (removed != null) {
                Map<String, ManagedProcess> one = new LinkedHashMap<>();
                one.put(key, removed);
                runtime.stopProcesses(one);
            }
            runtime.processSpecifications.remove(specificationPrefix + key);
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
