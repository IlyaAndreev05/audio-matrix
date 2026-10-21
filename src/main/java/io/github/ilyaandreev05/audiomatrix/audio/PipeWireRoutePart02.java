package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Applies explicitly requested Audio Matrix routing by owning PipeWire loopback nodes and links. */

abstract class PipeWireRoutePart02 extends PipeWireRoutePart01 {
    protected ApplyReport reconcilePipeWireGraph(Plan plan, String signature, int transitionMs) throws Exception {
        Plan previous = appliedPlan;
        transitionWarnings.clear();
        branchErrors.clear();
        skippedStreamCount = 0;
        try {
            prepareChangedProcesses(previous, plan);
            stopDeadProcesses(busProcesses);
            stopDeadProcesses(routeProcesses);
            stopDeadProcesses(filterChainProcesses);

            String linkListing;
            try {
                linkListing = run(List.of("pw-link", "-l"), COMMAND_TIMEOUT);
            } catch (Exception error) {
                linkListing = "";
                transitionWarnings.add("Could not snapshot stream links: " + message(error));
            }
            for (StreamPlan stream : plan.streams()) {
                originalStreamLinks.computeIfAbsent(stream.node().name(),
                        ignored -> linksFromNode(linkListing, stream.node().name()));
            }

            Set<String> failedBuses = new HashSet<>();
            for (BusPlan bus : plan.buses().values()) {
                try {
                    startBus(bus);
                } catch (Exception error) {
                    failedBuses.add(bus.id());
                    stopProcess(busProcesses, bus.id());
                    branchErrors.put(busOwner(bus.id()), message(error));
                    transitionWarnings.add("Bus branch " + bus.id() + " failed: " + message(error));
                }
            }

            Set<String> failedOutputs = new HashSet<>();
            for (OutputPlan output : plan.outputs().values()) {
                String name = output.sink().name();
                try {
                    if (!output.filters().isEmpty()) startFilterChain(output);
                    if (!output.limiterFilters().isEmpty()) startOutputLimiter(output);
                } catch (Exception error) {
                    failedOutputs.add(name);
                    stopProcess(filterChainProcesses, "output:" + name);
                    stopProcess(filterChainProcesses, "output-limiter:" + name);
                    branchErrors.put(outputOwner(name), message(error));
                    transitionWarnings.add("Output branch " + name + " failed: " + message(error));
                }
            }

            Set<String> failedRoutes = new HashSet<>();
            for (RoutePlan route : plan.routes()) {
                try {
                    startRoute(route);
                    if (route.channelMatrix() != null) startChannelMatrix(route);
                    if (!route.filters().isEmpty()) startFilterChain(route);
                } catch (Exception error) {
                    failedRoutes.add(route.id());
                    discardRouteProcesses(route.id());
                    branchErrors.put(routeOwner(route.id()), message(error));
                    transitionWarnings.add("Route branch " + route.id() + " failed: " + message(error));
                }
            }

            Set<String> failedStreams = new HashSet<>();
            for (StreamPlan stream : plan.streams()) {
                if (stream.filters().isEmpty()) continue;
                try {
                    startFilterChain(stream);
                } catch (Exception error) {
                    failedStreams.add(stream.node().name());
                    stopProcess(filterChainProcesses, stream.filterProcessKey());
                    branchErrors.put(streamOwner(stream), message(error));
                    transitionWarnings.add("Stream branch " + stream.node().name() + " failed: " + message(error));
                }
            }

            updateLiveLevels(previous, plan);
            Map<String, List<String>> inputs = ports("-i");
            Map<String, List<String>> outputs = ports("-o");
            refreshObservedLinks();

            Set<String> desiredOwners = new LinkedHashSet<>();
            Map<String, List<Link>> candidateLinks = new LinkedHashMap<>();
            Set<String> failedOwners = new HashSet<>();
            BusPlan mainBus = plan.buses().get(GraphCommonVibro.MAIN_SOURCE_ID);
            if (mainBus != null) {
                String owner = busOwner(mainBus.id());
                desiredOwners.add(owner);
                List<Link> links = channelLinks(
                        outputs.getOrDefault(PipeWireBackend.SYSTEM_MIXER_SINK_NAME, List.of()),
                        inputs.getOrDefault(mainBus.nodeName(), List.of()), mainBus.channelLayout(), false);
                if (links.isEmpty()) {
                    failedOwners.add(owner);
                    branchErrors.put(owner, "No compatible ports from the virtual system input");
                    transitionWarnings.add("Bus branch " + mainBus.id()
                            + " has no compatible ports from the virtual system input");
                } else candidateLinks.put(owner, links);
            }

            for (RoutePlan route : plan.routes()) {
                String owner = routeOwner(route.id());
                desiredOwners.add(owner);
                if (failedRoutes.contains(route.id()) || failedBuses.contains(route.fromBusId())
                        || (route.targetBusId() != null && failedBuses.contains(route.targetBusId()))
                        || (route.sink() != null && failedOutputs.contains(route.sink().name()))) {
                    failedOwners.add(owner);
                    branchErrors.put(owner, "Required bus, output, or route process is unavailable");
                    continue;
                }
                try {
                    candidateLinks.put(owner, routeLinks(route, plan, inputs, outputs));
                } catch (Exception error) {
                    failedOwners.add(owner);
                    branchErrors.put(owner, message(error));
                    transitionWarnings.add("Route branch " + route.id() + " link reconciliation failed: " + message(error));
                }
            }

            for (OutputPlan output : plan.outputs().values()) {
                if (output.filters().isEmpty() && output.limiterFilters().isEmpty()) continue;
                String owner = outputOwner(output.sink().name());
                desiredOwners.add(owner);
                if (failedOutputs.contains(output.sink().name())) {
                    failedOwners.add(owner);
                    continue;
                }
                try {
                    candidateLinks.put(owner, outputLinks(output, inputs, outputs));
                } catch (Exception error) {
                    failedOwners.add(owner);
                    branchErrors.put(owner, message(error));
                    transitionWarnings.add("Output branch " + output.sink().name()
                            + " link reconciliation failed: " + message(error));
                }
            }

            Map<String, String> streamNamesByOwner = new LinkedHashMap<>();
            for (StreamPlan stream : plan.streams()) {
                String owner = streamOwner(stream);
                desiredOwners.add(owner);
                streamNamesByOwner.put(owner, stream.node().name());
                if (failedStreams.contains(stream.node().name()) || failedBuses.contains(stream.busId())) {
                    failedOwners.add(owner);
                    skippedStreamCount++;
                    continue;
                }
                try {
                    candidateLinks.put(owner, streamLinks(stream, plan, inputs, outputs));
                } catch (Exception error) {
                    failedOwners.add(owner);
                    branchErrors.put(owner, message(error));
                    skippedStreamCount++;
                    transitionWarnings.add("Stream branch " + stream.node().name()
                            + " link reconciliation failed: " + message(error));
                }
            }

            for (String owner : new ArrayList<>(linkOwners.keySet()))
                if (!desiredOwners.contains(owner)) linkOwners.remove(owner);

            for (Map.Entry<String, List<Link>> candidate : candidateLinks.entrySet()) {
                String owner = candidate.getKey();
                if (failedOwners.contains(owner)) continue;
                String streamName = streamNamesByOwner.get(owner);
                boolean isNewStream = streamName != null && !linkOwners.containsKey(owner);
                if (isNewStream) {
                    List<Link> original = originalStreamLinks.getOrDefault(streamName, List.of());
                    disconnectAll(original);
                    refreshObservedLinks();
                    if (original.stream().anyMatch(observedLinks::contains)) {
                        failedOwners.add(owner);
                        skippedStreamCount++;
                        branchErrors.put(owner, "Could not detach the stream's previous PipeWire destinations");
                        transitionWarnings.add("Stream branch " + streamName
                                + " could not detach its previous PipeWire destinations");
                        continue;
                    }
                }
                if (!connectOwnerLinks(owner, candidate.getValue())) {
                    if (isNewStream) restoreOriginal(streamName);
                    if (streamName != null) skippedStreamCount++;
                }
            }

            Set<Link> expectedLinks = allOwnerLinks();
            Set<Link> obsoleteLinks = new LinkedHashSet<>(ownedLinks);
            obsoleteLinks.removeAll(expectedLinks);
            disconnectAll(new ArrayList<>(obsoleteLinks));
            resetDesiredLinks();

            Set<String> activeStreamNames = new HashSet<>();
            plan.streams().forEach(stream -> activeStreamNames.add(stream.node().name()));
            for (String name : new ArrayList<>(originalStreamLinks.keySet())) {
                if (activeStreamNames.contains(name)) continue;
                restoreOriginal(name);
                originalStreamLinks.remove(name);
            }

            cleanupObsoleteProcesses(plan);
            refreshObservedLinks();
            active = hasObservedRoute(plan);
            appliedSignature = signature;
            appliedPlan = plan;
            activeTransitionMs = transitionMs;
            return reportAppliedPlan(plan);
        } catch (Exception error) {
            status = "Route reconciliation failed; existing branches were retained: " + message(error);
            throw error;
        }
    }

    private void prepareChangedProcesses(Plan previous, Plan next) {
        if (previous == null) return;
        for (BusPlan before : previous.buses().values()) {
            BusPlan after = next.buses().get(before.id());
            if (after != null && !sameBusProcess(before, after)) stopProcess(busProcesses, before.id());
        }
        Map<String, RoutePlan> nextRoutes = new LinkedHashMap<>();
        next.routes().forEach(route -> nextRoutes.put(route.id(), route));
        for (RoutePlan before : previous.routes()) {
            RoutePlan after = nextRoutes.get(before.id());
            if (after != null && !sameRouteProcess(before, after)) discardRouteProcesses(before.id());
        }
        Map<String, OutputPlan> nextOutputs = next.outputs();
        for (OutputPlan before : previous.outputs().values()) {
            OutputPlan after = nextOutputs.get(before.sink().name());
            if (after != null && !sameOutputProcess(before, after)) discardOutputProcesses(before.sink().name());
        }
        Map<String, StreamPlan> nextStreams = new LinkedHashMap<>();
        next.streams().forEach(stream -> nextStreams.put(stream.node().name(), stream));
        for (StreamPlan before : previous.streams()) {
            StreamPlan after = nextStreams.get(before.node().name());
            if (after != null && !sameStreamProcess(before, after))
                stopProcess(filterChainProcesses, before.filterProcessKey());
        }
    }

    protected static boolean sameBusProcess(BusPlan left, BusPlan right) {
        return left.nodeName().equals(right.nodeName()) && left.channelLayout().equals(right.channelLayout())
                && left.positions().equals(right.positions());
    }

    protected static boolean sameRouteProcess(RoutePlan left, RoutePlan right) {
        return left.fromBusId().equals(right.fromBusId())
                && java.util.Objects.equals(left.targetBusId(), right.targetBusId())
                && sameSink(left.sink(), right.sink())
                && left.channelMap().equals(right.channelMap()) && left.positions().equals(right.positions())
                && java.util.Objects.equals(left.channelMatrix(), right.channelMatrix())
                && Double.compare(left.delayMs(), right.delayMs()) == 0
                && left.filters().equals(right.filters())
                && left.captureNodeName().equals(right.captureNodeName())
                && left.playbackNodeName().equals(right.playbackNodeName())
                && left.filterInputNodeName().equals(right.filterInputNodeName())
                && left.filterOutputNodeName().equals(right.filterOutputNodeName())
                && left.matrixInputNodeName().equals(right.matrixInputNodeName())
                && left.matrixOutputNodeName().equals(right.matrixOutputNodeName());
    }

    private static boolean sameOutputProcess(OutputPlan left, OutputPlan right) {
        return sameSink(left.sink(), right.sink()) && left.positions().equals(right.positions())
                && left.filters().equals(right.filters()) && left.limiterFilters().equals(right.limiterFilters())
                && left.filterInputNodeName().equals(right.filterInputNodeName())
                && left.filterOutputNodeName().equals(right.filterOutputNodeName())
                && left.limiterInputNodeName().equals(right.limiterInputNodeName())
                && left.limiterOutputNodeName().equals(right.limiterOutputNodeName());
    }

    private static boolean sameStreamProcess(StreamPlan left, StreamPlan right) {
        return left.node().name().equals(right.node().name()) && left.busId().equals(right.busId())
                && left.filters().equals(right.filters()) && left.positions().equals(right.positions())
                && left.filterProcessKey().equals(right.filterProcessKey())
                && left.filterInputNodeName().equals(right.filterInputNodeName())
                && left.filterOutputNodeName().equals(right.filterOutputNodeName());
    }

    private void stopDeadProcesses(Map<String, ManagedProcess> processes) {
        for (Map.Entry<String, ManagedProcess> entry : new ArrayList<>(processes.entrySet()))
            if (!entry.getValue().process().isAlive()) stopProcess(processes, entry.getKey());
    }

    private void discardRouteProcesses(String routeId) {
        stopProcess(routeProcesses, routeId);
        stopProcess(filterChainProcesses, "route:" + routeId);
        stopProcess(filterChainProcesses, "route-matrix:" + routeId);
    }

    private void discardOutputProcesses(String sinkName) {
        stopProcess(filterChainProcesses, "output:" + sinkName);
        stopProcess(filterChainProcesses, "output-limiter:" + sinkName);
    }

    private void cleanupObsoleteProcesses(Plan plan) {
        Set<String> busIds = new HashSet<>(plan.buses().keySet());
        for (String id : new ArrayList<>(busProcesses.keySet()))
            if (!busIds.contains(id)) stopProcess(busProcesses, id);

        Set<String> routeIds = new HashSet<>();
        Set<String> filterKeys = new HashSet<>();
        for (RoutePlan route : plan.routes()) {
            routeIds.add(route.id());
            if (!route.filters().isEmpty()) filterKeys.add("route:" + route.id());
            if (route.channelMatrix() != null) filterKeys.add("route-matrix:" + route.id());
        }
        for (OutputPlan output : plan.outputs().values()) {
            if (!output.filters().isEmpty()) filterKeys.add("output:" + output.sink().name());
            if (!output.limiterFilters().isEmpty()) filterKeys.add("output-limiter:" + output.sink().name());
        }
        for (StreamPlan stream : plan.streams())
            if (!stream.filters().isEmpty()) filterKeys.add(stream.filterProcessKey());
        for (String id : new ArrayList<>(routeProcesses.keySet()))
            if (!routeIds.contains(id)) stopProcess(routeProcesses, id);
        for (String key : new ArrayList<>(filterChainProcesses.keySet()))
            if (!filterKeys.contains(key)) stopProcess(filterChainProcesses, key);
    }

    private List<Link> routeLinks(RoutePlan route, Plan plan, Map<String, List<String>> inputs,
                                  Map<String, List<String>> outputs) throws IOException {
        BusPlan sourceBus = plan.buses().get(route.fromBusId());
        if (sourceBus == null) throw new IOException("Missing source bus " + route.fromBusId());
        String sourceTarget = route.filters().isEmpty() ? route.captureNodeName() : route.filterInputNodeName();
        List<Link> links = new ArrayList<>();
        if (route.channelMatrix() != null) {
            List<Link> intoMatrix = positionLinks(outputs.getOrDefault(sourceBus.nodeName(), List.of()),
                    inputs.getOrDefault(route.matrixInputNodeName(), List.of()));
            if (intoMatrix.size() != route.channelMatrix().inputs().size())
                throw new IOException("Channel matrix input ports are incomplete");
            links.addAll(intoMatrix);
            List<Link> fromMatrix = positionLinks(outputs.getOrDefault(route.matrixOutputNodeName(), List.of()),
                    inputs.getOrDefault(sourceTarget, List.of()));
            if (fromMatrix.size() != route.positions().size())
                throw new IOException("Channel matrix output ports are incomplete");
            links.addAll(fromMatrix);
        } else {
            links.addAll(channelLinks(outputs.getOrDefault(sourceBus.nodeName(), List.of()),
                    inputs.getOrDefault(sourceTarget, List.of()), route.channelMap(), true));
        }
        if (!route.filters().isEmpty()) {
            List<Link> intoCapture = route.channelMatrix() == null
                    ? channelLinks(outputs.getOrDefault(route.filterOutputNodeName(), List.of()),
                            inputs.getOrDefault(route.captureNodeName(), List.of()), route.channelMap(), false)
                    : positionLinks(outputs.getOrDefault(route.filterOutputNodeName(), List.of()),
                            inputs.getOrDefault(route.captureNodeName(), List.of()));
            links.addAll(intoCapture);
        }

        List<String> destinationPorts;
        if (route.targetBusId() != null) {
            BusPlan targetBus = plan.buses().get(route.targetBusId());
            if (targetBus == null) throw new IOException("Missing target bus " + route.targetBusId());
            destinationPorts = inputs.getOrDefault(targetBus.nodeName(), List.of());
        } else {
            if (route.sink() == null) throw new IOException("Route has no selected output");
            OutputPlan output = plan.outputs().get(route.sink().name());
            destinationPorts = output == null ? inputs.getOrDefault(route.sink().name(), List.of())
                    : inputs.getOrDefault(output.firstInputNodeName(), List.of());
        }
        List<Link> outOfRoute = route.channelMatrix() == null
                ? channelLinks(outputs.getOrDefault(route.playbackNodeName(), List.of()), destinationPorts,
                        route.channelMap(), true)
                : positionLinks(outputs.getOrDefault(route.playbackNodeName(), List.of()), destinationPorts);
        if (outOfRoute.isEmpty()) throw new IOException("No compatible output ports");
        links.addAll(outOfRoute);
        if (links.isEmpty()) throw new IOException("No compatible PipeWire ports");
        return distinct(links);
    }

    private List<Link> outputLinks(OutputPlan output, Map<String, List<String>> inputs,
                                   Map<String, List<String>> outputs) throws IOException {
        List<Link> links = new ArrayList<>();
        if (!output.filters().isEmpty()) {
            String nextInput = output.limiterFilters().isEmpty()
                    ? output.sink().name() : output.limiterInputNodeName();
            List<Link> toNext = channelLinks(outputs.getOrDefault(output.filterOutputNodeName(), List.of()),
                    inputs.getOrDefault(nextInput, List.of()), "stereo", false);
            if (toNext.isEmpty()) throw new IOException("Output filter ports are unavailable");
            links.addAll(toNext);
        }
        if (!output.limiterFilters().isEmpty()) {
            List<Link> toSink = channelLinks(outputs.getOrDefault(output.limiterOutputNodeName(), List.of()),
                    inputs.getOrDefault(output.sink().name(), List.of()), "stereo", false);
            if (toSink.isEmpty()) throw new IOException("Output limiter ports are unavailable");
            links.addAll(toSink);
        }
        return distinct(links);
    }

    private List<Link> streamLinks(StreamPlan stream, Plan plan, Map<String, List<String>> inputs,
                                   Map<String, List<String>> outputs) throws IOException {
        BusPlan targetBus = plan.buses().get(stream.busId());
        if (targetBus == null) throw new IOException("Missing stream target bus " + stream.busId());
        String firstTarget = stream.filters().isEmpty() ? targetBus.nodeName() : stream.filterInputNodeName();
        List<Link> intoBus = channelLinks(outputs.getOrDefault(stream.node().name(), List.of()),
                inputs.getOrDefault(firstTarget, List.of()),
                stream.filters().isEmpty() ? targetBus.channelLayout() : "stereo", true);
        List<Link> fromFilter = stream.filters().isEmpty() ? List.of() : channelLinks(
                outputs.getOrDefault(stream.filterOutputNodeName(), List.of()),
                inputs.getOrDefault(targetBus.nodeName(), List.of()), targetBus.channelLayout(), true);
        if (intoBus.isEmpty() || (!stream.filters().isEmpty() && fromFilter.isEmpty()))
            throw new IOException("PipeWire ports do not match bus " + stream.busId());
        List<Link> links = new ArrayList<>(intoBus);
        links.addAll(fromFilter);
        return distinct(links);
    }

    private boolean connectOwnerLinks(String owner, List<Link> links) {
        Set<Link> previous = linkOwners.getOrDefault(owner, Set.of());
        try {
            connectAll(links);
            refreshObservedLinks();
            Set<Link> missing = new LinkedHashSet<>(links);
            missing.removeAll(observedLinks);
            if (!missing.isEmpty()) throw new IOException("PipeWire did not confirm " + missing);
            linkOwners.put(owner, new LinkedHashSet<>(links));
            branchErrors.remove(owner);
            resetDesiredLinks();
            return true;
        } catch (Exception error) {
            Set<Link> otherOwners = allOwnerLinksExcept(owner);
            Set<Link> added = new LinkedHashSet<>(ownedLinks);
            added.removeAll(previous);
            added.removeAll(otherOwners);
            List<Link> liveAdded = added.stream().filter(observedLinks::contains).toList();
            disconnectAll(liveAdded);
            for (Link link : added) if (!observedLinks.contains(link)) ownedLinks.remove(link);
            resetDesiredLinks();
            branchErrors.put(owner, message(error));
            transitionWarnings.add("Could not reconcile " + owner + ": " + message(error));
            return false;
        }
    }

    private Set<Link> allOwnerLinks() { return allOwnerLinksExcept(null); }

    private Set<Link> allOwnerLinksExcept(String excludedOwner) {
        Set<Link> links = new LinkedHashSet<>();
        linkOwners.forEach((owner, owned) -> {
            if (!owner.equals(excludedOwner)) links.addAll(owned);
        });
        return links;
    }

    private void resetDesiredLinks() {
        desiredLinks.clear();
        desiredLinks.addAll(allOwnerLinks());
    }

    private boolean hasObservedRoute(Plan plan) {
        for (RoutePlan route : plan.routes()) {
            ManagedProcess process = routeProcesses.get(route.id());
            Set<Link> links = linkOwners.get(routeOwner(route.id()));
            if (process != null && process.process().isAlive() && links != null && !links.isEmpty()
                    && observedLinks.containsAll(links)) return true;
        }
        return false;
    }

    private static String busOwner(String id) { return "bus:" + id; }
    private static String routeOwner(String id) { return "route:" + id; }
    private static String outputOwner(String name) { return "output:" + name; }
    private static String streamOwner(StreamPlan stream) {
        return "stream:" + stream.bindingId() + ":" + stream.node().name();
    }
}
